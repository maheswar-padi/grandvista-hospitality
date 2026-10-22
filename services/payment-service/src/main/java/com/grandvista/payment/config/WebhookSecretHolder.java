package com.grandvista.payment.config;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Resolves the webhook secret; a random per-process secret is generated only when none is configured. */
@Component
public class WebhookSecretHolder {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretHolder.class);
    private final String secret;

    public WebhookSecretHolder(PaymentProperties properties, org.springframework.core.env.Environment env) {
        String configured = properties.webhookSecret();
        if (configured != null && configured.length() >= 32) {
            secret = configured;
        } else if (env.matchesProfiles("prod")) {
            throw new IllegalStateException("PAYMENT_WEBHOOK_SECRET (>= 32 chars) is required in the prod profile");
        } else {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = HexFormat.of().formatHex(random);
            log.warn("PAYMENT_WEBHOOK_SECRET not set: generated a random per-process webhook secret (development only)");
        }
    }

    public String secret() {
        return secret;
    }
}
