package com.miniredis.storage;

import java.util.*;

/**
 * Wrapper for stored Redis values.
 * Holds the underlying object, its type tag, creation time, and optional expiration time.
 * Note: LinkedHashSet is used internally for Sets purely as a deterministic implementation
 * detail for reproducible outputs during testing; Sets in Redis are conceptually unordered.
 */
public class RedisValue {
    private final RedisDataType type;
    private volatile Object data;
    private final long createdAt;
    private volatile Long expireAt; // Expiration epoch millis, null if persistent

    public RedisValue(RedisDataType type, Object data) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.createdAt = System.currentTimeMillis();
        this.expireAt = null;
    }

    public static RedisValue ofString(String value) {
        return new RedisValue(RedisDataType.STRING, Objects.requireNonNull(value, "string value cannot be null"));
    }

    public static RedisValue ofList() {
        return new RedisValue(RedisDataType.LIST, new LinkedList<String>());
    }

    public static RedisValue ofList(Deque<String> list) {
        return new RedisValue(RedisDataType.LIST, Objects.requireNonNull(list, "list cannot be null"));
    }

    public static RedisValue ofSet() {
        // LinkedHashSet used for deterministic iteration during testing, conceptually unordered
        return new RedisValue(RedisDataType.SET, new LinkedHashSet<String>());
    }

    public static RedisValue ofSet(Set<String> set) {
        return new RedisValue(RedisDataType.SET, Objects.requireNonNull(set, "set cannot be null"));
    }

    public static RedisValue ofHash() {
        return new RedisValue(RedisDataType.HASH, new LinkedHashMap<String, String>());
    }

    public static RedisValue ofHash(Map<String, String> hash) {
        return new RedisValue(RedisDataType.HASH, Objects.requireNonNull(hash, "hash cannot be null"));
    }

    public RedisDataType getType() {
        return type;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = Objects.requireNonNull(data, "data cannot be null");
    }

    public String getAsString() {
        if (type != RedisDataType.STRING) {
            throw new WrongTypeException();
        }
        return (String) data;
    }

    @SuppressWarnings("unchecked")
    public Deque<String> getAsList() {
        if (type != RedisDataType.LIST) {
            throw new WrongTypeException();
        }
        return (Deque<String>) data;
    }

    @SuppressWarnings("unchecked")
    public Set<String> getAsSet() {
        if (type != RedisDataType.SET) {
            throw new WrongTypeException();
        }
        return (Set<String>) data;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getAsHash() {
        if (type != RedisDataType.HASH) {
            throw new WrongTypeException();
        }
        return (Map<String, String>) data;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Long getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Long expireAt) {
        this.expireAt = expireAt;
    }

    public boolean isExpired(long now) {
        return expireAt != null && now >= expireAt;
    }

    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

    /**
     * Creates an independent deep copy of this RedisValue for snapshot isolation.
     */
    public RedisValue deepCopy() {
        RedisValue copy;
        switch (type) {
            case STRING:
                copy = RedisValue.ofString(getAsString());
                break;
            case LIST:
                copy = RedisValue.ofList(new LinkedList<>(getAsList()));
                break;
            case SET:
                copy = RedisValue.ofSet(new LinkedHashSet<>(getAsSet()));
                break;
            case HASH:
                copy = RedisValue.ofHash(new LinkedHashMap<>(getAsHash()));
                break;
            default:
                throw new IllegalStateException("Unknown data type: " + type);
        }
        copy.setExpireAt(this.expireAt);
        return copy;
    }

    @Override
    public String toString() {
        return "RedisValue{" +
                "type=" + type +
                ", data=" + data +
                ", expireAt=" + expireAt +
                '}';
    }
}