package com.grandvista.payment.provider;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 webhook signatures in the widely used {@code t=<unix-seconds>,v1=<hex>} format, where the
 * signed message is {@code "<t>.<raw body>"}. The timestamp bounds replay of captured callbacks.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Provider-Signature";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestampSeconds, byte[] body) {
        return "t=" + timestampSeconds + ",v1=" + hmac(secret, timestampSeconds, body);
    }

    public static boolean verify(String secret, String header, byte[] body, Instant now, Duration tolerance) {
        if (secret == null || secret.isBlank() || header == null) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException ex) {
                    return false;
                }
            } else if ("v1".equals(kv[0])) {
                signature = kv[1];
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            return false;
        }
        byte[] expected = hmac(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
