package com.grandvista.notification.service;

import com.grandvista.notification.config.NotificationProperties;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** Renders the HTML (Thymeleaf) and plain-text parts of each e-mail from a typed model. */
@Component
public class EmailComposer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH);

    public enum Kind {
        BOOKING_CONFIRMED("email/booking-confirmed", "Your stay is confirmed"),
        BOOKING_CANCELLED("email/booking-cancelled", "Your booking has been cancelled"),
        HOLD_EXPIRED("email/hold-expired", "Your reservation hold has expired"),
        PAYMENT_RECEIPT("email/payment-receipt", "Payment receipt"),
        PAYMENT_FAILED("email/payment-failed", "Your payment was not completed");

        final String template;
        final String headline;

        Kind(String template, String headline) {
            this.template = template;
            this.headline = headline;
        }
    }

    /** Everything an e-mail can show; fields not relevant to a kind are null. */
    public record EmailModel(Kind kind, UUID bookingId, String reference, String guestName, String roomName,
                             String roomTypeName, LocalDate checkIn, LocalDate checkOut, Integer nights, Integer guests,
                             BigDecimal totalAmount, BigDecimal refundAmount, BigDecimal paidAmount, String currency,
                             UUID paymentId, String provider, String failureCode, Instant holdExpiresAt, Instant occurredAt) {
    }

    public record RenderedEmail(String subject, String html, String text) {
    }

    private final SpringTemplateEngine templates;
    private final NotificationProperties properties;
    private final ZoneId hotelZone;

    public EmailComposer(SpringTemplateEngine templates, NotificationProperties properties,
            @Value("${gv.notification.hotel-zone:Asia/Kolkata}") String hotelZone) {
        this.templates = templates;
        this.properties = properties;
        this.hotelZone = ZoneId.of(hotelZone);
    }

    public String subject(EmailModel m) {
        return m.kind().headline + " — " + m.reference();
    }

    public RenderedEmail render(EmailModel m) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("hotelName", properties.hotelName());
        vars.put("headline", m.kind().headline);
        vars.put("reference", m.reference());
        vars.put("guestName", m.guestName() == null || m.guestName().isBlank() ? "Guest" : m.guestName());
        vars.put("roomName", m.roomName());
        vars.put("roomTypeName", m.roomTypeName());
        vars.put("checkIn", day(m.checkIn()));
        vars.put("checkOut", day(m.checkOut()));
        vars.put("nights", m.nights());
        vars.put("guests", m.guests());
        vars.put("total", money(m.totalAmount(), m.currency()));
        vars.put("refund", money(m.refundAmount(), m.currency()));
        vars.put("hasRefund", m.refundAmount() != null && m.refundAmount().signum() > 0);
        vars.put("paid", money(m.paidAmount(), m.currency()));
        vars.put("paymentId", m.paymentId());
        vars.put("simulated", "SIMULATED".equalsIgnoreCase(m.provider()));
        vars.put("provider", m.provider());
        vars.put("failureReason", failureText(m.failureCode()));
        vars.put("holdExpiresAt", m.holdExpiresAt() == null ? null : TIME.format(m.holdExpiresAt().atZone(hotelZone)));
        vars.put("occurredAt", m.occurredAt() == null ? null : TIME.format(m.occurredAt().atZone(hotelZone)));
        vars.put("manageUrl", properties.webBaseUrl() + "/account/bookings/" + m.bookingId());
        vars.put("searchUrl", properties.webBaseUrl() + "/rooms");
        Context context = new Context(Locale.ENGLISH, vars);
        String html = templates.process(m.kind().template, context);
        return new RenderedEmail(subject(m), html, text(m, vars));
    }

    private String text(EmailModel m, Map<String, Object> v) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(v.get("guestName")).append(",\n\n").append(m.kind().headline).append(".\n\n");
        sb.append("Booking reference: ").append(m.reference()).append('\n');
        if (m.roomName() != null) {
            sb.append("Room: ").append(m.roomName()).append('\n');
        }
        if (m.checkIn() != null) {
            sb.append("Stay: ").append(v.get("checkIn")).append(" to ").append(v.get("checkOut")).append('\n');
        }
        switch (m.kind()) {
            case BOOKING_CONFIRMED -> sb.append("Total: ").append(v.get("total")).append('\n');
            case BOOKING_CANCELLED -> sb.append("Refund: ").append(Boolean.TRUE.equals(v.get("hasRefund")) ? v.get("refund") : "none").append('\n');
            case PAYMENT_RECEIPT -> sb.append("Amount paid: ").append(v.get("paid")).append('\n')
                    .append("Payment ID: ").append(m.paymentId()).append('\n');
            case PAYMENT_FAILED -> sb.append("Reason: ").append(v.get("failureReason")).append('\n')
                    .append("You have not been charged and the room has been released.\n");
            case HOLD_EXPIRED -> sb.append("You have not been charged. The room was released.\n");
        }
        if (Boolean.TRUE.equals(v.get("simulated"))) {
            sb.append("\nThis was a simulated payment; no real money was charged.\n");
        }
        sb.append("\nManage your booking: ").append(v.get("manageUrl")).append("\n\n").append(properties.hotelName()).append('\n');
        return sb.toString();
    }

    static String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        String code = currency == null ? "INR" : currency;
        NumberFormat format = NumberFormat.getCurrencyInstance("INR".equals(code) ? Locale.of("en", "IN") : Locale.ENGLISH);
        format.setCurrency(Currency.getInstance(code));
        return format.format(amount);
    }

    private static String day(LocalDate date) {
        return date == null ? null : DAY.format(date);
    }

    static String failureText(String code) {
        if (code == null) {
            return "The payment could not be completed.";
        }
        return switch (code) {
            case "card_declined" -> "The card was declined by the issuer.";
            case "insufficient_funds" -> "The card has insufficient funds.";
            case "PAYMENT_EXPIRED", "expired" -> "The payment window expired.";
            default -> "The payment could not be completed (" + code + ").";
        };
    }
}
