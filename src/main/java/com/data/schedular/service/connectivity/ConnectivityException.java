package com.data.schedular.service.connectivity;

/** The remote database could not be reached or queried. */
public class ConnectivityException extends RuntimeException {

    public ConnectivityException(String message, Throwable cause) {
        super(message, cause);
    }
}
