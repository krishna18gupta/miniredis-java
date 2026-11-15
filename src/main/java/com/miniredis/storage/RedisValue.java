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

    public static RedisValue ofByteSequence(ByteSequence value) {
        return new RedisValue(RedisDataType.STRING, Objects.requireNonNull(value, "value cannot be null"));
    }

    public static RedisValue ofString(String value) {
        Objects.requireNonNull(value, "string value cannot be null");
        return new RedisValue(RedisDataType.STRING, ByteSequence.ofUtf8(value));
    }

    public static RedisValue ofByteList() {
        return new RedisValue(RedisDataType.LIST, new LinkedList<ByteSequence>());
    }

    public static RedisValue ofByteList(Deque<ByteSequence> list) {
        return new RedisValue(RedisDataType.LIST, Objects.requireNonNull(list, "list cannot be null"));
    }

    public static RedisValue ofList() {
        return ofByteList();
    }

    public static RedisValue ofList(Deque<String> list) {
        Objects.requireNonNull(list, "list cannot be null");
        Deque<ByteSequence> byteList = new LinkedList<>();
        for (String s : list) {
            byteList.add(s != null ? ByteSequence.ofUtf8(s) : ByteSequence.EMPTY);
        }
        return ofByteList(byteList);
    }

    public static RedisValue ofByteSet() {
        return new RedisValue(RedisDataType.SET, new LinkedHashSet<ByteSequence>());
    }

    public static RedisValue ofByteSet(Set<ByteSequence> set) {
        return new RedisValue(RedisDataType.SET, Objects.requireNonNull(set, "set cannot be null"));
    }

    public static RedisValue ofSet() {
        return ofByteSet();
    }

    public static RedisValue ofSet(Set<String> set) {
        Objects.requireNonNull(set, "set cannot be null");
        Set<ByteSequence> byteSet = new LinkedHashSet<>(set.size());
        for (String s : set) {
            byteSet.add(s != null ? ByteSequence.ofUtf8(s) : ByteSequence.EMPTY);
        }
        return ofByteSet(byteSet);
    }

    public static RedisValue ofByteHash() {
        return new RedisValue(RedisDataType.HASH, new LinkedHashMap<ByteSequence, ByteSequence>());
    }

    public static RedisValue ofByteHash(Map<ByteSequence, ByteSequence> hash) {
        return new RedisValue(RedisDataType.HASH, Objects.requireNonNull(hash, "hash cannot be null"));
    }

    public static RedisValue ofHash() {
        return ofByteHash();
    }

    public static RedisValue ofHash(Map<String, String> hash) {
        Objects.requireNonNull(hash, "hash cannot be null");
        Map<ByteSequence, ByteSequence> byteHash = new LinkedHashMap<>(hash.size());
        for (Map.Entry<String, String> e : hash.entrySet()) {
            byteHash.put(
                    e.getKey() != null ? ByteSequence.ofUtf8(e.getKey()) : ByteSequence.EMPTY,
                    e.getValue() != null ? ByteSequence.ofUtf8(e.getValue()) : ByteSequence.EMPTY);
        }
        return ofByteHash(byteHash);
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

    public ByteSequence getAsByteSequence() {
        if (type != RedisDataType.STRING) {
            throw new WrongTypeException();
        }
        if (data instanceof ByteSequence bs) {
            return bs;
        }
        return ByteSequence.ofUtf8(data.toString());
    }

    public String getAsString() {
        return getAsByteSequence().toUtf8String();
    }

    @SuppressWarnings("unchecked")
    public Deque<ByteSequence> getAsByteList() {
        if (type != RedisDataType.LIST) {
            throw new WrongTypeException();
        }
        return (Deque<ByteSequence>) data;
    }

    public Deque<String> getAsList() {
        Deque<ByteSequence> byteList = getAsByteList();
        Deque<String> strList = new LinkedList<>();
        for (ByteSequence b : byteList) {
            strList.add(b != null ? b.toUtf8String() : "");
        }
        return strList;
    }

    @SuppressWarnings("unchecked")
    public Set<ByteSequence> getAsByteSet() {
        if (type != RedisDataType.SET) {
            throw new WrongTypeException();
        }
        return (Set<ByteSequence>) data;
    }

    public Set<String> getAsSet() {
        Set<ByteSequence> byteSet = getAsByteSet();
        Set<String> strSet = new LinkedHashSet<>(byteSet.size());
        for (ByteSequence b : byteSet) {
            strSet.add(b != null ? b.toUtf8String() : "");
        }
        return strSet;
    }

    @SuppressWarnings("unchecked")
    public Map<ByteSequence, ByteSequence> getAsByteHash() {
        if (type != RedisDataType.HASH) {
            throw new WrongTypeException();
        }
        return (Map<ByteSequence, ByteSequence>) data;
    }

    public Map<String, String> getAsHash() {
        Map<ByteSequence, ByteSequence> byteMap = getAsByteHash();
        Map<String, String> strMap = new LinkedHashMap<>(byteMap.size());
        for (Map.Entry<ByteSequence, ByteSequence> e : byteMap.entrySet()) {
            strMap.put(
                    e.getKey() != null ? e.getKey().toUtf8String() : "",
                    e.getValue() != null ? e.getValue().toUtf8String() : "");
        }
        return strMap;
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
                copy = RedisValue.ofByteSequence(getAsByteSequence());
                break;
            case LIST:
                copy = RedisValue.ofByteList(new LinkedList<>(getAsByteList()));
                break;
            case SET:
                copy = RedisValue.ofByteSet(new LinkedHashSet<>(getAsByteSet()));
                break;
            case HASH:
                copy = RedisValue.ofByteHash(new LinkedHashMap<>(getAsByteHash()));
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