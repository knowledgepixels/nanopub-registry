package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.RegistryDB;
import com.mongodb.client.ClientSession;

/**
 * The MongoDB backend: readers open a session on the client {@code RegistryDB} holds.
 */
public class MongoRegistryStore implements RegistryStore {

    @Override
    public RegistryReader reader() {
        ClientSession s = RegistryDB.getClient().startSession();
        return new MongoRegistryReader(s, true);
    }

    @Override
    public RegistryReader snapshotReader() {
        ClientSession s = RegistryDB.getClient().startSession();
        s.startTransaction();
        return new MongoRegistryReader(s, true);
    }

    @Override
    public void close() {
        if (RegistryDB.getClient() != null) {
            RegistryDB.getClient().close();
        }
    }

}
