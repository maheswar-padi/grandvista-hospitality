package com.grandvista.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.service.BookingStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The recovery job running on its real schedule (not invoked by the test): an unpaid booking whose hold has
 * lapsed is voided, released and expired from database state alone.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.booking.recovery.interval=300ms", "gv.booking.recovery.initial-delay=0s", "gv.booking.recovery.expiry-grace=0s"})
@Import(BookingRecoverySchedulingTest.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class BookingRecoverySchedulingTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }

    @MockitoBean
    RoomServiceClient rooms;
    @MockitoBean
    PaymentServiceClient payments;
    @Autowired
    BookingStore store;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void scheduledRecoveryExpiresAnUnpaidBookingAfterItsHold() throws Exception {
        UUID paymentId = UUID.randomUUID();
        LocalDate checkIn = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(30);
        Booking booking = new Booking("GV-SCHED1", UUID.randomUUID(), "a@b.test", UUID.randomUUID(), checkIn,
                checkIn.plusDays(1), 1, "key-scheduled-001", "fp", null, Instant.now());
        store.insert(booking);
        store.attachPaymentAndAwaitPayment(booking.getId(), paymentId, "test");
        jdbc.update("UPDATE bookings SET hold_expires_at = now() - interval '1 minute', updated_at = now() WHERE id = ?",
                booking.getId());
        when(payments.cancel(paymentId, "BOOKING_EXPIRED")).thenReturn(new PaymentServiceClient.Payment(paymentId,
                booking.getId(), BigDecimal.TEN, "INR", "CANCELLED", "SIMULATED", true, null, BigDecimal.ZERO, Instant.now()));

        long deadline = System.currentTimeMillis() + 10_000;
        while (store.load(booking.getId()).getStatus() == BookingStatus.PENDING_PAYMENT && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(store.load(booking.getId()).getStatus()).isEqualTo(BookingStatus.EXPIRED);
        verify(rooms).release(eq(booking.getId()), any());
    }
}
