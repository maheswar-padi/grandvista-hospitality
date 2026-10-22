package com.grandvista.auth.web;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.platform.error.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token lives in an HttpOnly, SameSite=Strict cookie scoped to {@code /api/v1/auth}, so page
 * scripts can never read it and browsers never attach it to cross-site requests. Requests that use the
 * cookie must also carry {@code X-Requested-With: XMLHttpRequest}: a cross-origin page cannot add that
 * header without passing the gateway's CORS preflight, which closes the remaining CSRF gap.
 */
@Component
public class RefreshCookieSupport {

    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_HEADER_VALUE = "XMLHttpRequest";

    private final AuthProperties.Cookie settings;
    private final Duration ttl;

    public RefreshCookieSupport(AuthProperties properties) {
        this.settings = properties.cookie();
        this.ttl = properties.refreshTokenTtl();
    }

    public ResponseCookie create(String token) {
        return base(token).maxAge(ttl).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        requireCsrfHeader(request);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> settings.name().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank() && v.length() <= 128)
                .findFirst();
    }

    private void requireCsrfHeader(HttpServletRequest request) {
        if (!CSRF_HEADER_VALUE.equals(request.getHeader(CSRF_HEADER))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CSRF_HEADER_MISSING",
                    "This request must be sent by the GrandVista web application.");
        }
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(settings.name(), value)
                .httpOnly(true)
                .secure(settings.secure())
                .sameSite("Strict")
                .path(settings.path());
    }
}
