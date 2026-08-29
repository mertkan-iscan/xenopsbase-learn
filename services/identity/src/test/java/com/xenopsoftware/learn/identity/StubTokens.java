package com.xenopsoftware.learn.identity;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Decodes {@code username~tenant~side} as if it were a verified token — the seam web tests
 * substitute, and the only one: signature, issuer and expiry checks are Spring Security's
 * contract, and everything these tests own starts <i>after</i> verification.
 *
 * <p>An empty tenant segment produces a token with no {@code tenant_id} claim, which is what a
 * platform-side user's token looks like. The email claim mirrors the realm's shape
 * ({@code <username>@<tenant>.test}), because provisioning (T-1.2) requires it the way the real
 * realm supplies it.
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
            }
            // Every token carries an email, platform-side included: staff are provisioned as
            // app_user rows in the platform tenant just like anybody else (T-1.5).
            jwt.claim("email", parts[0] + "@" + (parts[1].isEmpty() ? "platform" : parts[1]) + ".test");
            jwt.claim("email_verified", true);
            return jwt.build();
        };
    }
}
