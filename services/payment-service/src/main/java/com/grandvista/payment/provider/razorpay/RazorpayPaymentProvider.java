package com.grandvista.payment.provider.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.grandvista.payment.provider.PaymentProvider;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Razorpay (TEST MODE) implementation of the payment gateway.
 * <ol>
 *   <li>{@link #checkout}: creates a Razorpay order for the exact amount (receipt = our payment id) once per payment.</li>
 *   <li>The browser opens Razorpay Checkout for that order and returns payment id, order id and signature.</li>
 *   <li>{@link #charge}: verifies the signature, then asks Razorpay for the payment's real state (the browser is
 *       never trusted), checks order/amount/currency and captures an authorised payment.</li>
 *   <li>Signed webhooks ({@code payment.captured}, {@code order.paid}, {@code payment.failed}) and
 *       {@link #retrieve(String, String)} (reconciliation) cover a closed browser or a lost response.</li>
 * </ol>
 * Network errors and timeouts raise {@link ProviderUnavailableException}: the payment stays PENDING and is
 * reconciled, so a retry can never capture twice (capture of a captured payment is refused by Razorpay and the
 * existing capture is reported instead).
 */
public class RazorpayPaymentProvider implements PaymentProvider {

    public static final String NAME = "RAZORPAY";
    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);

    private final RazorpayClient client;
    private final RazorpayProperties properties;

    public RazorpayPaymentProvider(RazorpayClient client, RazorpayProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean usesCheckout() {
        return true;
    }

    static long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    @Override
    public CheckoutSession checkout(CheckoutRequest request) throws ProviderUnavailableException {
        long minor = toMinorUnits(request.amount());
        String orderId = request.existingOrderId();
        if (orderId == null) {
            JsonNode order = client.createOrder(minor, request.currency(), request.paymentId().toString(),
                    Map.of("paymentId", request.paymentId().toString(), "booking", request.bookingReference()));
            orderId = order.path("id").asText(null);
            if (orderId == null) {
                throw new ProviderUnavailableException("Razorpay returned no order id", null);
            }
            log.info("Created Razorpay order {} for payment {}", orderId, request.paymentId());
        }
        return new CheckoutSession(NAME, properties.keyId(), orderId, request.amount(), request.currency(), minor);
    }

    @Override
    public ChargeResult charge(ChargeRequest request) throws ProviderUnavailableException {
        CheckoutResult checkout = request.checkout();
        if (checkout == null || request.providerOrderId() == null) {
            return new ChargeResult(Outcome.FAILED, null, "checkout_required",
                    "Razorpay payments must be completed in Razorpay Checkout.");
        }
        if (!request.providerOrderId().equals(checkout.providerOrderId())
                || !RazorpaySignatures.verifyCheckout(checkout.providerOrderId(), checkout.providerPaymentId(),
                checkout.signature(), properties.keySecret())) {
            // Do not decide on an unverifiable browser message; the order's real state is reconciled from the API.
            log.warn("Razorpay checkout signature/order mismatch for order {}; leaving the payment for reconciliation",
                    request.providerOrderId());
            return new ChargeResult(Outcome.PENDING, null, null, null);
        }
        JsonNode payment = client.fetchPayment(checkout.providerPaymentId());
        return evaluate(payment, request.providerOrderId(), request.amount(), request.currency());
    }

    /** Maps a Razorpay payment entity to an outcome, capturing it if it is only authorised. */
    ChargeResult evaluate(JsonNode payment, String expectedOrderId, BigDecimal amount, String currency)
            throws ProviderUnavailableException {
        String id = payment.path("id").asText(null);
        long minor = toMinorUnits(amount);
        if (!expectedOrderId.equals(payment.path("order_id").asText(null))
                || payment.path("amount").asLong(-1) != minor
                || !currency.equalsIgnoreCase(payment.path("currency").asText(""))) {
            log.error("Razorpay payment {} does not match order {} / {} {}", id, expectedOrderId, amount, currency);
            return new ChargeResult(Outcome.PENDING, null, null, null);
        }
        String status = payment.path("status").asText("");
        switch (status) {
            case "captured", "refunded" -> {
                return new ChargeResult(Outcome.SUCCEEDED, id, null, null);
            }
            case "authorized" -> {
                try {
                    JsonNode captured = client.capture(id, minor, currency);
                    return "captured".equals(captured.path("status").asText())
                            ? new ChargeResult(Outcome.SUCCEEDED, id, null, null)
                            : new ChargeResult(Outcome.PENDING, id, null, null);
                } catch (RazorpayClient.RazorpayApiException ex) {
                    // e.g. "already captured" by auto-capture or a concurrent request: read the real state
                    JsonNode again = client.fetchPayment(id);
                    return "captured".equals(again.path("status").asText())
                            ? new ChargeResult(Outcome.SUCCEEDED, id, null, null)
                            : new ChargeResult(Outcome.PENDING, id, null, null);
                }
            }
            case "failed" -> {
                return new ChargeResult(Outcome.FAILED, id, payment.path("error_code").asText("payment_failed"),
                        payment.path("error_description").asText("The payment failed at Razorpay."));
            }
            default -> {
                return new ChargeResult(Outcome.PENDING, id, null, null);
            }
        }
    }

    @Override
    public Optional<ChargeResult> retrieve(String idempotencyKey) {
        return Optional.empty(); // Razorpay payments are looked up by order: see retrieve(key, orderId)
    }

    @Override
    public Optional<ChargeResult> retrieve(String idempotencyKey, String providerOrderId) throws ProviderUnavailableException {
        if (providerOrderId == null) {
            return Optional.empty();
        }
        JsonNode items = client.orderPayments(providerOrderId).path("items");
        ChargeResult failed = null;
        for (JsonNode payment : items) {
            String status = payment.path("status").asText("");
            if (status.equals("captured") || status.equals("authorized") || status.equals("refunded")) {
                BigDecimal amount = BigDecimal.valueOf(payment.path("amount").asLong(), 2);
                return Optional.of(evaluate(payment, providerOrderId, amount, payment.path("currency").asText("INR")));
            }
            if (status.equals("failed")) {
                failed = new ChargeResult(Outcome.FAILED, payment.path("id").asText(null),
                        payment.path("error_code").asText("payment_failed"),
                        payment.path("error_description").asText("The payment failed at Razorpay."));
            }
        }
        return Optional.ofNullable(failed);
    }

    @Override
    public RefundResult refund(RefundRequest request) throws ProviderUnavailableException {
        try {
            JsonNode refund = client.refund(request.chargeReference(), toMinorUnits(request.amount()),
                    request.idempotencyKey().length() > 40 ? request.idempotencyKey().substring(0, 40) : request.idempotencyKey());
            String status = refund.path("status").asText("");
            boolean accepted = status.equals("processed") || status.equals("pending") || status.equals("created");
            return new RefundResult(accepted, refund.path("id").asText(null),
                    accepted ? null : "Razorpay refund status " + status);
        } catch (RazorpayClient.RazorpayApiException ex) {
            return new RefundResult(false, null, ex.getMessage());
        }
    }
}
