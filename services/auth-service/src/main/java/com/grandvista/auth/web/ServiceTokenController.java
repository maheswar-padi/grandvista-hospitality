package com.grandvista.auth.web;

import com.grandvista.auth.service.ServiceClientAuthenticator;
import com.grandvista.auth.service.TokenIssuer;
import com.grandvista.auth.web.dto.AuthDtos.ServiceTokenResponse;
import com.grandvista.platform.error.ApiException;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 client-credentials grant for backend services. Lives under {@code /internal}, which the API
 * gateway never routes, so it is reachable only on the private service network.
 */
@Hidden
@RestController
@RequestMapping("/internal/v1")
public class ServiceTokenController {

    private final ServiceClientAuthenticator clients;
    private final TokenIssuer tokenIssuer;

    public ServiceTokenController(ServiceClientAuthenticator clients, TokenIssuer tokenIssuer) {
        this.clients = clients;
        this.tokenIssuer = tokenIssuer;
    }

    @PostMapping(path = "/service-tokens", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ServiceTokenResponse issue(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("grant_type") String grantType) {
        if (!"client_credentials".equals(grantType)) {
            throw ApiException.badRequest("UNSUPPORTED_GRANT_TYPE", "Only client_credentials is supported.");
        }
        String clientId = clients.authenticate(authorization);
        TokenIssuer.IssuedToken token = tokenIssuer.issueServiceToken(clientId);
        return new ServiceTokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }
}
