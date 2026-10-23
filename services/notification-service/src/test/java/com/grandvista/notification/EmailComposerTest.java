package com.grandvista.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandvista.notification.config.NotificationProperties;
import com.grandvista.notification.service.EmailComposer;
import com.grandvista.notification.service.EmailComposer.EmailModel;
import com.grandvista.notification.service.EmailComposer.Kind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class EmailComposerTest {

    private final EmailComposer composer;

    EmailComposerTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        composer = new EmailComposer(engine, new NotificationProperties(null, "https://hotel.example/", null), "Asia/Kolkata");
    }

    private EmailModel model(Kind kind, BigDecimal refund, String provider, String failure) {
        return new EmailModel(kind, UUID.fromString("00000000-0000-0000-0000-000000000001"), "GV-ABC234", "GrandVista Goa", "Asha <Rao>",
                "Classic Queen Room", "Classic Room", LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 12), 2, 2,
                new BigDecimal("10584.00"), refund, new BigDecimal("10584.00"), "INR", UUID.randomUUID(), provider, failure,
                Instant.parse("2027-03-01T10:15:00Z"), Instant.parse("2027-03-01T10:00:00Z"));
    }

    @Test
    void everyKindRendersHtmlAndTextWithEscapedNamesAndAbsoluteLinks() {
        for (Kind kind : Kind.values()) {
            var email = composer.render(model(kind, new BigDecimal("5292.00"), "SIMULATED", "card_declined"));
            assertThat(email.subject()).endsWith("— GV-ABC234");
            assertThat(email.html()).startsWith("<!DOCTYPE html>").contains("GrandVista Hospitality", "GV-ABC234")
                    .contains("Asha &lt;Rao&gt;").doesNotContain("Asha <Rao>");
            assertThat(email.text()).contains("Booking reference: GV-ABC234", "Hotel: GrandVista Goa");
        }
        assertThat(composer.render(model(Kind.BOOKING_CONFIRMED, null, null, null)).html())
                .contains("https://hotel.example/account/bookings/00000000-0000-0000-0000-000000000001", "₹10,584.00");
    }

    @Test
    void kindSpecificContent() {
        assertThat(composer.render(model(Kind.BOOKING_CANCELLED, BigDecimal.ZERO, null, null)).html())
                .contains("no refund applies");
        assertThat(composer.render(model(Kind.PAYMENT_FAILED, null, null, "card_declined")).html())
                .contains("declined by the issuer", "You have not been charged");
        assertThat(composer.render(model(Kind.PAYMENT_RECEIPT, null, "RAZORPAY", null)).html())
                .doesNotContain("simulated payment");
    }
}
