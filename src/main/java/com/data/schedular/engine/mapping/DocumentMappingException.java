package com.data.schedular.engine.mapping;

/** One document cannot be converted to rows. The document goes to the dead-letter table; the run continues. */
public class DocumentMappingException extends RuntimeException {

    public DocumentMappingException(String message) {
        super(message);
    }
}
