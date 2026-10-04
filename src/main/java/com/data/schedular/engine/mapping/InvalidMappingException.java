package com.data.schedular.engine.mapping;

/** The mapping configuration itself is invalid. Fails the whole run. */
public class InvalidMappingException extends RuntimeException {

    public InvalidMappingException(String message) {
        super(message);
    }

    public InvalidMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
