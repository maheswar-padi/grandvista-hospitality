package com.grandvista.platform.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Converts every failure into an RFC 9457 problem document. Unexpected exceptions are logged with the
 * correlation ID and returned as a generic 500 so that stack traces and internals never reach clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = ProblemDetails.of(ex.getStatus(), ex.getCode(), ex.getMessage());
        ex.getProperties().forEach(problem::setProperty);
        if (ex.getStatus().is5xxServerError()) {
            log.warn("Request failed with {} {}: {}", ex.getStatus().value(), ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(leafName(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return validationProblem(violations);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return validationProblem(List.of(new FieldViolation(ex.getName(), "has an invalid format")));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleConcurrentModification(RuntimeException ex) {
        log.info("Concurrent modification detected: {}", ex.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified by another request. Reload it and try again.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.info("Data integrity violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "The request conflicts with existing data.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** Let Spring Security's entry point / access-denied handler produce 401 and 403 responses. */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class, AuthenticationException.class})
    public void rethrowSecurity(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Quote the correlation ID when reporting this problem.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        List<FieldViolation> global = ex.getBindingResult().getGlobalErrors().stream()
                .map(e -> new FieldViolation(e.getObjectName(), e.getDefaultMessage()))
                .toList();
        return asObject(validationProblem(concat(violations, global)));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return asObject(validationProblem(violations));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // Framework-level errors (malformed JSON, unsupported media type, missing header, 404 ...)
        String code = HttpStatus.resolve(statusCode.value()) == null
                ? "HTTP_" + statusCode.value()
                : HttpStatus.valueOf(statusCode.value()).name();
        String detail = statusCode.value() == 400 ? "The request is malformed or missing required data."
                : body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail()
                : HttpStatus.valueOf(statusCode.value()).getReasonPhrase();
        ProblemDetail problem = ProblemDetails.of(statusCode, code, detail);
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private ResponseEntity<ProblemDetail> validationProblem(List<FieldViolation> violations) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "One or more fields are invalid.");
        problem.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private static String leafName(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        return b.isEmpty() ? a : java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> entity) {
        return (ResponseEntity) entity;
    }
}
