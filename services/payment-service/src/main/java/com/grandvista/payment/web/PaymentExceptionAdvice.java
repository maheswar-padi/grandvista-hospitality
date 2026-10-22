package com.grandvista.payment.web;

import com.grandvista.payment.domain.IllegalPaymentTransitionException;
import com.grandvista.platform.error.ProblemDetails;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentExceptionAdvice {

    @ExceptionHandler(IllegalPaymentTransitionException.class)
    public ResponseEntity<ProblemDetail> illegalTransition(IllegalPaymentTransitionException ex) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "ILLEGAL_PAYMENT_TRANSITION",
                "The payment cannot move from " + ex.getFrom() + " to " + ex.getTo() + ".");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
