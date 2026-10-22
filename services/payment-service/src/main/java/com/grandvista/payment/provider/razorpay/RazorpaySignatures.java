package com.grandvista.payment.provider.razorpay;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Razorpay's HMAC-SHA256 signatures (hex encoded), compared in constant time. */
public final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    /** Checkout result: {@code HMAC_SHA256(order_id + "|" + razorpay_payment_id, key_secret)}. */
    public static boolean verifyCheckout(String orderId, String paymentId, String signature, String keySecret) {
        if (orderId == null || paymentId == null || signature == null) {
            return false;
        }
        return constantTimeEquals(hmacHex(keySecret, orderId + "|" + paymentId), signature);
    }

    /** Webhook: {@code X-Razorpay-Signature = HMAC_SHA256(raw request body, webhook_secret)}. */
    public static boolean verifyWebhook(byte[] rawBody, String signature, String webhookSecret) {
        if (signature == null || rawBody == null) {
            return false;
        }
        return constantTimeEquals(hmacHex(webhookSecret, rawBody), signature);
    }

    public static String hmacHex(String secret, String message) {
        return hmacHex(secret, message.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacHex(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
