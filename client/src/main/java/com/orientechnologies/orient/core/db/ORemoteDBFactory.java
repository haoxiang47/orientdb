package com.orientechnologies.orient.core.db;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.client.remote.OServerAdmin;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentRemote;
import com.orientechnologies.orient.core.engine.OEngine;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.storage.OStorage;

import java.io.IOException;
import java.util.*;

/**
 * Created by tglman on 08/04/16.
 */
public class ORemoteDBFactory implements OrientDBFactory {
  private String[] hosts;
  private Map<String, OStorage> storages = new HashMap<>();
  private OEngine          remote;
  private OrientDBSettings configuration;

  public ORemoteDBFactory(String[] hosts, OrientDBSettings configuration) {
    super();
    this.hosts = hosts;
    remote = Orient.instance().getEngine("remote");
    this.configuration = configuration;
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      ORemoteDBFactory.this.close();
      Runtime.getRuntime().removeShutdownHook(Thread.currentThread());
    }));
  }

  private String buildUrl(String name) {
    return hosts[0] + "/" + name;
  }

  @Override
  public synchronized ODatabaseDocument open(String name, String user, String password) {
    OStorage storage = storages.get(name);
    if (storage == null) {
      storage = remote.createStorage(buildUrl(name), new HashMap<>());
    }
    ODatabaseDocumentRemote db = new ODatabaseDocumentRemote(storage);
    db.internalOpen(user, password);
    return db;
  }

  @Override
  public synchronized void create(String name, String user, String password, DatabaseType databaseType) {
    connectEndExecute(name, user, password, admin -> {
      String sendType = null;
      if (databaseType == DatabaseType.MEMORY) {
        sendType = "memory";
      } else if (databaseType == DatabaseType.PLOCAL) {
        sendType = "plocal";
      }
      admin.createDatabase(name, null, sendType);
      return null;
    });
  }

  public ORemoteDatabasePool poolOpen(String name, String user, String password, ORemotePoolByFactory pool) {
    OStorage storage = storages.get(name);
    if (storage == null) {
      storage = remote.createStorage(buildUrl(name), new HashMap<>());
    }
    ORemoteDatabasePool db = new ORemoteDatabasePool(pool, storage);
    db.internalOpen(user, password);
    return db;
  }

  private interface Operation<T> {
    T execute(OServerAdmin admin) throws IOException;
  }

  private <T> T connectEndExecute(String name, String user, String password, Operation<T> operation) {
    OServerAdmin admin = null;
    try {
      admin = new OServerAdmin(buildUrl(name));
      admin.connect(user, password);
      return operation.execute(admin);
    } catch (IOException e) {
      throw OException.wrapException(new ODatabaseException("Error createing remote database "), e);
    } finally {
      if (admin != null)
        admin.close();
    }
  }

  @Override
  public synchronized boolean exist(String name, String user, String password) {
    return connectEndExecute(name, user, password, admin -> {
      //TODO: check for memory cases
      return admin.existsDatabase(name, null);
    });
  }

  @Override
  public synchronized void drop(String name, String user, String password) {
    connectEndExecute(name, user, password, admin -> {
      //TODO: check for memory cases
      return admin.dropDatabase(name, null);
    });
  }

  @Override
  public Set<String> listDatabases(String user, String password) {
    return connectEndExecute("", user, password, admin -> {
      //TODO: check for memory cases
      return admin.listDatabases().keySet();
    });
  }

  @Override
  public OPool<ODatabaseDocument> openPool(String name, String user, String password, Map<String, Object> poolSettings) {
    return new ORemotePoolByFactory(this, name, user, password, this.configuration);
  }

  @Override
  public void close() {
    final List<OStorage> storagesCopy = new ArrayList<OStorage>(storages.values());
    for (OStorage stg : storagesCopy) {
      try {
        OLogManager.instance().info(this, "- shutdown storage: " + stg.getName() + "...");
        stg.shutdown();
      } catch (Throwable e) {
        OLogManager.instance().warn(this, "-- error on shutdown storage", e);
      }
    }
    storages.clear();

    // SHUTDOWN ENGINES
    remote.shutdown();
  }
}
