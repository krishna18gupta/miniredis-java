package com.miniredis.protocol;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * High-performance, streaming RESP2 and inline command parser.
 * Reads directly from any InputStream (TCP socket, AOF file, ByteArrayInputStream).
 *
 * Automatically detects whether incoming data is formatted as RESP wire protocol
 * (*<count>\r\n...) or legacy inline text (<command> <arg1>...\r\n).
 *
 * Safety & Protection:
 * - Configurable limits for bulk strings, array sizes, inline commands, and nesting depths.
 * - Throws {@link RespProtocolException} on malformed framing or limit violations.
 * - Handles premature EOF and incomplete TCP requests safely.
 *
 * Encoding Model:
 * Fully binary-safe: RESP bulk strings are parsed into immutable ByteSequence buffers
 * without lossy UTF-8 transcoding.
 */
public class RespParser {

    public static final int DEFAULT_MAX_BULK_STRING_LENGTH = 64 * 1024 * 1024; // 64 MB
    public static final int DEFAULT_MAX_ARRAY_SIZE = 1_048_576; // 1M elements
    public static final int DEFAULT_MAX_INLINE_LENGTH = 65_536; // 64 KB
    public static final int DEFAULT_MAX_NESTING_DEPTH = 32;

    public static class CommandPayload {
        private final List<ByteSequence> rawTokens;
        private List<String> stringTokens;
        private final boolean respProtocol;

        public CommandPayload(List<ByteSequence> rawTokens, boolean respProtocol) {
            this.rawTokens = rawTokens != null ? rawTokens : Collections.emptyList();
            this.respProtocol = respProtocol;
        }

        public static CommandPayload ofStringTokens(List<String> tokens, boolean respProtocol) {
            if (tokens == null || tokens.isEmpty()) {
                return new CommandPayload(Collections.emptyList(), respProtocol);
            }
            List<ByteSequence> raw = new ArrayList<>(tokens.size());
            for (String t : tokens) {
                raw.add(t != null ? ByteSequence.ofUtf8(t) : ByteSequence.EMPTY);
            }
            CommandPayload cp = new CommandPayload(raw, respProtocol);
            cp.stringTokens = Collections.unmodifiableList(new ArrayList<>(tokens));
            return cp;
        }

        public List<ByteSequence> getRawTokens() {
            return rawTokens;
        }

        public List<String> getTokens() {
            if (stringTokens == null) {
                List<String> list = new ArrayList<>(rawTokens.size());
                for (ByteSequence b : rawTokens) {
                    list.add(b != null ? b.toUtf8String() : "");
                }
                this.stringTokens = Collections.unmodifiableList(list);
            }
            return stringTokens;
        }

        public boolean isRespProtocol() {
            return respProtocol;
        }

        public String getCommandName() {
            return rawTokens.isEmpty() ? "" : rawTokens.get(0).toUtf8String().toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Reads the next command from the stream using default limits.
     * Returns null if the stream reaches EOF.
     */
    public static CommandPayload readCommand(InputStream in) throws IOException {
        return readCommand(in, DEFAULT_MAX_BULK_STRING_LENGTH, DEFAULT_MAX_ARRAY_SIZE, DEFAULT_MAX_INLINE_LENGTH);
    }

    /**
     * Reads the next command with custom configurable size limits.
     */
    public static CommandPayload readCommand(InputStream in, int maxBulkLen, int maxArraySize, int maxInlineLen) throws IOException {
        int firstByte = in.read();
        while (firstByte == '\r' || firstByte == '\n' || firstByte == ' ') {
            firstByte = in.read();
        }

        if (firstByte == -1) {
            return null; // EOF
        }

        if (firstByte == '*') {
            // RESP Array command
            long count = readLong(in);
            if (count < -1) {
                throw new RespProtocolException("Invalid array size: " + count);
            }
            if (count > maxArraySize) {
                throw new RespProtocolException("Array element count " + count + " exceeds limit " + maxArraySize);
            }
            if (count <= 0) {
                return new CommandPayload(Collections.emptyList(), true);
            }
            List<ByteSequence> rawTokens = new ArrayList<>((int) count);
            for (int i = 0; i < count; i++) {
                ByteSequence token = readBulkStringTokenBytes(in, maxBulkLen);
                rawTokens.add(token != null ? token : ByteSequence.EMPTY);
            }
            return new CommandPayload(rawTokens, true);
        } else {
            // Inline command fallback
            StringBuilder sb = new StringBuilder();
            sb.append((char) firstByte);
            int b;
            int length = 1;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    length++;
                    if (length > maxInlineLen) {
                        throw new RespProtocolException("Inline command exceeds limit: " + maxInlineLen);
                    }
                    sb.append((char) b);
                }
            }
            String line = sb.toString().trim();
            if (line.isEmpty()) {
                return readCommand(in, maxBulkLen, maxArraySize, maxInlineLen);
            }
            List<String> tokens = CommandRegistry.parseCommandLine(line);
            return CommandPayload.ofStringTokens(tokens, false);
        }
    }

