package com.miniredis.storage;

/**
 * Thrown when an operation is attempted against a key holding the wrong data type.
 */
public class WrongTypeException extends RuntimeException {
    public WrongTypeException() {
        super("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    public WrongTypeException(String message) {
        super(message);
    }
}