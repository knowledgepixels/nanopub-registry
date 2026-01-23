package com.knowledgepixels.registry.utils;

import org.testcontainers.mongodb.MongoDBContainer;

import java.util.Map;

/**
 * Utility class for setting up test environments and clearing static fields.
 */
public class TestUtils {

    private static FakeEnv fakeEnv;

    /**
     * Sets up a fake environment for testing.
     *
     */
    public static FakeEnv setupFakeEnv() {
        fakeEnv = FakeEnv.getInstance();
        return fakeEnv;
    }

    /**
     * Sets up the database environment variables for testing.
     *
     * @param mongoDBContainer the MongoDB container to use
     * @param dbName           the name of the database
     */
    public static void setupDBEnv(MongoDBContainer mongoDBContainer, String dbName) {
        if (fakeEnv == null) {
            throw new IllegalStateException("FakeEnv not initialized. Call setupFakeEnv() first.");
        }
        fakeEnv.addVariable("REGISTRY_DB_NAME", dbName)
                .addVariable("REGISTRY_DB_HOST", mongoDBContainer.getHost())
                .addVariable("REGISTRY_DB_PORT", String.valueOf(mongoDBContainer.getFirstMappedPort()))
                .build();
    }

    /**
     * Clears the specified static fields of the given class by setting them to null.
     *
     * @param clazz      the class whose static fields are to be cleared
     * @param fieldNames the names of the static fields to be cleared
     * @throws IllegalAccessException thrown if the field cannot be accessed
     * @throws NoSuchFieldException   thrown if the field does not exist
     */
    public static void clearStaticFields(Class<?> clazz, String... fieldNames) throws IllegalAccessException, NoSuchFieldException {
        for (String fieldName : fieldNames) {
            var field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, null);
        }
    }

    /**
     * Clears the specified static fields of the given class by setting them to the provided values.
     *
     * @param clazz          the class whose static fields are to be cleared
     * @param fieldNameValue a map of field names to their desired values
     */
    public static void clearStaticFields(Class<?> clazz, Map<String, Object> fieldNameValue) {
        fieldNameValue.forEach((fieldName, value) -> {
            try {
                var field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(null, value);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

}
