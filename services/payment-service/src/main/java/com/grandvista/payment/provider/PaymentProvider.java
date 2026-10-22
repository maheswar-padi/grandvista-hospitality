package com.grandvista.payment.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment gateway boundary ("PaymentGateway"): one implementation per provider, selected with
 * {@code gv.payment.provider} — {@code simulated} (default, no money moves) or {@code razorpay} (Razorpay test mode).
 * Implementations must never charge twice for the same {@code idempotencyKey} (the payment id). Only
 * provider-issued tokens or checkout results cross this boundary — never raw card data.
 */
public interface PaymentProvider {

    String name();

    /** True when payments are completed in the provider's checkout (a checkout result is required to confirm). */
    default boolean usesCheckout() {
        return false;
    }

    /**
     * Prepares the provider-side object the browser pays against (Razorpay: an order). Providers without such a
     * step return a session without an order id. Must be safe to call again: it receives the order id created
     * earlier, if any, and should return that order instead of creating a new one.
     */
    default CheckoutSession checkout(CheckoutRequest request) throws ProviderUnavailableException {
        return new CheckoutSession(name(), null, null, request.amount(), request.currency(), null);
    }

    /**
     * Authorises/captures the payment. {@code request.checkout()} carries the browser's checkout result for
     * providers that use one; {@code paymentMethodToken} is used by token-based providers.
     */
    ChargeResult charge(ChargeRequest request) throws ProviderUnavailableException;

    /** Looks up the provider's authoritative state for a charge (used to reconcile timeouts). */
    Optional<ChargeResult> retrieve(String idempotencyKey) throws ProviderUnavailableException;

    /** As {@link #retrieve(String)}, with the provider order id when the provider uses orders. */
    default Optional<ChargeResult> retrieve(String idempotencyKey, String providerOrderId) throws ProviderUnavailableException {
        return retrieve(idempotencyKey);
    }

    RefundResult refund(RefundRequest request) throws ProviderUnavailableException;

    enum Outcome { SUCCEEDED, FAILED, PENDING }

    record CheckoutRequest(UUID paymentId, BigDecimal amount, String currency, String bookingReference,
                           String existingOrderId, Instant expiresAt) {
    }

    /**
     * What the browser needs to open the provider's checkout. {@code publicKey} is the provider's publishable key
     * (Razorpay key id) — never a secret.
     */
    record CheckoutSession(String provider, String publicKey, String orderId, BigDecimal amount, String currency,
                           Long amountMinorUnits) {
    }

    /** The browser's result from a provider checkout (Razorpay: payment id, order id and signature). */
    record CheckoutResult(String providerPaymentId, String providerOrderId, String signature) {
    }

    record ChargeRequest(String idempotencyKey, BigDecimal amount, String currency, String paymentMethodToken,
                         String description, String providerOrderId, CheckoutResult checkout) {
        public ChargeRequest(String idempotencyKey, BigDecimal amount, String currency, String paymentMethodToken,
                String description) {
            this(idempotencyKey, amount, currency, paymentMethodToken, description, null, null);
        }
    }

    record ChargeResult(Outcome outcome, String providerReference, String failureCode, String failureMessage) {
    }

    record RefundRequest(String idempotencyKey, String chargeReference, BigDecimal amount) {
    }

    record RefundResult(boolean succeeded, String providerReference, String failureMessage) {
    }

    /** The provider could not be reached or did not answer in time: the outcome is UNKNOWN. */
    class ProviderUnavailableException extends Exception {
        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
