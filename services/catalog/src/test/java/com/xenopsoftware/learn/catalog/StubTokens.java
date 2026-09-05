package com.xenopsoftware.learn.catalog;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Decodes {@code username~tenant~side} as if it were a verified token — the template's test
 * seam (T-9.10), same as identity's and streaming's: signature and issuer checks are Spring Security's
 * contract, and everything these tests own starts after verification.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubTokens {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            String[] parts = token.split("~", -1);
            Jwt.Builder jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("sub-" + parts[0])
                .claim("preferred_username", parts[0])
                .claim("side", parts[2])
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
            if (!parts[1].isEmpty()) {
                jwt.claim("tenant_id", parts[1]);
                jwt.claim("email", parts[0] + "@" + parts[1] + ".test");
            }
            return jwt.build();
        };
    }
}
