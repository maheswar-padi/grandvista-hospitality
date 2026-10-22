package com.grandvista.notification.web;

import com.grandvista.notification.domain.Notification;
import com.grandvista.notification.repository.NotificationRepository;
import com.grandvista.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@Tag(name = "Notifications (staff)")
@SecurityRequirement(name = "bearerAuth")
public class AdminNotificationController {

    public record NotificationView(UUID id, String type, UUID eventId, UUID bookingId, String bookingReference,
                                   String recipient, String subject, String status, Instant createdAt, Instant sentAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getNotificationType(), n.getEventId(), n.getBookingId(),
                    n.getBookingReference(), n.getRecipient(), n.getSubject(), n.getStatus().name(), n.getCreatedAt(),
                    n.getSentAt());
        }
    }

    private final NotificationRepository notifications;

    public AdminNotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Operation(summary = "List e-mail notifications, newest first (optionally for one booking)")
    @GetMapping
    public PageResponse<NotificationView> list(@RequestParam(required = false) UUID bookingId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = bookingId == null ? notifications.findAllByOrderByCreatedAtDesc(pageable)
                : notifications.findByBookingIdOrderByCreatedAtDesc(bookingId, pageable);
        return PageResponse.of(result, NotificationView::of);
    }
}
