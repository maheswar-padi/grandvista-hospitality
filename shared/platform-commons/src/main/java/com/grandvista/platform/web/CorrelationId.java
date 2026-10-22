package com.grandvista.platform.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Correlation-ID conventions shared by every service.
 *
 * <p>The gateway assigns an ID to each inbound request; services copy it into the logging MDC and
 * forward it on every outbound call so that one customer action can be traced across services.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Accept only short, header-safe identifiers so that client input cannot pollute logs. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private CorrelationId() {
    }

    public static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    public static String currentOrNew() {
        return current().orElseGet(CorrelationId::newId);
    }
}
