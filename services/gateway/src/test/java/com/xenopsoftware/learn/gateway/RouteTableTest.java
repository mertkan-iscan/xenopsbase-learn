package com.xenopsoftware.learn.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * The route table resolves the paths it is meant to distinguish (T-9.17, ADR-0111).
 *
 * <h2>Why this test exists, and why the ordering is the whole subject</h2>
 *
 * This platform's public API is <b>not namespaced by service</b>. {@code /api/v1/me} is identity,
 * {@code /api/v1/me/home} is catalog, and {@code /api/v1/me/nodes/{id}/progress} is streaming.
 * Spring Cloud Gateway takes the first matching route in declaration order, so a broader pattern
 * placed above a narrower one does not fail — it silently swallows it, and the symptom is a 404
 * from a service that has never heard of the resource.
 *
 * <p>That is not a property anybody can keep true by reading a YAML file, and it gets less true
 * every time an endpoint is added. So every distinction the table is supposed to make is asserted
 * here by name.
 *
 * <h2>The one path deliberately not routed</h2>
 *
 * {@code /api/v1/assignments/**} is claimed by identity (role assignments) and by catalog
 * (content assignments), with different meanings. It resolves to the collision fallback rather
 * than to either service, and {@link #theCollisionIsRoutedNowhereOnPurpose()} pins that so the
 * gap cannot be closed by accident — the fix is ADR-0109's {@code core} merge, not a guess in
 * this table.
 *
 * <h2>What starting the context proves on its own</h2>
 *
 * That this module boots as a <b>reactive</b> application. A servlet container reaching the
 * classpath makes Boot resolve the type as SERVLET and Spring Cloud Gateway refuses to start —
 * so any test that gets as far as an assertion has already proved the thing ADR-0111's build
 * rules exist to protect.
 */
@SpringBootTest
class RouteTableTest {

    /** Session storage and the rate limiter both need a real one; neither is stubbed here. */
    private static final GenericContainer<?> VALKEY =
        new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    /**
     * Endpoints are stated rather than discovered from an {@code issuer-uri}.
     *
     * <p>Boot resolves an issuer's metadata document eagerly at startup, so leaving the real
     * issuer configured would make this test require a running Keycloak — and then fail for a
     * reason that has nothing to do with routing. Naming the endpoints directly keeps the OAuth2
     * wiring real (the beans are built, the filter chain is assembled) without a network call.
     */
    @DynamicPropertySource
    static void withoutARunningIdentityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));

        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", () -> null);
        registry.add("spring.security.oauth2.client.provider.keycloak.authorization-uri",
            () -> "http://localhost:8081/realms/xenopslearn/protocol/openid-connect/auth");
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri",
            () -> "http://localhost:8081/realms/xenopslearn/protocol/openid-connect/token");
        registry.add("spring.security.oauth2.client.provider.keycloak.jwk-set-uri",
            () -> "http://localhost:8081/realms/xenopslearn/protocol/openid-connect/certs");
        registry.add("spring.security.oauth2.client.provider.keycloak.user-info-uri",
            () -> "http://localhost:8081/realms/xenopslearn/protocol/openid-connect/userinfo");
        registry.add("spring.security.oauth2.client.provider.keycloak.user-name-attribute", () -> "sub");
        registry.add("spring.security.oauth2.client.registration.keycloak.client-secret", () -> "test-secret");

        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> null);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> "http://localhost:8081/realms/xenopslearn/protocol/openid-connect/certs");
    }

    @Autowired
    private RouteLocator routes;

    @ParameterizedTest(name = "{0} is served by {1}")
    @CsvSource({
        // The three-way split of /api/v1/me/**, which is the ordering this table gets wrong first.
        "/api/v1/me,                                  identity",
        "/api/v1/me/reach/course/read,                identity",
        "/api/v1/me/home,                             catalog-home",
        "/api/v1/me/nodes/node-1/progress,            streaming-progress",
        "/api/v1/me/nodes/node-1/playback-token,      streaming-progress",

        // Everything else, one per owner.
        "/api/v1/users/u-1,                           identity",
        "/api/v1/groups,                              identity",
        "/api/v1/roles/r-1/assignments,               identity",
        "/api/v1/platform/tenants,                    identity",
        "/api/v1/auth-info,                           identity",
        "/api/v1/courses/c-1,                         catalog",
        "/api/v1/content-items,                       catalog",
        "/api/v1/telemetry/events,                    reporting",
        "/api/v1/videos/v-1/upload-target,            streaming-videos",
        "/webhooks/media/state,                       streaming-webhooks",
    })
    void eachPathReachesItsOwner(String path, String expectedRouteId) {
        assertThat(routeIdFor(path)).isEqualTo(expectedRouteId);
    }

    /**
     * The collision goes to the fallback, not to a service.
     *
     * <p>Asserted rather than left implicit because the tempting fix — adding
     * {@code /api/v1/assignments/**} to identity, which owns the older of the two — would make
     * catalog's assignment cycles 404 with nothing in any log explaining why.
     */
    @Test
    void theCollisionIsRoutedNowhereOnPurpose() {
        assertThat(routeIdFor("/api/v1/assignments")).isEqualTo("unroutable-assignments");
        assertThat(routeIdFor("/api/v1/assignments/a-1/cycles")).isEqualTo("unroutable-assignments");
    }

    /**
     * The service-to-service surface has no route at all.
     *
     * <p>{@code SecurityConfiguration} denies it before routing, so this asserts the second half
     * of the same rule: even if the security rule were removed, there is nowhere for the request
     * to go. T-9.11's {@code whoami} and relay are operator tools, and the relay makes a service
     * originate a call.
     */
    @Test
    void theInternalSurfaceIsNotReachableFromTheEdge() {
        assertThat(routeIdFor("/api/v1/internal/whoami")).isNull();
    }

    /**
     * The id of the route that would actually serve {@code path}, or null if none would.
     *
     * <p>{@code concatMap(...).next()} rather than collecting every match, because that is what
     * {@code RoutePredicateHandlerMapping} does: routes are evaluated in order and the first one
     * whose predicate passes wins. Asserting against all matches would let a table with two
     * matching routes pass here and behave differently in production — which is precisely the
     * failure mode this test is about.
     */
    private String routeIdFor(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        Route first = routes.getRoutes()
            .concatMap(route -> Mono.from(route.getPredicate().apply(exchange))
                .filter(Boolean::booleanValue)
                .map(matched -> route))
            .next()
            .block();
        return first == null ? null : first.getId();
    }
}
