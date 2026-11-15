package com.miniredis.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 18 Stage A1: ByteSequence Unit & Safety Tests")
public class ByteSequenceTest {

    @Test
    @DisplayName("Empty Sequence & Null Safety")
    public void testEmptyAndNullSafety() {
        assertSame(ByteSequence.EMPTY, ByteSequence.of(null));
        assertSame(ByteSequence.EMPTY, ByteSequence.of(new byte[0]));
        assertSame(ByteSequence.EMPTY, ByteSequence.ofUtf8(null));
        assertSame(ByteSequence.EMPTY, ByteSequence.ofUtf8(""));

        assertTrue(ByteSequence.EMPTY.isEmpty());
        assertEquals(0, ByteSequence.EMPTY.length());
        assertArrayEquals(new byte[0], ByteSequence.EMPTY.toByteArray());
        assertEquals("", ByteSequence.EMPTY.toUtf8String());
        assertEquals("\"\"", ByteSequence.EMPTY.toDisplayString());
    }

    @Test
    @DisplayName("Defensive Copying & Immutability")
    public void testImmutabilityAndDefensiveCopying() {
        byte[] input = new byte[]{1, 2, 3, 4};
        ByteSequence seq = ByteSequence.of(input);

        // Mutating input array after creation must not mutate ByteSequence
        input[0] = 99;
        assertEquals(1, seq.byteAt(0));

        // Mutating array returned by toByteArray() must not mutate ByteSequence
        byte[] retrieved = seq.toByteArray();
        retrieved[0] = 88;
        assertEquals(1, seq.byteAt(0));
    }

    @Test
    @DisplayName("Content-Based Equality and Hash Code Consistency")
    public void testEqualsAndHashCode() {
        byte[] b1 = new byte[]{0, (byte) 255, 10, 13, 65};
        byte[] b2 = new byte[]{0, (byte) 255, 10, 13, 65};
        byte[] b3 = new byte[]{0, (byte) 255, 10, 13, 66};

        ByteSequence seq1 = ByteSequence.of(b1);
        ByteSequence seq2 = ByteSequence.of(b2);
        ByteSequence seq3 = ByteSequence.of(b3);

        assertEquals(seq1, seq2);
        assertEquals(seq1.hashCode(), seq2.hashCode());
        assertNotEquals(seq1, seq3);

        // Verify inside standard Java Set & Map
        Set<ByteSequence> set = new HashSet<>();
        set.add(seq1);
        assertTrue(set.contains(seq2));
        assertFalse(set.contains(seq3));

        Map<ByteSequence, String> map = new HashMap<>();
        map.put(seq1, "value1");
        assertEquals("value1", map.get(seq2));
        assertNull(map.get(seq3));
    }

    @Test
    @DisplayName("Lexicographical Unsigned Comparison")
    public void testComparable() {
        ByteSequence a = ByteSequence.of(new byte[]{1, 2});
        ByteSequence b = ByteSequence.of(new byte[]{1, 3});
        ByteSequence c = ByteSequence.of(new byte[]{1, 2, 0});
        // 0xFF (255) must be greater than 0x7F (127) in unsigned comparison
        ByteSequence d = ByteSequence.of(new byte[]{(byte) 0x7F});
        ByteSequence e = ByteSequence.of(new byte[]{(byte) 0xFF});

        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
        assertTrue(a.compareTo(c) < 0);
        assertEquals(0, a.compareTo(ByteSequence.of(new byte[]{1, 2})));
        assertTrue(d.compareTo(e) < 0);
    }

    @Test
    @DisplayName("Zero-Copy Stream Writing")
    public void testStreamWriting() throws IOException {
        byte[] data = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        ByteSequence seq = ByteSequence.of(data);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        seq.writeTo(baos);

        assertArrayEquals(data, baos.toByteArray());
    }

    @Test
    @DisplayName("Slicing & Concatenation")
    public void testSliceAndConcat() {
        ByteSequence seq = ByteSequence.of(new byte[]{10, 20, 30, 40, 50});

        ByteSequence sub = seq.slice(1, 3);
        assertEquals(3, sub.length());
        assertArrayEquals(new byte[]{20, 30, 40}, sub.toByteArray());

        ByteSequence combined = sub.concat(ByteSequence.of(new byte[]{60, 70}));
        assertEquals(5, combined.length());
        assertArrayEquals(new byte[]{20, 30, 40, 60, 70}, combined.toByteArray());

        // Bounds checks
        assertThrows(IndexOutOfBoundsException.class, () -> seq.slice(-1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> seq.slice(2, 10));
    }

    @Test
    @DisplayName("Strict UTF-8 Detection")
    public void testStrictUtf8() {
        // Valid UTF-8 ASCII
        assertTrue(ByteSequence.ofUtf8("Hello World").isStrictUtf8());

        // Valid UTF-8 Multi-byte
        assertTrue(ByteSequence.ofUtf8("MiniRedis 🚀 数据库").isStrictUtf8());

        // Invalid UTF-8: 0xFF is never valid in UTF-8
        ByteSequence invalid1 = ByteSequence.of(new byte[]{(byte) 0xFF, (byte) 0xFE});
        assertFalse(invalid1.isStrictUtf8());

        // Incomplete 2-byte sequence (0xC2 without continuation byte)
        ByteSequence invalid2 = ByteSequence.of(new byte[]{(byte) 0xC2, 0x20});
        assertFalse(invalid2.isStrictUtf8());

        // Binary null bytes embedded in text is technically valid UTF-8 code point U+0000
        ByteSequence withNull = ByteSequence.of(new byte[]{'A', 0, 'B'});
        assertTrue(withNull.isStrictUtf8());
    }

    @Test
    @DisplayName("Display Escaping & Hex/Base64 Encodings")
    public void testDisplayAndEncodings() {
        byte[] binary = new byte[]{'H', 'i', 0, '\r', '\n', (byte) 0xFF};
        ByteSequence seq = ByteSequence.of(binary);

        assertEquals("\"Hi\\x00\\r\\n\\xff\"", seq.toDisplayString());
        assertEquals("4869000d0aff", seq.toHexString());
        assertEquals(Base64.getEncoder().encodeToString(binary), seq.toBase64());
    }
}
