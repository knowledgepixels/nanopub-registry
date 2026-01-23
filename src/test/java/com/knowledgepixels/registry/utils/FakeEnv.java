package com.knowledgepixels.registry.utils;

import com.knowledgepixels.registry.ReadsEnvironment;
import com.knowledgepixels.registry.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

/**
 * Utility class to create a fake environment for testing purposes.
 */
public class FakeEnv {

    private static final Logger logger = LoggerFactory.getLogger(FakeEnv.class);
    private static FakeEnv instance = null;
    private final HashMap<String, String> variables;

    private FakeEnv() {
        logger.info("Initializing FakeEnv instance");
        this.variables = new HashMap<>();
    }

    /**
     * Gets the singleton instance of FakeEnv.
     *
     * @return the FakeEnv instance
     */
    public static FakeEnv getInstance() {
        logger.info("Getting instance of FakeEnv");
        if (instance == null) {
            instance = new FakeEnv();
        }
        return instance;
    }

    /**
     * Adds a fake environment variable.
     *
     * @param key   the environment variable key
     * @param value the environment variable value
     * @return the FakeEnv instance for chaining
     */
    public FakeEnv addVariable(String key, String value) {
        logger.info("Adding fake env variable: {}={}", key, value);
        this.variables.put(key, value);
        return this;
    }

    /**
     * Builds the fake environment by setting the environment reader.
     */
    public void build() {
        logger.info("Building fake environment with {} variables", this.variables.size());
        Utils.setEnvReader(new ReadsEnvironment(this.variables::get));
    }

    /**
     * Resets the fake environment and sets the environment reader back to the system environment.
     */
    public void reset() {
        logger.info("Resetting fake environment and setting the environment reader back to system environment");
        this.variables.clear();
        Utils.setEnvReader(new ReadsEnvironment(System::getenv));
    }

}
