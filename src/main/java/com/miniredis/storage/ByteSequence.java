package com.miniredis.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Immutable, binary-safe byte sequence representing Redis keys, string values,
 * collection members, and wire protocol tokens.
 *
 * Design Guarantees:
 * - Truly immutable with defensive copying at all public boundary entry/exit points.
 * - Stable, precomputed content-based hashCode for O(1) hash map indexing.
 * - Content-based equals() utilizing vectorized byte comparison.
 * - Unsigned lexicographical ordering via Comparable&lt;ByteSequence&gt;.
 * - Zero-copy direct streaming to OutputStream for maximum RESP serialization throughput.
 * - Safe string conversion adapters (UTF-8 lossless checking, printable escaping, Base64).
 */
public final class ByteSequence implements Comparable<ByteSequence>, Serializable {

    private static final long serialVersionUID = 1L;

    public static final ByteSequence EMPTY = new ByteSequence(new byte[0], false);

    private final byte[] bytes;
    private final int hash;

    /**
     * Internal constructor with optional defensive copying.
     */
    private ByteSequence(byte[] data, boolean clone) {
        if (clone) {
            this.bytes = data.clone();
        } else {
            this.bytes = data;
        }
        this.hash = Arrays.hashCode(this.bytes);
    }

    /**
     * Creates an immutable ByteSequence from a byte array with defensive copying.
     */
    public static ByteSequence of(byte[] data) {
        if (data == null || data.length == 0) {
            return EMPTY;
        }
        return new ByteSequence(data, true);
    }

    /**
     * Creates an immutable ByteSequence from a sub-slice of a byte array.
     */
    public static ByteSequence of(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data cannot be null");
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                    String.format("offset=%d, length=%d, arrayLength=%d", offset, length, data.length));
        }
        if (length == 0) {
            return EMPTY;
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        return new ByteSequence(copy, false);
    }

    /**
     * Creates an immutable ByteSequence from a Java String encoded as UTF-8.
     */
    public static ByteSequence ofUtf8(String str) {
        if (str == null || str.isEmpty()) {
            return EMPTY;
        }
        return new ByteSequence(str.getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * Creates an immutable ByteSequence from an ASCII/numeric string.
     */
    public static ByteSequence ofAscii(String str) {
        if (str == null || str.isEmpty()) {
            return EMPTY;
        }
        return new ByteSequence(str.getBytes(StandardCharsets.US_ASCII), false);
    }

    /**
     * Returns the number of bytes in this sequence.
     */
    public int length() {
        return bytes.length;
    }

    /**
     * Returns true if this sequence contains zero bytes.
     */
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    /**
     * Returns the byte at the specified index.
     */
    public byte byteAt(int index) {
        return bytes[index];
    }

    /**
     * Returns a defensive copy of the underlying bytes.
     */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /**
     * Streams the raw bytes directly to an OutputStream without intermediate allocations.
     */
    public void writeTo(OutputStream out) throws IOException {
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Returns a sub-sequence slice of this byte sequence.
     */
    public ByteSequence slice(int offset, int length) {
        if (offset == 0 && length == bytes.length) {
            return this;
        }
        return of(bytes, offset, length);
    }

    /**
     * Concatenates another ByteSequence to the end of this sequence.
     */
    public ByteSequence concat(ByteSequence other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (this.isEmpty()) {
            return other;
        }
        byte[] combined = new byte[this.bytes.length + other.bytes.length];
        System.arraycopy(this.bytes, 0, combined, 0, this.bytes.length);
        System.arraycopy(other.bytes, 0, combined, this.bytes.length, other.bytes.length);
        return new ByteSequence(combined, false);
    }

    /**
     * Decodes the byte sequence as a UTF-8 Java String.
     * Note: Non-UTF8 byte sequences may result in standard Unicode replacement characters.
     */
    public String toUtf8String() {
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Validates whether this byte sequence represents strict, well-formed UTF-8 without errors.
     */
    public boolean isStrictUtf8() {
        if (bytes.length == 0) {
            return true;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /**
     * Formats bytes safely for debugging and console display.
     * Printable ASCII characters are displayed as-is; non-printable bytes are escaped (e.g. \x00, \r, \n).
     */
    public String toDisplayString() {
        if (bytes.length == 0) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        sb.append('"');
        for (byte b : bytes) {
            int ub = b & 0xFF;
            if (ub == '\\') {
                sb.append("\\\\");
            } else if (ub == '"') {
                sb.append("\\\"");
            } else if (ub == '\n') {
                sb.append("\\n");
            } else if (ub == '\r') {
                sb.append("\\r");
            } else if (ub == '\t') {
                sb.append("\\t");
            } else if (ub == 0) {
                sb.append("\\x00");
            } else if (ub >= 32 && ub < 127) {
                sb.append((char) ub);
            } else {
                sb.append(String.format("\\x%02x", ub));
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Encodes the byte sequence into a Base64 string for safe JSON transmission of binary payloads.
     */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Encodes the byte sequence into an uppercase hex string.
     */
    public String toHexString() {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ByteSequence other)) return false;
        // Fast pre-filter using precomputed hash and length
        if (this.hash != other.hash || this.bytes.length != other.bytes.length) {
            return false;
        }
        return Arrays.equals(this.bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(ByteSequence o) {
        if (this == o) return 0;
        return Arrays.compareUnsigned(this.bytes, o.bytes);
    }

    @Override
    public String toString() {
        return toDisplayString();
    }
}
