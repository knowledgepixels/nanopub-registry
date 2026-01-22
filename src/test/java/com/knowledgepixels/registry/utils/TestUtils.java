package com.knowledgepixels.registry.utils;

import com.knowledgepixels.registry.ReadsEnvironment;
import com.knowledgepixels.registry.Utils;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for setting up test environments and clearing static fields.
 */
public class TestUtils {

    /**
     * Sets up a fake environment for testing with the provided MongoDB container.
     *
     * @param mongoDBContainer the MongoDB container to use for the fake environment
     */
    public static void setupFakeEnv(MongoDBContainer mongoDBContainer) {
        Map<String, String> fakeEnv = new HashMap<>();
        fakeEnv.put("REGISTRY_DB_NAME", "nanopubRegistry");
        fakeEnv.put("REGISTRY_DB_HOST", mongoDBContainer.getHost());
        fakeEnv.put("REGISTRY_DB_PORT", String.valueOf(mongoDBContainer.getFirstMappedPort()));
        ReadsEnvironment reader = new ReadsEnvironment(fakeEnv::get);
        Utils.setEnvReader(reader);
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
