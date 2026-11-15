package com.miniredis.protocol;

import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance serializer for encoding commands and responses into RESP2 wire format.
 * Accurately calculates UTF-8 byte lengths for multibyte characters and handles all standard
 * data types, empty values, nulls, and nested arrays.
 */
public class RespSerializer {

    public static final byte[] CRLF = new byte[]{'\r', '\n'};
    public static final byte[] NULL_BULK = new byte[]{'$', '-', '1', '\r', '\n'};
    public static final byte[] NULL_ARRAY = new byte[]{'*', '-', '1', '\r', '\n'};
    public static final byte[] EMPTY_ARRAY = new byte[]{'*', '0', '\r', '\n'};
    public static final byte[] EMPTY_BULK = new byte[]{'$', '0', '\r', '\n', '\r', '\n'};

    /**
     * Serializes command tokens into a RESP Array of Bulk Strings:
     * *<argc>\r\n$<len>\r\n<arg1>\r\n...
     */
    public static byte[] encodeCommand(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return EMPTY_ARRAY;
        }
        List<ByteSequence> byteTokens = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            byteTokens.add(t != null ? ByteSequence.ofUtf8(t) : null);
        }
        return encodeCommandBytes(byteTokens);
    }

    /**
     * Serializes raw binary ByteSequence command tokens directly into RESP wire format.
     */
    public static byte[] encodeCommandBytes(List<ByteSequence> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return EMPTY_ARRAY;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * tokens.size());
        try {
            out.write('*');
            out.write(Integer.toString(tokens.size()).getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);

            for (ByteSequence token : tokens) {
                if (token == null) {
                    out.write(NULL_BULK);
                } else {
                    out.write('$');
                    out.write(Integer.toString(token.length()).getBytes(StandardCharsets.US_ASCII));
                    out.write(CRLF);
                    token.writeTo(out);
                    out.write(CRLF);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Unexpected byte array write error", e);
        }

        return out.toByteArray();
    }

    /**
     * Serializes any RedisResponse into its wire RESP2 byte representation.
     */
    public static byte[] serializeResponse(RedisResponse response) {
        if (response == null) {
            return NULL_BULK;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        try {
            writeResponse(out, response);
        } catch (IOException e) {
            throw new RuntimeException("Serialization failure", e);
        }
        return out.toByteArray();
    }

    private static void writeResponse(ByteArrayOutputStream out, RedisResponse resp) throws IOException {
        switch (resp.getType()) {
            case SIMPLE_STRING:
                out.write('+');
                out.write(resp.getStringValue().getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
                break;
            case ERROR:
                out.write('-');
                out.write(resp.getStringValue().getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
                break;
            case INTEGER:
                out.write(':');
                out.write(Long.toString(resp.getIntValue()).getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF);
                break;
            case BULK_STRING:
                ByteSequence bs = resp.getByteValue();
                if (bs == null) {
                    out.write(NULL_BULK);
                } else if (bs.isEmpty()) {
                    out.write(EMPTY_BULK);
                } else {
                    out.write('$');
                    out.write(Integer.toString(bs.length()).getBytes(StandardCharsets.US_ASCII));
                    out.write(CRLF);
                    bs.writeTo(out);
                    out.write(CRLF);
                }
                break;
            case NIL:
                out.write(NULL_BULK);
                break;
            case ARRAY:
                List<RedisResponse> arr = resp.getArrayValue();
                if (arr == null) {
                    out.write(NULL_ARRAY);
                } else if (arr.isEmpty()) {
                    out.write(EMPTY_ARRAY);
                } else {
                    out.write('*');
                    out.write(Integer.toString(arr.size()).getBytes(StandardCharsets.US_ASCII));
                    out.write(CRLF);
                    for (RedisResponse item : arr) {
                        writeResponse(out, item);
                    }
                }
                break;
            default:
                out.write('+');
                out.write("OK".getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF);
                break;
        }
    }
}
