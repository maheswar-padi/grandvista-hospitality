package com.grandvista.payment.provider.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Expected values were computed independently with {@code openssl dgst -sha256 -hmac <secret>}. */
class RazorpaySignaturesTest {

    @Test
    void checkoutSignatureIsHmacOfOrderPipePayment() {
        String expected = "0327e4f912d998274315e2d2a44c128f3ee09ed6243672358020f92ff05c841c";
        assertThat(RazorpaySignatures.verifyCheckout("order_TEST123456", "pay_TEST654321", expected, "test_secret_12345")).isTrue();
        assertThat(RazorpaySignatures.verifyCheckout("order_TEST123456", "pay_TEST654321", expected.toUpperCase(), "test_secret_12345")).isTrue();
        assertThat(RazorpaySignatures.verifyCheckout("order_OTHER", "pay_TEST654321", expected, "test_secret_12345")).isFalse();
        assertThat(RazorpaySignatures.verifyCheckout("order_TEST123456", "pay_TEST654321", expected, "wrong_secret")).isFalse();
        assertThat(RazorpaySignatures.verifyCheckout("order_TEST123456", "pay_TEST654321", null, "test_secret_12345")).isFalse();
    }

    @Test
    void webhookSignatureIsHmacOfRawBody() {
        byte[] body = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String expected = "ec886750ad72e1be7c53f280c94e06dc6926cc8ddc117279775a221f46e2e0bf";
        assertThat(RazorpaySignatures.verifyWebhook(body, expected, "whsec_test_abc")).isTrue();
        assertThat(RazorpaySignatures.verifyWebhook("{\"event\":\"payment.captured\" }".getBytes(StandardCharsets.UTF_8),
                expected, "whsec_test_abc")).isFalse();
        assertThat(RazorpaySignatures.verifyWebhook(body, "00", "whsec_test_abc")).isFalse();
    }

    @Test
    void onlyTestModeKeysAreAccepted() {
        new RazorpayProperties("rzp_test_abc", "s", "w", null, null, null, null).requireConfigured();
        assertThatThrownBy(() -> new RazorpayProperties("rzp_live_abc", "s", "w", null, null, null, null).requireConfigured())
                .hasMessageContaining("TEST MODE");
        assertThatThrownBy(() -> new RazorpayProperties("", "", "", null, null, null, null).requireConfigured())
                .hasMessageContaining("RAZORPAY_KEY_ID").hasMessageContaining("RAZORPAY_KEY_SECRET")
                .hasMessageContaining("RAZORPAY_WEBHOOK_SECRET");
        assertThat(new RazorpayProperties("rzp_test_abc", "very-secret", "hook-secret", null, null, null, null).toString())
                .doesNotContain("very-secret").doesNotContain("hook-secret");
    }
}
