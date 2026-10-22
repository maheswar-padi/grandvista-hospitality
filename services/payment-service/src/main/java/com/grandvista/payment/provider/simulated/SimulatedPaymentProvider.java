package com.grandvista.payment.provider.simulated;

import com.grandvista.payment.config.PaymentProperties;
import com.grandvista.payment.provider.PaymentProvider;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SIMULATED PAYMENT PROVIDER — FOR DEVELOPMENT, DEMONSTRATION AND AUTOMATED TESTS ONLY.
 * No real money is moved and no card network is contacted. Behaviour is selected with provider-style
 * test tokens (like the test cards real providers publish), so no card data is ever handled:
 *
 * <ul>
 *   <li>{@code sim_card_success} / {@code sim_card_success_mastercard} – approved immediately</li>
 *   <li>{@code sim_card_declined} – declined (card_declined)</li>
 *   <li>{@code sim_card_insufficient_funds} – declined (insufficient_funds)</li>
 *   <li>{@code sim_card_async_success} – pending, approved later via a signed webhook</li>
 *   <li>{@code sim_card_async_decline} – pending, declined later via a signed webhook</li>
 *   <li>{@code sim_card_timeout_success} – approved by the provider, but the response is "lost"
 *       (the call times out) so the reconciliation job must discover the outcome</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "gv.payment.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPaymentProvider implements PaymentProvider {

    public static final String NAME = "SIMULATED";
    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    private record Behaviour(Outcome immediate, Outcome eventual, String failureCode, boolean loseResponse) {
    }

    static final Map<String, Behaviour> TOKENS = Map.of(
            "sim_card_success", new Behaviour(Outcome.SUCCEEDED, Outcome.SUCCEEDED, null, false),
            "sim_card_success_mastercard", new Behaviour(Outcome.SUCCEEDED, Outcome.SUCCEEDED, null, false),
            "sim_card_declined", new Behaviour(Outcome.FAILED, Outcome.FAILED, "card_declined", false),
            "sim_card_insufficient_funds", new Behaviour(Outcome.FAILED, Outcome.FAILED, "insufficient_funds", false),
            "sim_card_async_success", new Behaviour(Outcome.PENDING, Outcome.SUCCEEDED, null, false),
            "sim_card_async_decline", new Behaviour(Outcome.PENDING, Outcome.FAILED, "card_declined", false),
            "sim_card_timeout_success", new Behaviour(Outcome.SUCCEEDED, Outcome.SUCCEEDED, null, true));

    private final SimulatedChargeRepository charges;
    private final JdbcTemplate jdbc;
    private final PaymentProperties properties;
    private final Clock clock;

    public SimulatedPaymentProvider(SimulatedChargeRepository charges, JdbcTemplate jdbc, PaymentProperties properties,
            Clock clock) {
        this.charges = charges;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Runs in its own transaction: the provider's records are independent of the caller's. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChargeResult charge(ChargeRequest request) throws ProviderUnavailableException {
        SimulatedCharge charge = charges.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (charge != null) {
            // Idempotent replay: same key returns the original outcome, never a second charge.
            if (charge.getAmount().compareTo(request.amount()) != 0) {
                return new ChargeResult(Outcome.FAILED, charge.getReference(), "idempotency_mismatch",
                        "A different amount was already charged with this key.");
            }
            return toResult(charge);
        }
        Behaviour behaviour = TOKENS.get(request.paymentMethodToken());
        Instant now = clock.instant();
        if (behaviour == null) {
            charge = new SimulatedCharge(request.idempotencyKey(), request.amount(), request.currency(),
                    "unknown", "FAILED", "FAILED", "invalid_payment_method", null, now);
            charges.save(charge);
            return toResult(charge);
        }
        Instant webhookAt = behaviour.immediate() == Outcome.PENDING ? now.plus(properties.simulator().webhookDelay()) : null;
        charge = new SimulatedCharge(request.idempotencyKey(), request.amount(), request.currency(),
                request.paymentMethodToken(), behaviour.immediate().name(), behaviour.eventual().name(),
                behaviour.failureCode(), webhookAt, now);
        charges.saveAndFlush(charge);
        log.info("[SIMULATED PROVIDER] charge {} {} {} -> {}", charge.getReference(), request.amount(), request.currency(),
                behaviour.immediate());
        if (behaviour.loseResponse()) {
            throw new ProviderUnavailableException("[SIMULATED] provider processed the charge but the response timed out",
                    null);
        }
        return toResult(charge);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ChargeResult> retrieve(String idempotencyKey) {
        return charges.findByIdempotencyKey(idempotencyKey).map(SimulatedPaymentProvider::toResult);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundResult refund(RefundRequest request) {
        var existing = jdbc.queryForList("SELECT reference FROM simulated_provider.refunds WHERE idempotency_key = ?",
                String.class, request.idempotencyKey());
        if (!existing.isEmpty()) {
            return new RefundResult(true, existing.get(0), null);
        }
        SimulatedCharge charge = charges.findByReference(request.chargeReference()).orElse(null);
        if (charge == null || !"SUCCEEDED".equals(charge.getStatus())) {
            return new RefundResult(false, null, "Charge not found or not captured");
        }
        BigDecimal remaining = charge.getAmount().subtract(charges.refundedAmount(charge.getReference()));
        if (request.amount().compareTo(remaining) > 0) {
            return new RefundResult(false, null, "Refund exceeds the remaining captured amount");
        }
        String reference = "sim_re_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO simulated_provider.refunds (id, reference, idempotency_key, charge_ref, amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), reference, request.idempotencyKey(),
                charge.getReference(), request.amount(), Timestamp.from(clock.instant()));
        log.info("[SIMULATED PROVIDER] refund {} of {} on {}", reference, request.amount(), charge.getReference());
        return new RefundResult(true, reference, null);
    }

    private static ChargeResult toResult(SimulatedCharge charge) {
        Outcome outcome = Outcome.valueOf(charge.getStatus());
        String code = outcome == Outcome.FAILED ? charge.getFailureCode() : null;
        return new ChargeResult(outcome, charge.getReference(), code,
                code == null ? null : "[SIMULATED] payment declined: " + code);
    }
}
