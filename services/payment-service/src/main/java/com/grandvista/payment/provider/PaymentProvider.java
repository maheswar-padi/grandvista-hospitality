package com.grandvista.payment.provider;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Boundary to an external payment provider. Implementations must be idempotent per
 * {@code idempotencyKey} (as real providers such as Stripe or Razorpay are) so that a retried call
 * never charges twice. Only provider-issued payment-method tokens cross this boundary — never raw card data.
 */
public interface PaymentProvider {

    String name();

    ChargeResult charge(ChargeRequest request) throws ProviderUnavailableException;

    /** Looks up the provider's authoritative state for a charge (used to reconcile timeouts). */
    Optional<ChargeResult> retrieve(String idempotencyKey) throws ProviderUnavailableException;

    RefundResult refund(RefundRequest request) throws ProviderUnavailableException;

    enum Outcome { SUCCEEDED, FAILED, PENDING }

    record ChargeRequest(String idempotencyKey, BigDecimal amount, String currency, String paymentMethodToken,
                         String description) {
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
