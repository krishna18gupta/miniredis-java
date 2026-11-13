package com.miniredis.aof;

/**
 * Thrown when an AOF persistence operation fails, such as disk-full, fsync failure,
 * or write queue overflow under strict durability policies.
 */
public class AofException extends RuntimeException {

    public AofException(String message) {
        super(message);
    }

    public AofException(String message, Throwable cause) {
        super(message, cause);
    }
}
