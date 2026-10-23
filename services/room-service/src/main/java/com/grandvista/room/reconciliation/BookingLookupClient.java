package com.grandvista.room.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads booking facts from the booking-service's internal lookup API (service token, chunks of 1000 ids). */
@Component
public class BookingLookupClient {

    static final int CHUNK = 1000;
    private static final ParameterizedTypeReference<List<RemoteBooking>> LIST = new ParameterizedTypeReference<>() {
    };

    /** Shape of {@code InternalBookingLookupController.BookingFact} in the booking-service. */
    public record RemoteBooking(UUID bookingId, String reference, String status, UUID roomId, UUID propertyId,
                                LocalDate checkIn, LocalDate checkOut, Instant holdExpiresAt, Instant updatedAt) {
        ReconciliationEngine.BookingFact toFact() {
            return new ReconciliationEngine.BookingFact(bookingId, reference, status, roomId, checkIn, checkOut,
                    holdExpiresAt, updatedAt);
        }
    }

    private final RestClient bookingService;

    public BookingLookupClient(RestClient bookingServiceLookupClient) {
        this.bookingService = bookingServiceLookupClient;
    }

    public List<ReconciliationEngine.BookingFact> lookup(Collection<UUID> bookingIds) {
        List<ReconciliationEngine.BookingFact> out = new ArrayList<>();
        for (List<UUID> chunk : chunks(bookingIds)) {
            out.addAll(post("/internal/v1/bookings/lookup", Map.of("bookingIds", chunk)));
        }
        return out;
    }

    public List<ReconciliationEngine.BookingFact> holdingInventory(Collection<UUID> roomIds, LocalDate checkOutFrom) {
        List<ReconciliationEngine.BookingFact> out = new ArrayList<>();
        for (List<UUID> chunk : chunks(roomIds)) {
            out.addAll(post("/internal/v1/bookings/holding-inventory", Map.of("roomIds", chunk, "checkOutFrom", checkOutFrom)));
        }
        return out;
    }

    private List<ReconciliationEngine.BookingFact> post(String path, Object body) {
        List<RemoteBooking> answer = bookingService.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(LIST);
        return answer == null ? List.of() : answer.stream().map(RemoteBooking::toFact).toList();
    }

    static List<List<UUID>> chunks(Collection<UUID> ids) {
        List<UUID> all = List.copyOf(ids);
        List<List<UUID>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }
}
