package com.data.schedular.engine.target;

/** The target tables do not match what the mapping needs and cannot (or may not) be fixed automatically. */
public class TargetSchemaException extends RuntimeException {

    public TargetSchemaException(String message) {
        super(message);
    }
}
