package com.grandvista.notification.service;

import com.grandvista.notification.config.NotificationProperties;
import com.grandvista.notification.domain.BookingContact;
import com.grandvista.notification.domain.Notification;
import com.grandvista.notification.messaging.Events.BookingEvent;
import com.grandvista.notification.messaging.Events.PaymentEvent;
import com.grandvista.notification.repository.BookingContactRepository;
import com.grandvista.notification.repository.NotificationRepository;
import com.grandvista.notification.service.EmailComposer.EmailModel;
import com.grandvista.notification.service.EmailComposer.Kind;
import com.grandvista.platform.messaging.EventTopology;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns domain events into e-mails, at most once per (event id, notification type).
 * <p>
 * Within one transaction it records the notification ({@code INSERT … ON CONFLICT DO NOTHING}), locks that row,
 * skips it if already SENT, sends the e-mail and marks it SENT. A concurrent duplicate delivery waits on the row
 * lock and then skips. If the process stops after the SMTP server accepted the e-mail but before the commit, the
 * redelivered event sends it again (the only duplicate window); the stable {@code Message-ID} lets mail clients
 * recognise that copy.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public enum Result { SENT, DUPLICATE, NO_EMAIL }

    /** A payment event arrived before any booking event for that booking; retried, then dead-lettered. */
    public static class ContactNotYetKnownException extends RuntimeException {
        public ContactNotYetKnownException(UUID bookingId) {
            super("No booking contact known yet for booking " + bookingId);
        }
    }

    private final BookingContactRepository contacts;
    private final NotificationRepository notifications;
    private final EmailComposer composer;
    private final JavaMailSender mail;
    private final NotificationProperties properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public NotificationService(BookingContactRepository contacts, NotificationRepository notifications,
            EmailComposer composer, JavaMailSender mail, NotificationProperties properties, TransactionTemplate tx,
            MeterRegistry meters, Clock clock) {
        this.contacts = contacts;
        this.notifications = notifications;
        this.composer = composer;
        this.mail = mail;
        this.properties = properties;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    public Result onBookingEvent(BookingEvent e) {
        Kind kind = switch (e.eventType()) {
            case EventTopology.BOOKING_CONFIRMED -> Kind.BOOKING_CONFIRMED;
            case EventTopology.BOOKING_CANCELLED -> Kind.BOOKING_CANCELLED;
            case EventTopology.HOLD_EXPIRED -> Kind.HOLD_EXPIRED;
            default -> null; // BOOKING_CREATED only updates the contact read model
        };
        return tx.execute(status -> {
            BookingContact contact = contacts.findById(e.bookingId()).orElseGet(() -> new BookingContact(e.bookingId()));
            contact.apply(e.reference(), e.customerEmail(), e.guestName(), e.roomName(), e.roomTypeName(), e.checkIn(),
                    e.checkOut(), e.nights(), e.guests(), e.totalAmount(), e.currency(), e.occurredAt());
            contacts.save(contact);
            if (kind == null) {
                return Result.NO_EMAIL;
            }
            EmailModel model = new EmailModel(kind, e.bookingId(), e.reference(), e.guestName(), e.roomName(),
                    e.roomTypeName(), e.checkIn(), e.checkOut(), e.nights(), e.guests(), e.totalAmount(), e.refundAmount(),
                    null, e.currency(), null, null, null, e.holdExpiresAt(), e.occurredAt());
            return sendOnce(e.eventId(), kind, e.customerEmail(), model);
        });
    }

    public Result onPaymentEvent(PaymentEvent e) {
        Kind kind = switch (e.eventType()) {
            case EventTopology.PAYMENT_SUCCEEDED -> Kind.PAYMENT_RECEIPT;
            case EventTopology.PAYMENT_FAILED -> Kind.PAYMENT_FAILED;
            default -> null;
        };
        if (kind == null) {
            return Result.NO_EMAIL;
        }
        return tx.execute(status -> {
            BookingContact c = contacts.findById(e.bookingId()).orElseThrow(() -> new ContactNotYetKnownException(e.bookingId()));
            EmailModel model = new EmailModel(kind, c.getBookingId(), c.getReference(), c.getGuestName(), c.getRoomName(),
                    c.getRoomTypeName(), c.getCheckIn(), c.getCheckOut(), c.getNights(), c.getGuests(), c.getTotalAmount(),
                    null, e.amount(), e.currency(), e.paymentId(), e.provider(), e.failureCode(), null, e.occurredAt());
            return sendOnce(e.eventId(), kind, c.getCustomerEmail(), model);
        });
    }

    private Result sendOnce(UUID eventId, Kind kind, String recipient, EmailModel model) {
        String subject = composer.subject(model);
        notifications.insertIfAbsent(UUID.randomUUID(), eventId, kind.name(), model.bookingId(), model.reference(),
                recipient, subject, clock.instant());
        Notification record = notifications.lockByEventAndType(eventId, kind.name()).orElseThrow();
        if (record.getStatus() == Notification.Status.SENT) {
            meters.counter("gv.notifications.duplicates", "type", kind.name()).increment();
            log.info("{} for event {} already sent; duplicate delivery ignored", kind, eventId);
            return Result.DUPLICATE;
        }
        var rendered = composer.render(model);
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.mailFrom());
            helper.setTo(recipient);
            helper.setSubject(rendered.subject());
            helper.setText(rendered.text(), rendered.html());
            message.setHeader("X-GrandVista-Event-Id", eventId.toString());
            message.saveChanges();
            message.setHeader("Message-ID", "<" + eventId + "." + kind.name().toLowerCase() + "@grandvista.local>");
            mail.send(message);
        } catch (MessagingException ex) {
            throw new IllegalStateException("Could not build e-mail: " + ex.getMessage(), ex);
        }
        record.markSent(clock.instant());
        meters.counter("gv.notifications.sent", "type", kind.name()).increment();
        log.info("Sent {} e-mail for booking {} to {}", kind, model.reference(), mask(recipient));
        return Result.SENT;
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }
}
