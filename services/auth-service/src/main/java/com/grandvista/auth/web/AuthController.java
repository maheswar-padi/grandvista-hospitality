package com.grandvista.auth.web;

import com.grandvista.auth.domain.AuthAuditEvent.Type;
import com.grandvista.auth.domain.UserAccount;
import com.grandvista.auth.service.AccountService;
import com.grandvista.auth.service.AuditService;
import com.grandvista.auth.service.RefreshTokenService;
import com.grandvista.auth.service.TokenIssuer;
import com.grandvista.auth.web.dto.AuthDtos.AuthResponse;
import com.grandvista.auth.web.dto.AuthDtos.LoginRequest;
import com.grandvista.auth.web.dto.AuthDtos.RegisterRequest;
import com.grandvista.auth.web.dto.AuthDtos.UserResponse;
import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AccountService accounts;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookieSupport cookies;
    private final AuditService audit;

    public AuthController(AccountService accounts, TokenIssuer tokenIssuer, RefreshTokenService refreshTokens,
            RefreshCookieSupport cookies, AuditService audit) {
        this.accounts = accounts;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.audit = audit;
    }

    @Operation(summary = "Register a customer account and start a session")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount account = accounts.register(request);
        return session(HttpStatus.CREATED, account, refreshTokens.issue(account.getId()));
    }

    @Operation(summary = "Sign in with e-mail and password")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        UserAccount account = accounts.authenticate(request.email(), request.password());
        return session(HttpStatus.OK, account, refreshTokens.issue(account.getId()));
    }

    @Operation(summary = "Exchange the refresh-token cookie for a new access token (rotates the cookie)")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        String token = cookies.read(request).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN", "Your session has expired. Please sign in again."));
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(token);
        UserAccount account = accounts.requireActive(rotation.userId());
        return session(HttpStatus.OK, account, rotation.newToken());
    }

    @Operation(summary = "End the session: revokes the refresh token and clears the cookie")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        cookies.read(request).flatMap(refreshTokens::revoke)
                .ifPresent(userId -> audit.record(userId, Type.LOGOUT, null));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @Operation(summary = "Current user profile", security = @SecurityRequirement(name = "bearer-jwt"))
    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return UserResponse.from(accounts.get(AuthenticatedUser.from(authentication).requireUserId()));
    }

    private ResponseEntity<AuthResponse> session(HttpStatus status, UserAccount account, String refreshToken) {
        TokenIssuer.IssuedToken access = tokenIssuer.issueAccessToken(account);
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.create(refreshToken).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthResponse(access.value(), "Bearer", access.expiresInSeconds(), UserResponse.from(account)));
    }
}
