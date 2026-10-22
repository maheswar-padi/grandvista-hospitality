package com.grandvista.auth.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key so other services can verify tokens without sharing secrets. */
@RestController
@Tag(name = "Keys")
public class JwksController {

    private final JWKSet publicKeys;

    public JwksController(RSAKey signingKey) {
        this.publicKeys = new JWKSet(signingKey.toPublicJWK());
    }

    @Operation(summary = "JSON Web Key Set containing the public token-signing key")
    @GetMapping("/oauth2/jwks")
    public Map<String, Object> jwks() {
        return publicKeys.toJSONObject();
    }
}
