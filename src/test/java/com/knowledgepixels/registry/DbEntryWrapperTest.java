package com.knowledgepixels.registry;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbEntryWrapperTest {

    @Test
    void constructor() {
        DbEntryWrapper dbEntryWrapper1 = new DbEntryWrapper(EntryStatus.toRetrieve);
        assertNotNull(dbEntryWrapper1);
        assertNotNull(dbEntryWrapper1.getDocument());

        Document document = new Document("key", "value");
        DbEntryWrapper dbEntryWrapper2 = new DbEntryWrapper(document);
        assertNotNull(dbEntryWrapper2);
        assertEquals(document, dbEntryWrapper2.getDocument());

        DbEntryWrapper dbEntryWrapper3 = new DbEntryWrapper(document, EntryStatus.retrieved);
        assertNotNull(dbEntryWrapper3);
        assertEquals(document, dbEntryWrapper3.getDocument());
    }

    @Test
    void getDocument() {
        DbEntryWrapper dbEntryWrapper = new DbEntryWrapper(EntryStatus.toRetrieve);
        Document document = new Document(DbEntryWrapper.statusField, EntryStatus.toRetrieve.getValue());
        assertEquals(dbEntryWrapper.getDocument(), document);
    }

    @Test
    void setStatus() {
        DbEntryWrapper dbEntryWrapper = new DbEntryWrapper(EntryStatus.toRetrieve);
        dbEntryWrapper.setStatus(EntryStatus.retrieved);
        Document document = new Document(DbEntryWrapper.statusField, EntryStatus.retrieved.getValue());
        assertEquals(dbEntryWrapper.getDocument(), document);
    }

}