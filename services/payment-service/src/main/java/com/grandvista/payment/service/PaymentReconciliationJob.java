package com.grandvista.payment.service;

import com.grandvista.payment.config.PaymentProperties;
import com.grandvista.payment.provider.PaymentProvider;
import com.grandvista.payment.repository.PaymentRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves payments left PENDING because the provider's answer was lost (timeout) or a webhook never
 * arrived, by asking the provider for its authoritative record. If the provider has no record, the charge
 * never happened and the payment is marked FAILED.
 */
@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final PaymentService paymentService;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentReconciliationJob(PaymentRepository payments, PaymentProvider provider, PaymentService paymentService,
            PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.provider = provider;
        this.paymentService = paymentService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gv.payment.reconciliation.interval:20s}", initialDelayString = "30s")
    public void reconcile() {
        for (UUID id : payments.findPendingIdsUpdatedBefore(clock.instant().minus(properties.reconciliation().pendingThreshold()))) {
            try {
                reconcile(id);
            } catch (RuntimeException | PaymentProvider.ProviderUnavailableException ex) {
                log.warn("Reconciliation of payment {} failed; will retry: {}", id, ex.getMessage());
            }
        }
    }

    public void reconcile(UUID paymentId) throws PaymentProvider.ProviderUnavailableException {
        var result = provider.retrieve(paymentId.toString()).orElse(new PaymentProvider.ChargeResult(
                PaymentProvider.Outcome.FAILED, null, "provider_no_record", "The provider has no record of this charge."));
        if (result.outcome() != PaymentProvider.Outcome.PENDING) {
            paymentService.applyOutcome(paymentId, result, "RECONCILIATION");
            log.info("Reconciled payment {} -> {}", paymentId, result.outcome());
        }
    }
}
