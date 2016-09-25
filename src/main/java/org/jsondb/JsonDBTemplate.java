/*
 * Copyright (c) 2016 Farooq Khan
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.jsondb;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.jxpath.JXPathContext;
import org.jsondb.crypto.ICipher;
import org.jsondb.io.JsonFileLockException;
import org.jsondb.io.JsonReader;
import org.jsondb.query.CollectionSchemaUpdate;
import org.jsondb.query.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * @version 1.0 25-Sep-2016
 */
public class JsonDBTemplate implements JsonDBOperations {
  private Logger logger = LoggerFactory.getLogger(JsonDBTemplate.class);

  private static final Collection<String> RESTRICTED_CLASSES;

  private JsonDBConfig dbConfig = null;
  private final boolean encrypted;
  private File lockFilesLocation;

  private Map<String, CollectionMetaData> cmdMap;
  private AtomicReference<Map<String, File>> fileObjectsRef = new AtomicReference<Map<String, File>>(new ConcurrentHashMap<String, File>());
  private AtomicReference<Map<String, Map<Object, ?>>> collectionsRef = new AtomicReference<Map<String, Map<Object, ?>>>(new ConcurrentHashMap<String, Map<Object, ?>>());
  private AtomicReference<Map<String, JXPathContext>> contextsRef = new AtomicReference<Map<String, JXPathContext>>(new ConcurrentHashMap<String, JXPathContext>());


  static {

    Set<String> restrictedClasses = new HashSet<String>();
    restrictedClasses.add(List.class.getName());
    restrictedClasses.add(Collection.class.getName());
    restrictedClasses.add(Iterator.class.getName());
    restrictedClasses.add(HashSet.class.getName());

    RESTRICTED_CLASSES = Collections.unmodifiableCollection(restrictedClasses);
  }

  public JsonDBTemplate(String dbFilesLocationString, String baseScanPackage) {
    this(dbFilesLocationString, baseScanPackage, null, false, null);
  }

  public JsonDBTemplate(String dbFilesLocationString, String baseScanPackage, boolean compatibilityMode, Comparator<String> schemaComparator) {
    this(dbFilesLocationString, baseScanPackage, null, compatibilityMode, schemaComparator);
  }

  public JsonDBTemplate(String dbFilesLocationString, String baseScanPackage, ICipher cipher) {
    this(dbFilesLocationString, baseScanPackage, cipher, false, null);
  }

  public JsonDBTemplate(String dbFilesLocationString, String baseScanPackage, ICipher cipher, boolean compatibilityMode, Comparator<String> schemaComparator) {
    dbConfig = new JsonDBConfig(dbFilesLocationString, baseScanPackage, cipher, compatibilityMode, schemaComparator);
    this.encrypted = true;
    initialize();
  }

