package com.miniredis.commands;

import com.miniredis.storage.ByteSequence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates a Redis response.
 * Supports formatting for human-friendly CLI and wire-level RESP2 protocol.
 */
public class RedisResponse {

    public enum ResponseType {
        SIMPLE_STRING,
        BULK_STRING,
        INTEGER,
        NIL,
        ERROR,
        ARRAY
    }

    private final ResponseType type;
    private final String stringValue;
    private final ByteSequence byteValue;
    private final Long intValue;
    private final List<RedisResponse> arrayValue;
    private final boolean mutated;

    private RedisResponse(ResponseType type, String stringValue, ByteSequence byteValue, Long intValue, List<RedisResponse> arrayValue, boolean mutated) {
        this.type = type;
        this.stringValue = stringValue;
        this.byteValue = byteValue;
        this.intValue = intValue;
        this.arrayValue = arrayValue;
        this.mutated = mutated;
    }

    private RedisResponse(ResponseType type, String stringValue, ByteSequence byteValue, Long intValue, List<RedisResponse> arrayValue) {
        this(type, stringValue, byteValue, intValue, arrayValue, true);
    }

    // Factory methods
    public static RedisResponse ok() {
        return new RedisResponse(ResponseType.SIMPLE_STRING, "OK", null, null, null);
    }

    public static RedisResponse simpleString(String message) {
        return new RedisResponse(ResponseType.SIMPLE_STRING, message, null, null, null);
    }

    public static RedisResponse bulkString(ByteSequence content) {
        if (content == null) {
            return nil();
        }
        return new RedisResponse(ResponseType.BULK_STRING, null, content, null, null);
    }

    public static RedisResponse bulkString(byte[] content) {
        if (content == null) {
            return nil();
        }
        return new RedisResponse(ResponseType.BULK_STRING, null, ByteSequence.of(content), null, null);
    }

    public static RedisResponse bulkString(String content) {
        if (content == null) {
            return nil();
        }
        return new RedisResponse(ResponseType.BULK_STRING, content, ByteSequence.ofUtf8(content), null, null);
    }

    public static RedisResponse nil() {
        return new RedisResponse(ResponseType.NIL, null, null, null, null);
    }

    public static RedisResponse integer(long value) {
        return new RedisResponse(ResponseType.INTEGER, null, null, value, null);
    }

    public static RedisResponse error(String message) {
        return new RedisResponse(ResponseType.ERROR, message, null, null, null);
    }

    public static RedisResponse array(List<RedisResponse> elements) {
        return new RedisResponse(ResponseType.ARRAY, null, null, null, elements);
    }

    public static RedisResponse nullArray() {
        return new RedisResponse(ResponseType.ARRAY, null, null, null, null);
    }

    public static RedisResponse stringList(Collection<String> strings) {
        if (strings == null) {
            return array(Collections.emptyList());
        }
        List<RedisResponse> list = new ArrayList<>(strings.size());
        for (String s : strings) {
            list.add(bulkString(s));
        }
        return array(list);
    }

    public static RedisResponse byteList(Collection<ByteSequence> sequences) {
        if (sequences == null) {
            return array(Collections.emptyList());
        }
        List<RedisResponse> list = new ArrayList<>(sequences.size());
        for (ByteSequence bs : sequences) {
            list.add(bulkString(bs));
        }
        return array(list);
    }

    public ResponseType getType() {
        return type;
    }

    public String getStringValue() {
        if (stringValue != null) {
            return stringValue;
        }
        if (byteValue != null) {
            return byteValue.toUtf8String();
        }
        return null;
    }

    public ByteSequence getByteValue() {
        if (byteValue != null) {
            return byteValue;
        }
        if (stringValue != null) {
            return ByteSequence.ofUtf8(stringValue);
        }
        return null;
    }

    public Long getIntValue() {
        return intValue;
    }

    public List<RedisResponse> getArrayValue() {
        return arrayValue;
    }

    public boolean isMutated() {
        return mutated;
    }

    public RedisResponse withMutated(boolean mutated) {
        if (this.mutated == mutated) {
            return this;
        }
        return new RedisResponse(this.type, this.stringValue, this.byteValue, this.intValue, this.arrayValue, mutated);
    }

    /**
     * Formats the response for interactive CLI output (similar to redis-cli).
     */
    public String toCliString() {
        switch (type) {
            case SIMPLE_STRING:
                return stringValue;
            case BULK_STRING:
                ByteSequence bsCli = getByteValue();
                if (bsCli == null) return "(nil)";
                return bsCli.toDisplayString();
            case NIL:
                return "(nil)";
            case INTEGER:
                return "(integer) " + intValue;
            case ERROR:
                return "(error) " + stringValue;
            case ARRAY:
                if (arrayValue == null) {
                    return "(nil)";
                }
                if (arrayValue.isEmpty()) {
                    return "(empty array)";
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arrayValue.size(); i++) {
                    sb.append(i + 1).append(") ").append(arrayValue.get(i).toCliString());
                    if (i < arrayValue.size() - 1) {
                        sb.append("\n");
                    }
                }
                return sb.toString();
            default:
                return "";
        }
    }

    /**
     * Formats the response for inline text protocol terminated by CRLF.
     */
    public String toInlineResponse() {
        String cli = toCliString();
        return cli.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n";
    }

    /**
     * Formats the response according to the RESP2 wire protocol.
     */
    public String toRespWireFormat() {
        switch (type) {
            case SIMPLE_STRING:
                return "+" + stringValue + "\r\n";
            case ERROR:
                return "-" + stringValue + "\r\n";
            case INTEGER:
                return ":" + intValue + "\r\n";
            case BULK_STRING:
                ByteSequence bsWire = getByteValue();
                if (bsWire == null) {
                    return "$-1\r\n";
                }
                return "$" + bsWire.length() + "\r\n" + bsWire.toUtf8String() + "\r\n";
            case NIL:
                return "$-1\r\n";
            case ARRAY:
                if (arrayValue == null) {
                    return "*-1\r\n";
                }
                StringBuilder asb = new StringBuilder();
                asb.append("*").append(arrayValue.size()).append("\r\n");
                for (RedisResponse item : arrayValue) {
                    asb.append(item != null ? item.toRespWireFormat() : "$-1\r\n");
                }
                return asb.toString();
            default:
                return "+OK\r\n";
        }
    }

    @Override
    public String toString() {
        return toCliString();
    }
}
