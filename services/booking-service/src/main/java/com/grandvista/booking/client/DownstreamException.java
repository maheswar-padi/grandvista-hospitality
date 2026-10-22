package com.grandvista.booking.client;

import com.grandvista.platform.client.RemoteProblem;

/** A downstream service answered with a definite business error (4xx); the operation did not happen. */
public class DownstreamException extends RuntimeException {

    private final String service;
    private final RemoteProblem problem;

    public DownstreamException(String service, RemoteProblem problem) {
        super(service + " rejected the request: " + problem.code());
        this.service = service;
        this.problem = problem;
    }

    public String getService() {
        return service;
    }

    public RemoteProblem getProblem() {
        return problem;
    }

    public String code() {
        return problem.code();
    }
}
