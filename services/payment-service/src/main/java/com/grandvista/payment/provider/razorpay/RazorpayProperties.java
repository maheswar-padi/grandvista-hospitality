package com.grandvista.payment.provider.razorpay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay credentials and endpoints (test mode). All three secrets come from the environment only
 * ({@code RAZORPAY_KEY_ID}, {@code RAZORPAY_KEY_SECRET}, {@code RAZORPAY_WEBHOOK_SECRET}); they are never logged.
 *
 * @param keyId          publishable key id ({@code rzp_test_…}); sent to the browser
 * @param keySecret      API secret; server only
 * @param webhookSecret  secret configured on the Razorpay webhook; server only
 * @param baseUrl        API base URL (overridable for tests)
 * @param merchantName   name shown in Razorpay Checkout
 */
@ConfigurationProperties("gv.payment.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret, String baseUrl,
                                 Duration connectTimeout, Duration readTimeout, String merchantName) {

    public RazorpayProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.razorpay.com" : baseUrl.replaceAll("/+$", "");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        merchantName = merchantName == null ? "GrandVista Hospitality" : merchantName;
    }

    /** @throws IllegalStateException naming (not revealing) the missing settings */
    public void requireConfigured() {
        StringBuilder missing = new StringBuilder();
        if (keyId == null || keyId.isBlank()) {
            missing.append(" RAZORPAY_KEY_ID");
        }
        if (keySecret == null || keySecret.isBlank()) {
            missing.append(" RAZORPAY_KEY_SECRET");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            missing.append(" RAZORPAY_WEBHOOK_SECRET");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("GV_PAYMENT_PROVIDER=razorpay but these settings are empty:" + missing
                    + ". Add your Razorpay TEST MODE keys to .env (see docs/deployment/DEPLOYMENT.md#razorpay-test-mode).");
        }
        if (!keyId.startsWith("rzp_test_")) {
            throw new IllegalStateException("RAZORPAY_KEY_ID must be a TEST MODE key (rzp_test_…); live keys are refused.");
        }
    }

    @Override
    public String toString() {
        return "RazorpayProperties[keyId=" + keyId + ", keySecret=***, webhookSecret=***, baseUrl=" + baseUrl + "]";
    }
}
