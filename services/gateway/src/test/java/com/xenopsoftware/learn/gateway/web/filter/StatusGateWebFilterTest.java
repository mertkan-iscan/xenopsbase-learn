package com.xenopsoftware.learn.gateway.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantClaims;
import com.xenopsoftware.learn.common.tenancy.TenantStatusKeys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The edge refuses exactly what the services refuse, and lets through exactly what they let
 * through (T-1.4, ADR-0111).
 *
 * <h2>Why this is worth its own test rather than trusting the shared contract</h2>
 *
 * ADR-0111 shares the key, the enum and the refusal body between the two gates, which removes the
 * class of bug where they disagree about a <em>string</em>. It does not remove the class where
 * they disagree about a <em>rule</em> — READ_ONLY permitting a GET, a missing entry meaning
 * ACTIVE, a Valkey timeout being permissive. Those are branches, and they are re-implemented here
 * against a different client.
 *
 * <p>The consequence of getting one wrong is asymmetric and neither direction is acceptable: too
 * strict and a paying customer is locked out of a platform that is working, too loose and a
 * suspended one keeps reading. So both directions are asserted, not just the refusals.
 */
class StatusGateWebFilterTest {

    private final ReactiveStringRedisTemplate valkey = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    private final StatusGateWebFilter filter = new StatusGateWebFilter(valkey);

    private boolean chainWasCalled;
    private final WebFilterChain chain = exchange -> {
        chainWasCalled = true;
        return Mono.empty();
    };

    @ParameterizedTest(name = "{0} {1} for a {2} account -> passed on: {3}")
    @CsvSource({
        // ACTIVE: everything.
        "GET,    /api/v1/courses,  ACTIVE,     true",
        "POST,   /api/v1/courses,  ACTIVE,     true",
        // READ_ONLY: the middle state that makes suspension a usable tool -- reads and exports
        // survive so a customer in a payment dispute can still get their data out.
        "GET,    /api/v1/courses,  READ_ONLY,  true",
        "HEAD,   /api/v1/courses,  READ_ONLY,  true",
        "POST,   /api/v1/courses,  READ_ONLY,  false",
        "PUT,    /api/v1/courses,  READ_ONLY,  false",
        "DELETE, /api/v1/courses,  READ_ONLY,  false",
        // SUSPENDED: both.
        "GET,    /api/v1/courses,  SUSPENDED,  false",
        "POST,   /api/v1/courses,  SUSPENDED,  false",
    })
    void theGateMatchesWhatTheServicesEnforce(String method, String path, String published, boolean passedOn) {
        publishedStatusIs(published);

        run(method, path, tokenFor("acme"));

        assertThat(chainWasCalled).isEqualTo(passedOn);
    }

