package com.miniredis.protocol;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 7: Comprehensive RESP2 Wire Protocol Serialization & Deserialization Tests")
public class RespProtocolTest {

    private StorageEngine storage;
    private CommandRegistry registry;

    @BeforeEach
    public void setup() {
        storage = new StorageEngine();
        registry = new CommandRegistry();
    }

    @Test
    @DisplayName("Serialize and parse Simple Strings (+OK\\r\\n)")
    public void testSimpleStringSerializationAndParsing() throws IOException {
        RedisResponse response = RedisResponse.simpleString("PONG");
        byte[] bytes = RespSerializer.serializeResponse(response);
        assertEquals("+PONG\r\n", new String(bytes, StandardCharsets.UTF_8));

        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        RedisResponse parsed = RespParser.parseResponse(in);
        assertNotNull(parsed);
        assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, parsed.getType());
        assertEquals("PONG", parsed.getStringValue());
    }

    @Test
    @DisplayName("Serialize and parse Errors (-ERR ...\\r\\n)")
    public void testErrorSerializationAndParsing() throws IOException {
        RedisResponse response = RedisResponse.error("ERR unknown command 'FOO'");
        byte[] bytes = RespSerializer.serializeResponse(response);
        assertEquals("-ERR unknown command 'FOO'\r\n", new String(bytes, StandardCharsets.UTF_8));

        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        RedisResponse parsed = RespParser.parseResponse(in);
        assertNotNull(parsed);
        assertEquals(RedisResponse.ResponseType.ERROR, parsed.getType());
        assertEquals("ERR unknown command 'FOO'", parsed.getStringValue());
    }

    @Test
    @DisplayName("Serialize and parse Integers (:1000\\r\\n, :-42\\r\n, :0\\r\\n)")
    public void testIntegerSerializationAndParsing() throws IOException {
        long[] testValues = {0L, 123456789L, -42L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long val : testValues) {
            RedisResponse response = RedisResponse.integer(val);
            byte[] bytes = RespSerializer.serializeResponse(response);
            assertEquals(":" + val + "\r\n", new String(bytes, StandardCharsets.US_ASCII));

            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            RedisResponse parsed = RespParser.parseResponse(in);
            assertNotNull(parsed);
            assertEquals(RedisResponse.ResponseType.INTEGER, parsed.getType());
            assertEquals(val, parsed.getIntValue());
        }
    }

    @Test
    @DisplayName("Serialize and parse Bulk Strings including UTF-8 multibyte characters")
    public void testBulkStringSerializationAndParsing() throws IOException {
        String unicodeContent = "Hello 世界 🚀 Multibyte Text: \uD83D\uDC4D\uD83C\uDFFC";
        RedisResponse response = RedisResponse.bulkString(unicodeContent);
        byte[] bytes = RespSerializer.serializeResponse(response);

        int byteLen = unicodeContent.getBytes(StandardCharsets.UTF_8).length;
        String expectedHeader = "$" + byteLen + "\r\n";
        assertTrue(new String(bytes, StandardCharsets.UTF_8).startsWith(expectedHeader));

        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        RedisResponse parsed = RespParser.parseResponse(in);
        assertNotNull(parsed);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, parsed.getType());
        assertEquals(unicodeContent, parsed.getStringValue());
    }

    @Test
    @DisplayName("Serialize and parse Empty Bulk String ($0\\r\\n\\r\\n)")
    public void testEmptyBulkString() throws IOException {
        RedisResponse response = RedisResponse.bulkString("");
        byte[] bytes = RespSerializer.serializeResponse(response);
        assertEquals("$0\r\n\r\n", new String(bytes, StandardCharsets.US_ASCII));

        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        RedisResponse parsed = RespParser.parseResponse(in);
        assertNotNull(parsed);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, parsed.getType());
        assertEquals("", parsed.getStringValue());
    }

    @Test
    @DisplayName("Serialize and parse Nil Bulk String ($-1\\r\\n)")
    public void testNilBulkString() throws IOException {
        RedisResponse response = RedisResponse.nil();
        byte[] bytes = RespSerializer.serializeResponse(response);
        assertEquals("$-1\r\n", new String(bytes, StandardCharsets.US_ASCII));

        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        RedisResponse parsed = RespParser.parseResponse(in);
        assertNotNull(parsed);
        assertEquals(RedisResponse.ResponseType.NIL, parsed.getType());
    }

    @Test
    @DisplayName("Serialize and parse Empty and Null RESP Arrays")
    public void testEmptyAndNullArrays() throws IOException {
        // Empty Array
        RedisResponse emptyResp = RedisResponse.array(List.of());
        byte[] emptyBytes = RespSerializer.serializeResponse(emptyResp);
        assertEquals("*0\r\n", new String(emptyBytes, StandardCharsets.US_ASCII));

        RedisResponse parsedEmpty = RespParser.parseResponse(new ByteArrayInputStream(emptyBytes));
        assertNotNull(parsedEmpty);
        assertEquals(RedisResponse.ResponseType.ARRAY, parsedEmpty.getType());
        assertTrue(parsedEmpty.getArrayValue().isEmpty());

        // Null Array
        RedisResponse nullResp = RedisResponse.array(null);
        byte[] nullBytes = RespSerializer.serializeResponse(nullResp);
        assertEquals("*-1\r\n", new String(nullBytes, StandardCharsets.US_ASCII));

        RedisResponse parsedNull = RespParser.parseResponse(new ByteArrayInputStream(nullBytes));
        assertNotNull(parsedNull);
        assertEquals(RedisResponse.ResponseType.ARRAY, parsedNull.getType());
        assertNull(parsedNull.getArrayValue());
    }

    @Test
    @DisplayName("Serialize and parse Nested RESP Arrays (*2\\r\\n*2\\r\\n...)")
    public void testNestedArraySerializationAndParsing() throws IOException {
        RedisResponse innerArray = RedisResponse.stringList(List.of("inner1", "inner2"));
        RedisResponse outerArray = RedisResponse.array(List.of(
                RedisResponse.bulkString("outerHeader"),
                innerArray,
                RedisResponse.integer(100)
        ));

        byte[] bytes = RespSerializer.serializeResponse(outerArray);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        RedisResponse parsed = RespParser.parseResponse(in);

        assertNotNull(parsed);
        assertEquals(RedisResponse.ResponseType.ARRAY, parsed.getType());
        assertEquals(3, parsed.getArrayValue().size());
        assertEquals("outerHeader", parsed.getArrayValue().get(0).getStringValue());

        RedisResponse parsedInner = parsed.getArrayValue().get(1);
        assertEquals(RedisResponse.ResponseType.ARRAY, parsedInner.getType());
        assertEquals(2, parsedInner.getArrayValue().size());
        assertEquals("inner1", parsedInner.getArrayValue().get(0).getStringValue());
        assertEquals("inner2", parsedInner.getArrayValue().get(1).getStringValue());

        assertEquals(100L, parsed.getArrayValue().get(2).getIntValue());
    }

    @Test
    @DisplayName("Prevent excessive array nesting depth exceeding configured limit")
    public void testExcessiveNestingDepthProtection() {
        // Create 10-level nested array and parse with limit of 3
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("*1\r\n");
        }
        sb.append("$2\r\nok\r\n");

        ByteArrayInputStream in = new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(RespProtocolException.class, () -> {
            RespParser.parseResponse(in, 0, 3);
        });
    }

    @Test
    @DisplayName("Configurable limit protection rejects oversized bulk strings and oversized arrays")
    public void testOversizedRequestLimits() {
        // Oversized bulk string
        String oversizedBulk = "*1\r\n$1000\r\n" + "x".repeat(1000) + "\r\n";
        ByteArrayInputStream in1 = new ByteArrayInputStream(oversizedBulk.getBytes(StandardCharsets.UTF_8));
        assertThrows(RespProtocolException.class, () -> {
            RespParser.readCommand(in1, 100, 1000, 1000); // Max bulk is 100
        });

        // Oversized array
        String oversizedArray = "*500\r\n";
        ByteArrayInputStream in2 = new ByteArrayInputStream(oversizedArray.getBytes(StandardCharsets.UTF_8));
        assertThrows(RespProtocolException.class, () -> {
            RespParser.readCommand(in2, 1000, 50, 1000); // Max array is 50
        });
    }

    @Test
    @DisplayName("Malformed inputs throw RespProtocolException or EOFException")
    public void testMalformedInputHandling() {
        // Malformed integer
        String malformedInt = "*abc\r\n";
        assertThrows(RespProtocolException.class, () -> {
            RespParser.readCommand(new ByteArrayInputStream(malformedInt.getBytes(StandardCharsets.UTF_8)));
        });

        // Incomplete bulk string (premature EOF)
        String incompleteBulk = "$10\r\nshort";
        assertThrows(EOFException.class, () -> {
            RespParser.parseResponse(new ByteArrayInputStream(incompleteBulk.getBytes(StandardCharsets.UTF_8)));
        });

        // Unknown type byte
        String unknownByte = "?unknown\r\n";
        assertThrows(RespProtocolException.class, () -> {
            RespParser.parseResponse(new ByteArrayInputStream(unknownByte.getBytes(StandardCharsets.UTF_8)));
        });
    }

    @Test
    @DisplayName("Parse client RESP command payload (*3\\r\\n$3\\r\\nSET\\r\\n...)")
    public void testParseClientRespCommand() throws IOException {
        String respCmd = "*3\r\n$3\r\nSET\r\n$4\r\nuser\r\n$7\r\nKrishna\r\n";
        ByteArrayInputStream in = new ByteArrayInputStream(respCmd.getBytes(StandardCharsets.UTF_8));

        RespParser.CommandPayload payload = RespParser.readCommand(in);
        assertNotNull(payload);
        assertTrue(payload.isRespProtocol());
        assertEquals(List.of("SET", "user", "Krishna"), payload.getTokens());
    }

    @Test
    @DisplayName("Parse inline client command fallback with quoted strings (SET user \"Krishna Murthy\"\\r\\n)")
    public void testParseInlineClientCommand() throws IOException {
        String inlineCmd = "SET user \"Krishna Murthy\"\r\n";
        ByteArrayInputStream in = new ByteArrayInputStream(inlineCmd.getBytes(StandardCharsets.UTF_8));

        RespParser.CommandPayload payload = RespParser.readCommand(in);
        assertNotNull(payload);
        assertFalse(payload.isRespProtocol());
        assertEquals(List.of("SET", "user", "Krishna Murthy"), payload.getTokens());
    }

    @Test
    @DisplayName("Parse pipelined batch of multiple commands in a single stream")
    public void testPipelinedCommandsParsing() throws IOException {
        String stream = "*3\r\n$3\r\nSET\r\n$2\r\nk1\r\n$2\r\nv1\r\n" +
                        "*3\r\n$3\r\nSET\r\n$2\r\nk2\r\n$2\r\nv2\r\n" +
                        "GET k1\r\n" +
                        "*2\r\n$3\r\nGET\r\n$2\r\nk2\r\n";

        ByteArrayInputStream in = new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8));

        RespParser.CommandPayload p1 = RespParser.readCommand(in);
        assertNotNull(p1);
        assertTrue(p1.isRespProtocol());
        assertEquals(List.of("SET", "k1", "v1"), p1.getTokens());

        RespParser.CommandPayload p2 = RespParser.readCommand(in);
        assertNotNull(p2);
        assertTrue(p2.isRespProtocol());
        assertEquals(List.of("SET", "k2", "v2"), p2.getTokens());

        RespParser.CommandPayload p3 = RespParser.readCommand(in);
        assertNotNull(p3);
        assertFalse(p3.isRespProtocol()); // Inline fallback
        assertEquals(List.of("GET", "k1"), p3.getTokens());

        RespParser.CommandPayload p4 = RespParser.readCommand(in);
        assertNotNull(p4);
        assertTrue(p4.isRespProtocol());
        assertEquals(List.of("GET", "k2"), p4.getTokens());

        RespParser.CommandPayload pEof = RespParser.readCommand(in);
        assertNull(pEof);
    }

    @Test
    @DisplayName("Strict wire distinction: Null bulk ($-1\\r\\n) vs Empty bulk ($0\\r\\n\\r\\n), Null array (*-1\\r\\n) vs Empty array (*0\\r\\n)")
    public void testNullVsEmptyBulkStringsAndArraysWireDistinction() throws IOException {
        // 1. Bulk Strings: null vs empty
        byte[] nullBulkBytes = RespSerializer.serializeResponse(RedisResponse.nil());
        byte[] emptyBulkBytes = RespSerializer.serializeResponse(RedisResponse.bulkString(""));

        assertArrayEquals("$-1\r\n".getBytes(StandardCharsets.US_ASCII), nullBulkBytes);
        assertArrayEquals("$0\r\n\r\n".getBytes(StandardCharsets.US_ASCII), emptyBulkBytes);
        assertNotEquals(new String(nullBulkBytes), new String(emptyBulkBytes));

        RedisResponse parsedNullBulk = RespParser.parseResponse(new ByteArrayInputStream(nullBulkBytes));
        RedisResponse parsedEmptyBulk = RespParser.parseResponse(new ByteArrayInputStream(emptyBulkBytes));

        assertEquals(RedisResponse.ResponseType.NIL, parsedNullBulk.getType());
        assertNull(parsedNullBulk.getStringValue());

        assertEquals(RedisResponse.ResponseType.BULK_STRING, parsedEmptyBulk.getType());
        assertEquals("", parsedEmptyBulk.getStringValue());

        // 2. Arrays: null vs empty
        byte[] nullArrayBytes = RespSerializer.serializeResponse(RedisResponse.array(null));
        byte[] emptyArrayBytes = RespSerializer.serializeResponse(RedisResponse.array(List.of()));

        assertArrayEquals("*-1\r\n".getBytes(StandardCharsets.US_ASCII), nullArrayBytes);
        assertArrayEquals("*0\r\n".getBytes(StandardCharsets.US_ASCII), emptyArrayBytes);
        assertNotEquals(new String(nullArrayBytes), new String(emptyArrayBytes));

        RedisResponse parsedNullArray = RespParser.parseResponse(new ByteArrayInputStream(nullArrayBytes));
        RedisResponse parsedEmptyArray = RespParser.parseResponse(new ByteArrayInputStream(emptyArrayBytes));

        assertEquals(RedisResponse.ResponseType.ARRAY, parsedNullArray.getType());
        assertNull(parsedNullArray.getArrayValue());

        assertEquals(RedisResponse.ResponseType.ARRAY, parsedEmptyArray.getType());
        assertNotNull(parsedEmptyArray.getArrayValue());
        assertTrue(parsedEmptyArray.getArrayValue().isEmpty());
    }

    @Test
    @DisplayName("Truncated command payloads in parser throw EOFException cleanly")
    public void testTruncatedCommandPayloadsInParser() {
        // Truncated array: claims 3 elements, only provides 1
        String truncatedArray = "*3\r\n$3\r\nSET\r\n";
        assertThrows(EOFException.class, () -> {
            RespParser.readCommand(new ByteArrayInputStream(truncatedArray.getBytes(StandardCharsets.UTF_8)));
        });

        // Truncated bulk: claims 10 bytes, provides 4
        String truncatedBulk = "*1\r\n$10\r\nfour";
        assertThrows(EOFException.class, () -> {
            RespParser.readCommand(new ByteArrayInputStream(truncatedBulk.getBytes(StandardCharsets.UTF_8)));
        });
    }
}
