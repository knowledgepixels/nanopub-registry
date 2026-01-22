package com.knowledgepixels.registry;

public enum Collection {

    SERVER_INFO("serverInfo"),
    SETTING("setting"),
    AGENTS("agents"),
    ACCOUNTS("accounts"),
    NANOPUBS("nanopubs"),

    TASKS("tasks");

    private final String value;

    Collection(final String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return this.value;
    }

}
