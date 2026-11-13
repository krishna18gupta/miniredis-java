package com.miniredis.protocol;

import java.io.IOException;

/**
 * Exception thrown when incoming stream violates the RESP2 wire protocol specification,
 * exceeds configured request-size limits, or contains malformed framing.
 */
public class RespProtocolException extends IOException {

    public RespProtocolException(String message) {
        super(message);
    }

    public RespProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
