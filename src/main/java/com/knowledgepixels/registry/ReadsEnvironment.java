package com.knowledgepixels.registry;

public class ReadsEnvironment {

    private final GetEnv getEnv;

    public ReadsEnvironment(GetEnv getEnv) {
        this.getEnv = getEnv;
    }

    public String getEnv(String name) {
        return getEnv.get(name);
    }

}
