package com.grandvista.platform.client;

import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClientResponseException;

/** A structured 4xx error returned by another service (its problem document's {@code code} member). */
public record RemoteProblem(int status, String code, String detail, Map<String, Object> properties) {

    public static RemoteProblem from(RestClientResponseException ex) {
        ProblemDetail problem = null;
        try {
            problem = ex.getResponseBodyAs(ProblemDetail.class);
        } catch (RuntimeException ignored) {
            // body was not a problem document
        }
        if (problem == null) {
            return new RemoteProblem(ex.getStatusCode().value(), "HTTP_" + ex.getStatusCode().value(),
                    ex.getStatusText(), Map.of());
        }
        Map<String, Object> props = problem.getProperties() == null ? Map.of() : problem.getProperties();
        Object code = props.get("code");
        return new RemoteProblem(problem.getStatus(), code == null ? "HTTP_" + problem.getStatus() : code.toString(),
                problem.getDetail(), props);
    }
}
