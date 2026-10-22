package com.grandvista.platform.client;

/**
 * A downstream service could not be reached or answered with a transient/unexpected failure. The outcome
 * of the remote operation is unknown, so callers must only retry idempotent operations.
 */
public class ServiceCallException extends RuntimeException {

    private final String service;

    public ServiceCallException(String service, String message, Throwable cause) {
        super(service + ": " + message, cause);
        this.service = service;
    }

    public String getService() {
        return service;
    }
}
