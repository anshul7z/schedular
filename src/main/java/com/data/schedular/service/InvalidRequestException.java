package com.data.schedular.service;

/** A request that is well-formed but semantically invalid (e.g. inconsistent connection settings). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