    private static ByteSequence readBulkStringTokenBytes(InputStream in, int maxBulkLen) throws IOException {
        int prefix = in.read();
        while (prefix == '\r' || prefix == '\n' || prefix == ' ') {
            prefix = in.read();
        }
        if (prefix == -1) {
            throw new EOFException("Unexpected EOF while expecting bulk string prefix '$'");
        }
        if (prefix == '$') {
            long len = readLong(in);
            if (len == -1) {
                return null;
            }
            if (len < 0) {
                throw new RespProtocolException("Invalid bulk string length: " + len);
            }
            if (len > maxBulkLen) {
                throw new RespProtocolException("Bulk string length " + len + " exceeds limit " + maxBulkLen);
            }
            byte[] bytes = readExactBytes(in, (int) len);
            readCrLf(in);
            return ByteSequence.of(bytes);
        } else if (prefix == '+' || prefix == ':') {
            return ByteSequence.ofUtf8(readLine(in, DEFAULT_MAX_INLINE_LENGTH));
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append((char) prefix);
            sb.append(readLine(in, DEFAULT_MAX_INLINE_LENGTH));
            return ByteSequence.ofUtf8(sb.toString());
        }
    }

    /**
     * Reads a full generic RedisResponse from the stream for protocol verification.
     */
    public static RedisResponse parseResponse(InputStream in) throws IOException {
        return parseResponse(in, 0, DEFAULT_MAX_NESTING_DEPTH);
    }

    /**
     * Reads a full generic RedisResponse with explicit nesting depth limit.
     */
    public static RedisResponse parseResponse(InputStream in, int depth, int maxDepth) throws IOException {
        if (depth > maxDepth) {
            throw new RespProtocolException("Exceeded maximum array nesting depth: " + maxDepth);
        }

        int prefix = in.read();
        while (prefix == '\r' || prefix == '\n' || prefix == ' ') {
            prefix = in.read();
        }
        if (prefix == -1) {
            return null;
        }

        switch (prefix) {
            case '+': // Simple String
                return RedisResponse.simpleString(readLine(in, DEFAULT_MAX_INLINE_LENGTH));
            case '-': // Error
                return RedisResponse.error(readLine(in, DEFAULT_MAX_INLINE_LENGTH));
            case ':': // Integer
                return RedisResponse.integer(readLong(in));
            case '$': { // Bulk String
                long len = readLong(in);
                if (len == -1) {
                    return RedisResponse.nil();
                }
                if (len < 0) {
                    throw new RespProtocolException("Invalid bulk string length: " + len);
                }
                if (len > DEFAULT_MAX_BULK_STRING_LENGTH) {
                    throw new RespProtocolException("Bulk string length exceeds limit: " + len);
                }
                byte[] bytes = readExactBytes(in, (int) len);
                readCrLf(in);
                return RedisResponse.bulkString(ByteSequence.of(bytes));
            }
            case '*': { // Array
                long count = readLong(in);
                if (count == -1) {
                    return RedisResponse.array(null);
                }
                if (count < -1) {
                    throw new RespProtocolException("Invalid array size: " + count);
                }
                if (count > DEFAULT_MAX_ARRAY_SIZE) {
                    throw new RespProtocolException("Array size exceeds limit: " + count);
                }
                List<RedisResponse> items = new ArrayList<>((int) count);
                for (int i = 0; i < count; i++) {
                    items.add(parseResponse(in, depth + 1, maxDepth));
                }
                return RedisResponse.array(items);
            }
            default:
                throw new RespProtocolException("Unknown RESP type byte: '" + (char) prefix + "' (0x" + Integer.toHexString(prefix) + ")");
        }
    }

    private static String readLine(InputStream in, int maxLen) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        int len = 0;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                len++;
                if (len > maxLen) {
                    throw new RespProtocolException("Line length exceeds limit: " + maxLen);
                }
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    private static long readLong(InputStream in) throws IOException {
        String line = readLine(in, 64);
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
            throw new RespProtocolException("Malformed integer in RESP stream: '" + line + "'", e);
        }
    }

    public static ByteSequence readBulkStringBytes(InputStream in, int maxBulkLen) throws IOException {
        return readBulkStringTokenBytes(in, maxBulkLen);
    }

    public static String readBulkString(InputStream in, int maxBulkLen) throws IOException {
        ByteSequence bs = readBulkStringTokenBytes(in, maxBulkLen);
        return bs != null ? bs.toUtf8String() : null;
    }

    private static byte[] readExactBytes(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(buffer, totalRead, length - totalRead);
            if (read == -1) {
                throw new EOFException("Premature EOF while reading bulk string of length " + length);
            }
            totalRead += read;
        }
        return buffer;
    }

    private static void readCrLf(InputStream in) throws IOException {
        int cr = in.read();
        if (cr == '\r') {
            int lf = in.read();
            if (lf != '\n') {
                throw new RespProtocolException("Expected LF after CR, got byte: " + lf);
            }
        } else if (cr != '\n') {
            throw new RespProtocolException("Expected CRLF terminating bulk string, got byte: " + cr);
        }
    }
}
