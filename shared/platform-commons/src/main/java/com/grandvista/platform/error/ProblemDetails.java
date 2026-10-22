package com.grandvista.platform.error;

import com.grandvista.platform.web.CorrelationId;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem documents with the platform's standard extension members. */
public final class ProblemDetails {

    public static final String TYPE_PREFIX = "urn:grandvista:problem:";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        CorrelationId.current().ifPresent(id -> problem.setProperty("correlationId", id));
        return problem;
    }
}
