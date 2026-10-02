package com.data.schedular.service.connectivity;

/** Outcome of a connectivity check. A failed check is a normal result, not an error. */
public record ConnectionTestResult(boolean success, String message, String product, String version,
                                   long latencyMs) {

    public static ConnectionTestResult ok(String product, String version, long latencyMs) {
        return new ConnectionTestResult(true, "Connected", product, version, latencyMs);
    }

    public static ConnectionTestResult failed(String message, long latencyMs) {
        return new ConnectionTestResult(false, message, null, null, latencyMs);
    }
}
