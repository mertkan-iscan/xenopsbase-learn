package com.xenopsoftware.learn.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The three promises the gateway makes to a browser (T-10.2).
 *
 * <ol>
 *   <li>an API call with no session is a <b>401 with a code</b>, never a redirect to a login page;
 *   <li>the session cookie is <b>HttpOnly</b>, which is the half of "no token in the browser" that
 *       only a server can promise;
 *   <li>a write needs more than the cookie, because the cookie now travels on every request the
 *       browser makes from anywhere.
 * </ol>
 *
 * <h2>Why the client registration is built by hand here</h2>
 *
 * <p>{@code issuer-uri} makes Spring fetch the provider's discovery document <b>at startup</b>, so
 * a context that names one cannot start without a live Keycloak. Blanking the property does not
 * help — an empty issuer is an issuer, and Boot refuses it. Supplying the repository as a bean
 * makes the auto-configuration back off entirely, and none of the endpoints below is ever called:
 * nothing here signs in, it asserts what happens to somebody who has not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewaySessionTest.AProviderNobodyHasToStart.class)
class GatewaySessionTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class AProviderNobodyHasToStart {

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

    /** A real Valkey: the sessions live in it, so a fake would be testing the fake. */
    private static final GenericContainer<?> VALKEY =
        new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired
    private Environment environment;

    @Autowired
    private ServletContext servletContext;

    private final HttpClient http = HttpClient.newBuilder()
        // Never follow: the entire assertion below is about what the SERVER answered, and a client
        // that follows a 302 to a login page would report the login page's 200.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Test
    void anApiCallWithNoSessionIsRefusedRatherThanRedirected() throws Exception {
        HttpResponse<String> refused = get("/api/v1/me");

        assertThat(refused.statusCode())
            .as("fetch follows redirects silently, so a 302 here would hand the SPA a login page "
                + "with a 200 on it and look exactly like the API answering")
            .isEqualTo(401);
        assertThat(refused.headers().firstValue("content-type").orElse(""))
            .contains("application/problem+json");
        assertThat(refused.body())
            .as("the code is what lets the frontend tell 'sign in again' from 'not yours to do', "
                + "and that distinction decides whether a half-finished exam is kept")
            .contains("SESSION_ENDED");
    }

    @Test
    void whoIsHereCanBeAskedWithoutBeingSignedIn() throws Exception {
        HttpResponse<String> answer = get("/auth/session");

        assertThat(answer.statusCode())
            .as("a 401 here would make 'are you signed in' a question nobody can ask")
            .isEqualTo(200);
        assertThat(answer.body()).contains("\"signedIn\":false");
        assertThat(answer.headers().allValues("set-cookie").toString())
            .as("and it is what issues the CSRF cookie, which is why the app calls it first")
            .contains("XSRF-TOKEN");
    }

    /**
     * The half of "no token in the browser" that only the server can promise.
     *
     * <p>A script in an embedded frame can read every cookie that is not HttpOnly, and the
     * frontend's own test asserts nothing token-shaped is in storage. This is the other side: the
     * credential that IS in the browser is one no script can see.
     */
    @Test
    void theSessionCookieIsUnreadableByScript() {
        assertThat(servletContext.getSessionCookieConfig().isHttpOnly()).isTrue();
        assertThat(servletContext.getSessionCookieConfig().getName()).isEqualTo("LEARNSESSION");
        // SameSite is applied to the response by Boot rather than stored on the servlet config,
        // so the configured value is the thing to assert.
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site"))
            .as("Lax, not Strict: Strict drops the cookie on the way back from Keycloak and turns "
                + "every sign-in into a loop that looks like a broken identity provider")
            .isEqualTo("lax");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
