package com.knowledgepixels.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class RegistryDBTest {

    @Test
    void getDBWhenNotInitialized() {
        assertNull(RegistryDB.getDB());
    }

    @Test
    void getClientWhenNotInitialized() {
        assertNull(RegistryDB.getClient());
    }

}