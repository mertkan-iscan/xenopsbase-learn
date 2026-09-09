package com.xenopsoftware.learn.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * What actually reaches a service when the browser calls the gateway (T-10.2).
 *
 * <p>Against a REAL upstream — twenty lines of {@code com.sun.net.httpserver} — rather than a mock,
 * because the assertions are about headers on the wire and a mock would only prove what the mock
 * was told to record.
 *
 * <p>The two that matter are the two the design rests on: the inward call carries <b>the token this
 * process obtained</b>, and it carries <b>nothing the caller supplied</b> in its place. A relay
 * that forwarded a caller's {@code Authorization} header would be an oracle that signs whatever it
 * is handed, and the whole "the browser never holds a token" argument would be decoration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiRelayTest.AProviderNobodyHasToStart.class)
class ApiRelayTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class AProviderNobodyHasToStart {

        /** See {@code GatewaySessionTest}: an issuer-uri would make startup need a live Keycloak. */
        @Bean
        ClientRegistrationRepository clientRegistrations() {
            return new InMemoryClientRegistrationRepository(ClientRegistration
                .withRegistrationId("oidc")
                .clientId("web")
                .clientSecret("not-a-real-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .authorizationUri("http://keycloak.invalid/auth")
                .tokenUri("http://keycloak.invalid/token")
                .jwkSetUri("http://keycloak.invalid/certs")
                .userInfoUri("http://keycloak.invalid/userinfo")
                .userNameAttributeName("sub")
                .build());
        }
    }

    private static final GenericContainer<?> VALKEY =
        new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    /** What the upstream saw, so the assertions are about bytes that arrived. */
    private static final AtomicReference<HttpExchange> LAST = new AtomicReference<>();

    private static final HttpServer UPSTREAM;

    static {
        VALKEY.start();
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException cannotBind) {
            throw new IllegalStateException(cannotBind);
        }
        UPSTREAM.createContext("/", exchange -> {
            LAST.set(exchange);
            byte[] answer = "{\"who\":\"me\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // A cookie the relay must not pass on: a resource server has no business writing to
            // the app origin's jar, and the one thing in that jar is the session.
            exchange.getResponseHeaders().add("Set-Cookie", "upstream=1");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        UPSTREAM.start();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("gateway.identity",
            () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void forgetTheLastRequest() {
        LAST.set(null);
    }

    private static OAuth2AccessToken theSessionsToken() {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "the-sessions-token",
            Instant.now(), Instant.now().plusSeconds(3600));
    }

    @Test
    void theInwardCallCarriesTheTokenThisProcessHolds() throws Exception {
        mvc.perform(get("/api/v1/me")
                .with(oidcLogin())
                .with(oauth2Client("oidc").accessToken(theSessionsToken())))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        assertThat(LAST.get().getRequestHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer the-sessions-token");
    }

    @Test
    void aCallerCannotSmuggleTheirOwnTokenThroughTheRelay() throws Exception {
        mvc.perform(get("/api/v1/me")
                .header("Authorization", "Bearer a-token-the-caller-made-up")
                .with(oidcLogin())
                .with(oauth2Client("oidc").accessToken(theSessionsToken())))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        // Replaced, not appended: the inward call must be authorised by the session this process
        // verified and by nothing else.
        assertThat(LAST.get().getRequestHeaders().get("Authorization"))
            .containsExactly("Bearer the-sessions-token");
    }

    @Test
    void theSessionCookieStopsAtTheGateway() throws Exception {
        mvc.perform(get("/api/v1/me")
                .header("Cookie", "LEARNSESSION=abc; XSRF-TOKEN=def")
                .with(oidcLogin())
                .with(oauth2Client("oidc").accessToken(theSessionsToken())))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        // The services are stateless resource servers. A credential in front of three processes
        // that have no reason to see one is a credential in three more places.
        assertThat(LAST.get().getRequestHeaders().get("Cookie")).isNull();
    }

    @Test
    void anUpstreamCannotWriteACookieOnTheAppsOrigin() throws Exception {
        var relayed = mvc.perform(get("/api/v1/me")
                .with(oidcLogin())
                .with(oauth2Client("oidc").accessToken(theSessionsToken())))
            .andReturn().getResponse();

        assertThat(relayed.getHeaders("Set-Cookie")).isEmpty();
        assertThat(relayed.getContentAsString()).isEqualTo("{\"who\":\"me\"}");
    }

    /**
     * The credential is a cookie now, so a write needs something a cross-origin page cannot
     * produce.
     *
     * <p>This is the assertion that needs somebody signed in: an anonymous request is refused by
     * authentication first, and 401 would pass a test aimed at CSRF while proving nothing about it.
     */
    @Test
    void aWriteWithoutTheCsrfHeaderIsRefusedEvenWithAValidSession() throws Exception {
        mvc.perform(post("/api/v1/groups")
                .contentType("application/json")
                .content("{}")
                .with(oidcLogin())
                .with(oauth2Client("oidc").accessToken(theSessionsToken())))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));

        assertThat(LAST.get()).as("and nothing reached a service").isNull();
    }

    @Test
    void aWriteWithItGoesThrough() throws Exception {
        mvc.perform(post("/api/v1/groups")
                .contentType("application/json")
                .content("{}")
                .with(csrf())
                .with(oidcLogin())
                .with(oauth2Client("oidc").accessToken(theSessionsToken())))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    }

    @Test
    void nothingOutsideTheRoutingTableIsProxied() throws Exception {
        mvc.perform(get("/api/v1/something-nobody-declared")
                .with(oidcLogin())
                .with(oauth2Client("oidc").accessToken(theSessionsToken())))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        // It IS routed -- `/api` is identity's prefix, and that is the point of the table having a
        // catch-all for identity rather than a catch-all for everything. The path that is not
        // proxied is one outside /api entirely, which the relay never sees.
        assertThat(LAST.get().getRequestURI().getPath()).isEqualTo("/api/v1/something-nobody-declared");
    }
}
