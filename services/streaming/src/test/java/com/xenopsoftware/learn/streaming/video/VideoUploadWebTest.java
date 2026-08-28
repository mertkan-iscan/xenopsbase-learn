package com.xenopsoftware.learn.streaming.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.streaming.PostgresTestHarness;
import com.xenopsoftware.learn.streaming.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The upload endpoints through the real filter chain (T-3.2), plus this service's version of
 * the closed-by-default walker: every endpoint is consciously accounted for, and no endpoint
 * accepts bytes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"streaming.upload.max-size-bytes=1000", "streaming.upload.tenant-quota-bytes=2500"})
@Import(StubTokens.class)
class VideoUploadWebTest extends PostgresTestHarness {

    /**
     * Until services can hold grants (T-2.2/T-2.3) and check permissions across modules,
     * streaming's endpoints are authentication-only, each with the reason on record — the same
     * discipline as identity's CatalogCoverageTest, sized to this service.
     */
    private static final Map<String, String> AUTH_ONLY = Map.of(
        "VideoResource#create",
            "authoring permission (video:upload) arrives with cross-service grants; until then any tenant member",
        "VideoResource#reissue",
            "same authorization story as create, on the same asset",
        "VideoResource#video",
            "read gating arrives with the playback entitlement work (T-3.4)");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private Environment environment;

    @Autowired
    private javax.sql.DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * The harness's container is one Postgres shared by every test class in the module, so rows
     * another class left behind are rows this class starts with — and against a deliberately
     * tiny test quota that reads as a 409 on the first upload, in a test that never mentions
     * quota. Cheap to prevent, confusing to diagnose.
     */
    @org.junit.jupiter.api.BeforeEach
    void emptyTheTable() {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("DELETE FROM video_asset");
    }

    @Test
    void anAuthorGetsATargetBoundToANewAsset() throws Exception {
        HttpResponse<String> response = postJson("/api/v1/videos",
            "{\"maxDurationSeconds\": 3600, \"sizeBytes\": 900}", "acme-author~acme~TENANT");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .contains("\"state\":\"PENDING_UPLOAD\"")
            .contains("fake-media.invalid");
    }

    @Test
    void anotherTenantCannotSeeTheAssetAtAll() throws Exception {
        String id = idFrom(postJson("/api/v1/videos",
            "{\"maxDurationSeconds\": 3600, \"sizeBytes\": 900}", "acme-author~acme~TENANT").body());

        assertThat(get("/api/v1/videos/" + id, "globex-author~globex~TENANT").statusCode())
            .isEqualTo(404);
        assertThat(get("/api/v1/videos/" + id, "acme-author~acme~TENANT").statusCode())
            .isEqualTo(200);
    }

    @Test
    void limitsAnswerBeforeATargetIsIssued() throws Exception {
        assertThat(postJson("/api/v1/videos",
            "{\"maxDurationSeconds\": 3600, \"sizeBytes\": 99999}", "acme-author~acme~TENANT")
            .statusCode()).isEqualTo(413);
    }

    @Test
    void unauthenticatedGetsNothing() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/videos"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"maxDurationSeconds\":1,\"sizeBytes\":1}"))
            .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
            .isEqualTo(401);
    }

    @Test
    void aMultipartUploadHasNoDoorToKnockOn() throws Exception {
        // The convenience someone will one day propose, sent as a request: multipart is
        // disabled service-wide and no endpoint consumes it, so the answer is a client error,
        // never an accepted byte.
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/videos"))
            .header("Authorization", "Bearer acme-author~acme~TENANT")
            .header("Content-Type", "multipart/form-data; boundary=x")
            .POST(HttpRequest.BodyPublishers.ofString("--x\r\nContent-Disposition: form-data; "
                + "name=\"file\"; filename=\"movie.mp4\"\r\n\r\nBYTES\r\n--x--"))
            .build();

        int status = http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        assertThat(status).isBetween(400, 499);
    }

    @Test
    void everyEndpointIsConsciouslyAccountedFor() {
        Set<String> unaccounted = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            boolean api = mapping.getPathPatternsCondition() != null
                && mapping.getPathPatternsCondition().getPatternValues().stream()
                    .anyMatch(p -> p.startsWith("/api/"));
            if (api) {
                String endpoint = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
                if (!AUTH_ONLY.containsKey(endpoint)) {
                    unaccounted.add(endpoint);
                }
            }
        });
        assertThat(unaccounted)
            .as("endpoints that decided their authorization by omission -- list each in "
                + "AUTH_ONLY with its reason, or bring the permission machinery here")
            .isEmpty();
    }

    private HttpResponse<String> postJson(String path, String body, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static String idFrom(String body) {
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }
}
