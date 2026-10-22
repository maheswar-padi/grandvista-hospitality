package com.grandvista.notification.repository;

import com.grandvista.notification.domain.Notification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Creates the notification record unless one exists for this event and type (concurrency-safe). */
    @Modifying
    @Query(value = """
            INSERT INTO notifications (id, event_id, notification_type, booking_id, booking_reference, recipient, subject,
                                       status, created_at)
            VALUES (:id, :eventId, :type, :bookingId, :reference, :recipient, :subject, 'SENDING', :now)
            ON CONFLICT (event_id, notification_type) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID id, UUID eventId, String type, UUID bookingId, String reference, String recipient,
            String subject, Instant now);

    /** Row lock: a concurrent duplicate delivery waits here and then sees SENT. */
    @Query(value = "SELECT * FROM notifications WHERE event_id = :eventId AND notification_type = :type FOR UPDATE",
            nativeQuery = true)
    Optional<Notification> lockByEventAndType(UUID eventId, String type);

    Page<Notification> findByBookingIdOrderByCreatedAtDesc(UUID bookingId, Pageable pageable);

    Page<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(Notification.Status status);
}