    /**
     * A refusal carries the machine-readable code, byte for byte the same as the servlet gate's.
     *
     * <p>Asserted on the body rather than only on the status, because a UI branches on the code to
     * decide whether to say "suspended" or "read only" — two different things to be told, and
     * collapsing them is what {@code AccountStatus.messageFor} exists to prevent.
     */
    @Test
    void aRefusalSaysWhichStateItIs() {
        publishedStatusIs("READ_ONLY");
        MockServerWebExchange exchange = run("POST", "/api/v1/courses", tokenFor("acme"));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyOf(exchange))
            .isEqualTo(AccountStatus.READ_ONLY.refusalBody(true))
            .contains("ACCOUNT_READ_ONLY");
    }

    /**
     * Everything this gate cannot answer means ACTIVE.
     *
     * <p>T-1.4 settled the direction and the reasoning has not changed: a Valkey outage that
     * suspended every customer at once is a worse failure than one that briefly lets a suspended
     * customer read, and the writes that matter are refused inside the transaction by the module
     * that owns the rows. An unrecognised value is included because a newer build writing a status
     * this one does not know must not lock anybody out.
     */
    @ParameterizedTest(name = "published = {0} -> passed on")
    @CsvSource(nullValues = "NONE", value = {
        "NONE",                 // no entry: the normal state of a tenant nobody has suspended
        "SOMETHING_NEWER",      // written by a build that knows a status this one does not
        "''",                   // present and empty
    })
    void whatItCannotReadIsTreatedAsActive(String published) {
        when(valkey.opsForValue()).thenReturn(values);
        when(values.get(anyString()))
            .thenReturn(published == null ? Mono.empty() : Mono.just(published));

        run("POST", "/api/v1/courses", tokenFor("acme"));

        assertThat(chainWasCalled).isTrue();
    }

    @Test
    void andSoIsValkeyBeingDown() {
        when(valkey.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.error(new IllegalStateException("connection refused")));

        run("POST", "/api/v1/courses", tokenFor("acme"));

        assertThat(chainWasCalled).isTrue();
    }

    /**
     * Anything outside {@code /api/} is not this filter's business.
     *
     * <p>A suspended tenant must still be able to sign in far enough to be told why, and an
     * operator must still reach the probes. Asserted by the absence of any Valkey call, so the
     * skip is proved rather than inferred from the request succeeding.
     */
    @ParameterizedTest
    @CsvSource({"/management/health", "/login/oauth2/code/keycloak", "/", "/webhooks/media/state"})
    void nonApiPathsAreNotGated(String path) {
        // No stubbing at all: any read would throw a NullPointerException on opsForValue().
        run("GET", path, tokenFor("acme"));

        assertThat(chainWasCalled).isTrue();
    }

    /**
     * Platform staff are gated on the reserved tenant, exactly as {@code TenantFilter} binds them.
     *
     * <p>The pair matters. If the edge read {@code tenant_id} for a platform token it would gate
     * staff against whichever customer the claim named — and a platform token that also carries a
     * customer's {@code tenant_id} is precisely the case {@code TenantFilter} refuses to honour.
     */
    @Test
    void platformStaffAreGatedOnTheReservedTenant() {
        when(valkey.opsForValue()).thenReturn(values);
        when(values.get(TenantStatusKeys.forTenant(TenantClaims.PLATFORM_TENANT)))
            .thenReturn(Mono.just("SUSPENDED"));
        when(values.get(TenantStatusKeys.forTenant("acme"))).thenReturn(Mono.just("ACTIVE"));

        JwtAuthenticationToken staff = tokenFor(Map.of(
            TenantClaims.TENANT_CLAIM, "acme",
            TenantClaims.SIDE_CLAIM, TenantClaims.PLATFORM));

        run("GET", "/api/v1/platform/tenants", staff);

        assertThat(chainWasCalled)
            .as("the reserved tenant's status decides, not the tenant_id the token also carries")
            .isFalse();
    }

    /** An unauthenticated request is the security chain's to refuse, not this filter's. */
    @Test
    void anonymousRequestsArePassedOnForTheSecurityChainToHandle() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        run("GET", "/api/v1/courses", anonymous);

        assertThat(chainWasCalled).isTrue();
    }

    // ---------------------------------------------------------------------------------------

    private void publishedStatusIs(String status) {
        when(valkey.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just(status));
    }

    private MockServerWebExchange run(String method, String path,
            org.springframework.security.core.Authentication authentication) {
        chainWasCalled = false;
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path));
        StepVerifier.create(filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
            .verifyComplete();
        return exchange;
    }

    private static JwtAuthenticationToken tokenFor(String tenant) {
        return tokenFor(Map.of(TenantClaims.TENANT_CLAIM, tenant, TenantClaims.SIDE_CLAIM, "TENANT"));
    }

    private static JwtAuthenticationToken tokenFor(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("sub-person")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claims(existing -> existing.putAll(claims))
            .build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
    }

    private static String bodyOf(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block() == null
            ? ""
            : new String(exchange.getResponse().getBodyAsString()
                .block().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
