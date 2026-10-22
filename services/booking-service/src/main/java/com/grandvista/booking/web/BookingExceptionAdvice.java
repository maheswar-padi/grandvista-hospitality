package com.grandvista.booking.web;

import com.grandvista.booking.client.DownstreamException;
import com.grandvista.booking.domain.IllegalBookingTransitionException;
import com.grandvista.platform.client.ServiceCallException;
import com.grandvista.platform.error.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BookingExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(BookingExceptionAdvice.class);

    @ExceptionHandler(IllegalBookingTransitionException.class)
    public ResponseEntity<ProblemDetail> illegalTransition(IllegalBookingTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetails.of(HttpStatus.CONFLICT,
                "ILLEGAL_BOOKING_TRANSITION", "The booking cannot move from " + ex.getFrom() + " to " + ex.getTo() + "."));
    }

    /** A dependency is unreachable: 503 tells callers (and the outbox relay) to retry later. */
    @ExceptionHandler(ServiceCallException.class)
    public ResponseEntity<ProblemDetail> dependencyDown(ServiceCallException ex) {
        log.warn("Dependency failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", "A required service is temporarily unavailable. Please retry shortly."));
    }

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ProblemDetail> downstream(DownstreamException ex) {
        log.warn("Unhandled downstream rejection from {}: {}", ex.getService(), ex.code());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetails.of(HttpStatus.CONFLICT,
                "DOWNSTREAM_REJECTED", "The operation could not be completed."));
    }
}
