package com.knowledgepixels.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReadsEnvironment {

    private static final Logger logger = LoggerFactory.getLogger(ReadsEnvironment.class);

    private final GetEnv getEnv;

    public ReadsEnvironment(GetEnv getEnv) {
        this.getEnv = getEnv;
    }

    public String getEnv(String name) {
        String value = getEnv.get(name);
        if (value == null) {
            logger.debug("Environment variable '{}' is not set", name);
        } else {
            logger.debug("Environment variable '{}' read (length={} chars)", name, value.length());
        }
        return value;
    }

}
