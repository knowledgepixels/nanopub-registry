package com.knowledgepixels.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionTest {

    @Test
    void testToString() {
        assertEquals("serverInfo", Collection.SERVER_INFO.toString());
        assertEquals("setting", Collection.SETTING.toString());
        assertEquals("agents", Collection.AGENTS.toString());
        assertEquals("accounts", Collection.ACCOUNTS.toString());
        assertEquals("nanopubs", Collection.NANOPUBS.toString());
    }

}