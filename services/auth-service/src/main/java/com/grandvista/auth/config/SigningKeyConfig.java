package com.grandvista.auth.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Loads the RS256 signing key. The private key never leaves this service; other services verify
 * tokens with the public half published at {@code /oauth2/jwks}.
 */
@Configuration
public class SigningKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfig.class);

    @Bean
    RSAKey signingKey(AuthProperties properties) throws JOSEException {
        AuthProperties.Keys keys = properties.keys();
        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        if (hasText(keys.privateKeyLocation()) && hasText(keys.publicKeyLocation())) {
            privateKey = RsaKeyConverters.pkcs8().convert(open(keys.privateKeyLocation()));
            publicKey = RsaKeyConverters.x509().convert(open(keys.publicKeyLocation()));
            log.info("Loaded JWT signing key from configured PEM files");
        } else if (keys.allowEphemeral()) {
            KeyPair pair = generate();
            publicKey = (RSAPublicKey) pair.getPublic();
            privateKey = (RSAPrivateKey) pair.getPrivate();
            log.warn("No JWT signing key configured: generated an EPHEMERAL key. Tokens become invalid when "
                    + "this instance restarts. Configure GV_JWT_PRIVATE_KEY_PATH/GV_JWT_PUBLIC_KEY_PATH outside development.");
        } else {
            throw new IllegalStateException("JWT signing key is not configured and ephemeral keys are disabled");
        }
        RSAKey withoutId = new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE).build();
        return new RSAKey.Builder(withoutId).keyID(withoutId.computeThumbprint().toString()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
    }

    private static InputStream open(String location) {
        try {
            Resource resource = new DefaultResourceLoader().getResource(location.contains(":") ? location : "file:" + location);
            return resource.getInputStream();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read key file " + location, ex);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