  private void initialize(){
    this.lockFilesLocation = new File(dbConfig.getDbFilesLocation(), "lock");
    if(!lockFilesLocation.exists()) {
      lockFilesLocation.mkdirs();
    }
    if (!dbConfig.getDbFilesLocation().exists()) {
      try {
        Files.createDirectory(dbConfig.getDbFilesPath());
      } catch (IOException e) {
        logger.error("DbFiles directory does not exist. Failed to create a new empty DBFiles directory {}", e);
        throw new InvalidJsonDbApiUsageException("DbFiles directory does not exist. Failed to create a new empty DBFiles directory " + dbConfig.getDbFilesLocationString());
      }
    } else if (dbConfig.getDbFilesLocation().isFile()) {
      throw new InvalidJsonDbApiUsageException("Specified DbFiles directory is actually a file cannot use it as a directory");
    }

    cmdMap = CollectionMetaData.builder(dbConfig);

    loadDB();

    // Auto-cleanup at shutdown
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        shutdown();
      }
    });
  }

  @Override
  public void reLoadDB() {
    loadDB();
  }

  private synchronized void loadDB() {
    for(String collectionName : cmdMap.keySet()) {
      File collectionFile = new File(dbConfig.getDbFilesLocation(), collectionName + ".json");
      if(collectionFile.exists()) {
        reloadCollection(collectionName);
      }
    }
  }

  public void reloadCollection(String collectionName) {
    CollectionMetaData cmd = cmdMap.get(collectionName);
    cmd.getCollectionLock().writeLock().lock();
    try {
      File collectionFile = fileObjectsRef.get().get(collectionName);
      if(null == collectionFile) {
        // Lets create a file now
        collectionFile = new File(dbConfig.getDbFilesLocation(), collectionName + ".json");
        if(!collectionFile.exists()) {
          throw new InvalidJsonDbApiUsageException("Collection by name '" + collectionName + "' cannot be found at " + collectionFile.getAbsolutePath());
        }
        Map<String, File> fileObjectMap = fileObjectsRef.get();
        Map<String, File> newFileObjectmap = new ConcurrentHashMap<String, File>(fileObjectMap);
        newFileObjectmap.put(collectionName, collectionFile);
        fileObjectsRef.set(newFileObjectmap);
      }
      if (null != cmd && null != collectionFile) {
        Map<Object, ?> collection = loadCollection(collectionFile, collectionName, cmd);
        if (null != collection) {
          JXPathContext newContext = JXPathContext.newContext(collection.values());
          contextsRef.get().put(collectionName, newContext);
          collectionsRef.get().put(collectionName, collection);
        }
      }
    } finally {
      cmd.getCollectionLock().writeLock().unlock();
    }
  }

  private <T> Map<Object, T> loadCollection(File collectionFile, String collectionName, CollectionMetaData cmd) {
    @SuppressWarnings("unchecked")
    Class<T> entity = cmd.getClazz();
    Method getterMethodForId = cmd.getIdAnnotatedFieldGetterMethod();

    JsonReader jr = null;
    Map<Object, T> collection = new LinkedHashMap<Object, T>();

    String line = null;
    int lineNo = 1;
    try {
      jr = new JsonReader(dbConfig, collectionFile);

      while ((line = jr.readLine()) != null) {
        if (lineNo == 1) {
          SchemaVersion v = dbConfig.getObjectMapper().readValue(line, SchemaVersion.class);
          cmd.setActualSchemaVersion(v.getSchemaVersion());
        } else {
          T row = dbConfig.getObjectMapper().readValue(line, entity);
          Object id = Util.getIdForEntity(row, getterMethodForId);
          collection.put(id, row);
        }
        lineNo++;
      }
    } catch (JsonParseException je) {
      logger.error("Failed Json Parsing for file {} line {}", collectionFile.getName(), lineNo, je);
      return null;
    } catch (JsonMappingException jm) {
      logger.error("Failed Mapping Parsed Json to Entity {} for file {} line {}",
          entity.getSimpleName(), collectionFile.getName(), lineNo, jm);
      return null;
    } catch (CharacterCodingException ce) {
      logger.error("Unsupported Character Encoding in file {} expected Encoding {}",
          collectionFile.getName(), dbConfig.getCharset().displayName(), ce);
      return null;
    } catch (JsonFileLockException jfe) {
      logger.error("Failed to acquire lock for collection file {}", collectionFile.getName(), jfe);
      return null;
    } catch (FileNotFoundException fe) {
      logger.error("Collection file {} not found", collectionFile.getName(), fe);
      return null;
    } catch (IOException e) {
      logger.error("Some IO Exception reading the Json File {}", collectionFile.getName(), e);
      return null;
    } catch(Throwable t) {
      logger.error("Throwable Caught ", collectionFile.getName(), t);
      return null;
    } finally {
      if (null != jr) {
        jr.close();
      }
    }
    return collection;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#shutdown()
   */
  @Override
  public void shutdown() {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#addCollectionFileChangeListener(org.jsondb.CollectionFileChangeListener)
   */
  @Override
  public void addCollectionFileChangeListener(
      CollectionFileChangeListener listener) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#removeCollectionFileChangeListener(org.jsondb.CollectionFileChangeListener)
   */
  @Override
  public void removeCollectionFileChangeListener(
      CollectionFileChangeListener listener) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#getCollectionName(java.lang.Class)
   */
  @Override
  public String getCollectionName(Class<?> entityClass) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#createCollection(java.lang.Class)
   */
  @Override
  public <T> void createCollection(Class<T> entityClass) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#createCollection(java.lang.String)
   */
  @Override
  public <T> void createCollection(String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#updateCollectionSchema(org.jsondb.query.CollectionSchemaUpdate, java.lang.Class)
   */
  @Override
  public <T> void updateCollectionSchema(CollectionSchemaUpdate update,
      Class<T> entityClass) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#updateCollectionSchema(org.jsondb.query.CollectionSchemaUpdate, java.lang.String)
   */
  @Override
  public <T> void updateCollectionSchema(CollectionSchemaUpdate update,
      String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#getCollectionNames()
   */
  @Override
  public Set<String> getCollectionNames() {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#getCollection(java.lang.Class)
   */
  @Override
  public <T> List<T> getCollection(Class<T> entityClass) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#collectionExists(java.lang.Class)
   */
  @Override
  public <T> boolean collectionExists(Class<T> entityClass) {
    // TODO Auto-generated method stub
    return false;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#collectionExists(java.lang.String)
   */
  @Override
  public boolean collectionExists(String collectionName) {
    // TODO Auto-generated method stub
    return false;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#isCollectionReadonly(java.lang.Class)
   */
  @Override
  public <T> boolean isCollectionReadonly(Class<T> entityClass) {
    // TODO Auto-generated method stub
    return false;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#isCollectionReadonly(java.lang.String)
   */
  @Override
  public <T> boolean isCollectionReadonly(String collectionName) {
    // TODO Auto-generated method stub
    return false;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#dropCollection(java.lang.Class)
   */
  @Override
  public <T> void dropCollection(Class<T> entityClass) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#dropCollection(java.lang.String)
   */
  @Override
  public void dropCollection(String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#find(java.lang.String, java.lang.Class)
   */
  @Override
  public <T> List<T> find(String jxQuery, Class<T> entityClass) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#find(java.lang.String, java.lang.String)
   */
  @Override
  public <T> List<T> find(String jxQuery, String collectionName) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findAll(java.lang.Class)
   */
  @Override
  public <T> List<T> findAll(Class<T> entityClass) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findAll(java.lang.String)
   */
  @Override
  public <T> List<T> findAll(String collectionName) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findById(java.lang.Object, java.lang.Class)
   */
  @Override
  public <T> T findById(Object id, Class<T> entityClass) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findById(java.lang.Object, java.lang.String)
   */
  @Override
  public <T> T findById(Object id, String collectionName) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findOne(java.lang.String, java.lang.Class)
   */
  @Override
  public <T> T findOne(String jxQuery, Class<T> entityClass) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findOne(java.lang.String, java.lang.String)
   */
  @Override
  public <T> T findOne(String jxQuery, String collectionName) {
    // TODO Auto-generated method stub
    return null;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#insert(java.lang.Object)
   */
  @Override
  public <T> void insert(Object objectToSave) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#insert(java.lang.Object, java.lang.String)
   */
  @Override
  public <T> void insert(Object objectToSave, String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#insert(java.util.Collection, java.lang.Class)
   */
  @Override
  public <T> void insert(Collection<? extends T> batchToSave,
      Class<T> entityClass) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#insert(java.util.Collection, java.lang.String)
   */
  @Override
  public <T> void insert(Collection<? extends T> batchToSave,
      String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findAndRemove(java.lang.String, java.lang.Class)
   */
  @Override
  public <T> int findAndRemove(String jxQuery, Class<T> entityClass) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findAndRemove(java.lang.String, java.lang.Class, java.lang.String)
   */
  @Override
  public <T> int findAndRemove(String jxQuery, Class<T> entityClass,
      String collectionName) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#remove(java.lang.Object, java.lang.Class)
   */
  @Override
  public <T> int remove(Object object, Class<T> entityClass) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#remove(java.lang.Object, java.lang.String)
   */
  @Override
  public <T> int remove(Object object, String collectionName) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#remove(java.util.Collection, java.lang.Class)
   */
  @Override
  public <T> int remove(Collection<? extends T> batchToRemove,
      Class<T> entityClass) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#remove(java.util.Collection, java.lang.String)
   */
  @Override
  public <T> int remove(Collection<? extends T> batchToRemove,
      String collectionName) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findAndModify(java.lang.String, org.jsondb.query.Update, java.lang.Class)
   */
  @Override
  public <T> int findAndModify(String jxQuery, Update update,
      Class<T> entityClass) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#findAndModify(java.lang.String, org.jsondb.query.Update, java.lang.String)
   */
  @Override
  public <T> int findAndModify(String jxQuery, Update update,
      String collectionName) {
    // TODO Auto-generated method stub
    return 0;
  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#save(java.lang.Object, java.lang.Class)
   */
  @Override
  public <T> void save(Object objectToSave, Class<T> entityClass) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#save(java.lang.Object, java.lang.String)
   */
  @Override
  public <T> void save(Object objectToSave, String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#upsert(java.lang.Object)
   */
  @Override
  public <T> void upsert(Object objectToSave) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#upsert(java.lang.Object, java.lang.String)
   */
  @Override
  public <T> void upsert(Object objectToSave, String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#upsert(java.util.Collection, java.lang.Class)
   */
  @Override
  public <T> void upsert(Collection<? extends T> batchToSave,
      Class<T> entityClass) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#upsert(java.util.Collection, java.lang.String)
   */
  @Override
  public <T> void upsert(Collection<? extends T> batchToSave,
      String collectionName) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#backup(java.lang.String)
   */
  @Override
  public void backup(String backupPath) {
    // TODO Auto-generated method stub

  }

  /* (non-Javadoc)
   * @see org.jsondb.JsonDBOperations#restore(java.lang.String, boolean)
   */
  @Override
  public void restore(String restorePath, boolean merge) {
    // TODO Auto-generated method stub

  }
}
