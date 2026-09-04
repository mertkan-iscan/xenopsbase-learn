package com.xenopsoftware.learn.common.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * This service's own credential (T-9.11): a client-credentials token from the realm, cached
 * until shortly before it expires.
 *
 * <p>Per service and rotatable on its own. A shared secret would make rotating any of them an
 * outage for all of them, and would make "which service called" unanswerable — the callee reads
 * the caller's identity out of this token, so a shared one would let any service claim to be any
 * other.
 *
 * <p>Cached because a token fetch per outbound call would put Keycloak on the hot path of every
 * inter-service request, which is the shape ADR-0103 rejected for authorization and rejects
 * again here. Refreshed a minute early, so a request never carries a token that expires in
 * flight.
 */
@Component
public class ServiceTokens {

    private record CachedToken(String token, Instant refreshAfter) {}

    private final RestClient tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public ServiceTokens(
            @Value("${platform.service-auth.token-uri:${spring.security.oauth2.resourceserver.jwt.issuer-uri}/protocol/openid-connect/token}") String tokenUri,
            @Value("${platform.service-auth.client-id:}") String clientId,
            @Value("${platform.service-auth.client-secret:}") String clientSecret) {
        // RestClient.builder() rather than an injected RestClient.Builder: Boot 4 does not
        // auto-configure one in every service, and a shared library must not depend on
        // whether the service that includes it happens to have that auto-configuration.
        this.tokenEndpoint = RestClient.builder().baseUrl(tokenUri).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** A valid token for this service, fetched only when the cached one is close to expiring. */
    public String current() {
        if (!configured()) {
            throw new IllegalStateException(
                "This service has no credentials of its own, so it cannot call another service. "
                + "Set platform.service-auth.client-id and client-secret (T-9.11).");
        }
        CachedToken token = cached.get();
        if (token != null && Instant.now().isBefore(token.refreshAfter())) {
            return token.token();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> response = tokenEndpoint.post()
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
        String issued = String.valueOf(response.get("access_token"));
        long expiresIn = response.get("expires_in") instanceof Number seconds
            ? seconds.longValue() : 300;
        cached.set(new CachedToken(issued,
            Instant.now().plus(Duration.ofSeconds(Math.max(30, expiresIn - 60)))));
        return issued;
    }
}
