package com.xenopsoftware.learn.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * The user endpoints through the real filter chain (T-1.2) — including the error path, which is
 * the part MockMvc would have waved through: a controller's 404 travels through the container's
 * ERROR dispatch, and the first version of this service turned that into a bare 403 with
 * {@code WWW-Authenticate: insufficient_scope} because {@code denyAll()} covered {@code /error}.
 * Found against the running stack, pinned here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class UserResourceWebTest extends PostgresTestHarness {

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void meProvisionsOnceAndAnswersTheSameIdForever() throws Exception {
        HttpResponse<String> first = get("/api/v1/me", "web-casey~acme~TENANT");
        HttpResponse<String> second = get("/api/v1/me", "web-casey~acme~TENANT");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"tenant\":\"acme\"").contains("web-casey@acme.test");
        assertThat(idFrom(second.body())).isEqualTo(idFrom(first.body()));
    }

    @Test
    void anotherTenantsUserIs404NotForbiddenAndNotAHint() throws Exception {
        String acmeId = idFrom(get("/api/v1/me", "web-jordan~acme~TENANT").body());

        HttpResponse<String> crossTenant = get("/api/v1/users/" + acmeId, "web-rival~globex~TENANT");

        // 404, exactly as ADR-0102's isolation sentence promises: not 403, which would confirm
        // the id exists somewhere, and not the insufficient_scope 403 the swallowed error
        // dispatch used to produce.
        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(crossTenant.headers().firstValue("WWW-Authenticate")).isEmpty();

        HttpResponse<String> sameTenant = get("/api/v1/users/" + acmeId, "web-jordan~acme~TENANT");
        assertThat(sameTenant.statusCode()).isEqualTo(200);
        assertThat(sameTenant.body()).contains(acmeId);
    }

    @Test
    void anUnknownIdIs404ThroughTheErrorDispatch() throws Exception {
        HttpResponse<String> response =
            get("/api/v1/users/00000000-0000-4000-8000-0000000000ff", "web-casey~acme~TENANT");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void platformStaffAreNotAppUsers() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", "web-ops~~PLATFORM");
        assertThat(response.statusCode()).isEqualTo(403);
        // The deliberate 403 from the controller, not the accidental one from the filter chain.
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + environment.getProperty("local.server.port") + path))
            .header("Authorization", "Bearer " + token)
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String idFrom(String body) {
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }
}
