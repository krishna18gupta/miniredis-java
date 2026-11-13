package com.miniredis.storage;

/**
 * Enumeration of Redis data structures supported by MiniRedis.
 */
public enum RedisDataType {
    STRING("string"),
    LIST("list"),
    SET("set"),
    HASH("hash");

    private final String typeName;

    RedisDataType(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeName() {
        return typeName;
    }
}
