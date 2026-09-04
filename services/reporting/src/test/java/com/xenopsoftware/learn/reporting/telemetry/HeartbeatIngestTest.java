package com.xenopsoftware.learn.reporting.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.reporting.PostgresTestHarness;
import com.xenopsoftware.learn.reporting.StubTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The heartbeat ingest path (T-3.6).
 *
 * <p>Two things are being held here, and only one of them is "it stores the rows". The other is
 * the property the whole design exists for: <b>losing a heartbeat is acceptable and losing all of
 * them silently is not.</b> Every refusal is a named status the client can act on and a counter an
 * operator can alert on, because the failure mode that matters is a compliance report filling with
 * zeros while every dashboard stays green.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class HeartbeatIngestTest extends PostgresTestHarness {

    private static final String LEARNER = "acme-learner~acme~TENANT";

    /**
     * Endpoint → why authentication alone is the whole check, in this service's version of the
     * closed-by-default walker (the same discipline as identity's CatalogCoverageTest).
     */
    private static final Map<String, String> AUTH_ONLY = Map.of(
        "ServiceChainResource#whoami",
            "shared by every service (T-9.11): reports what the caller's own token says and "
            + "which service carried it here, and nothing about anybody else",
        "TelemetryResource#playback",
            "a learner posting what they themselves watched needs no grant -- the token is the "
            + "whole credential, and the subject is taken from it rather than from the body, so "
            + "there is no wider version of this endpoint to gate (T-3.6)");

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTable() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM playback_heartbeat");
    }

    @Test
    void aBatchIsAcceptedAndEverySampleIsKept() throws Exception {
        HttpResponse<String> response = post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 1.0, "observedAt": "%s"},
            {"fromSecond": 10, "toSecond": 20, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now(), Instant.now())));

        // 202 and not 200: accepted means written down, not "counted towards your progress".
        // Merging into watched intervals is T-3.7's, and a 200 would invite a client to read the
        // response as the completion confirmation ADR-0107 refuses to let a client make.
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"samples\":2");
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void theSubjectComesFromTheTokenAndNotFromTheBody() throws Exception {
        post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now())));

        // The one property that makes this endpoint safe without a permission check: a caller
        // writes heartbeats for themselves and there is no field in the request that could say
        // otherwise.
        assertThat(jdbc.queryForObject("SELECT subject FROM playback_heartbeat", String.class))
            .isEqualTo("sub-acme-learner");
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM playback_heartbeat", String.class))
            .isEqualTo("acme");
    }

    @Test
    void anotherCompanysHeartbeatsAreItsOwn() throws Exception {
        post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now())));
        post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now())), "globex-learner~globex~TENANT");

        assertThat(jdbc.queryForList("SELECT DISTINCT tenant_id FROM playback_heartbeat ORDER BY 1",
            String.class)).containsExactly("acme", "globex");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aBatchLargerThanOnePostMayCarryIsRefusedWithSomethingTheClientCanActOn() throws Exception {
        String samples = java.util.stream.IntStream.range(0, 61)
            .mapToObj(n -> """
                {"fromSecond": %d, "toSecond": %d, "rate": 1.0, "observedAt": "%s"}"""
                .formatted(n * 10, n * 10 + 10, Instant.now()))
            .collect(java.util.stream.Collectors.joining(","));

        HttpResponse<String> response = post(batch(samples));

        // 413 and not 400: "split this and resend" is a different instruction to "stop sending
        // this", and a client that cannot tell them apart retries both forever.
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("BATCH_TOO_LARGE");
        assertThat(rowCount()).isZero();
    }

    @Test
    void anIntervalThatIsNotAnIntervalIsRefused() throws Exception {
        HttpResponse<String> response = post(batch("""
            {"fromSecond": 30, "toSecond": 10, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now())));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("MALFORMED_INTERVAL");
        assertThat(rowCount()).isZero();
    }

    @Test
    void aSampleClaimingMoreVideoThanASampleCouldCoverIsRefused() throws Exception {
        // Not a rate check -- that is T-3.7's, and it needs the wall clock. This is the cruder
        // bound that makes the work per request finite: one sample cannot claim an hour.
        HttpResponse<String> response = post(batch("""
            {"fromSecond": 0, "toSecond": 3600, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now())));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("MALFORMED_INTERVAL");
    }

    @Test
    void aRateNoPlayerOffersIsRefused() throws Exception {
        HttpResponse<String> response = post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 64.0, "observedAt": "%s"}
            """.formatted(Instant.now())));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("IMPLAUSIBLE_RATE");
    }

    @Test
    void anEmptyBatchIsRefusedRatherThanQuietlyAccepted() throws Exception {
        HttpResponse<String> response = post(batch(""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("EMPTY_BATCH");
    }

    @Test
    void unreadableJsonAnswersTheClientRatherThanAStackTrace() throws Exception {
        HttpResponse<String> response = postRaw("{not json at all", LEARNER);

        // Never a 500. A 500 says "unknown, try again" about a batch that will never be accepted,
        // and one broken player then becomes sustained load nobody can see the cause of.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("MALFORMED_BATCH").doesNotContain("Exception");
    }

    @Test
    void aBatchWithNoTokenIsRefusedBecauseNothingCouldAttributeIt() throws Exception {
        HttpResponse<String> response = postRaw("""
            {"nodeId": "11111111-1111-4111-8111-111111111111", "samples": [
              {"fromSecond": 0, "toSecond": 10, "rate": 1.0, "observedAt": "%s"}]}
            """.formatted(Instant.now()), LEARNER);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("MISSING_ATTRIBUTION");
    }

    @Test
    void unauthenticatedGetsNothing() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/telemetry/playback"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
            .isEqualTo(401);
    }

    // ------------------------------------------------------------------ metrics

    @Test
    void whatIngestIsDoingIsCounted() throws Exception {
        post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now().minus(Duration.ofMinutes(3)))));
        post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 99.0, "observedAt": "%s"}
            """.formatted(Instant.now())));

        assertThat(meters.get("telemetry.playback.batches.accepted").counter().count()).isEqualTo(1);
        assertThat(meters.get("telemetry.playback.samples.accepted").counter().count()).isEqualTo(1);
        // Tagged by reason rather than counted as one number: a spike in malformed intervals is a
        // player release and a spike in oversized batches is a network outage refilling buffers,
        // and a single "rejections" number would show both as the same event.
        assertThat(meters.get("telemetry.playback.batches.rejected")
            .tag("reason", "IMPLAUSIBLE_RATE").counter().count()).isEqualTo(1);
    }

    @Test
    void lagIsMeasuredFromTheOldestSampleInTheBatch() throws Exception {
        // The newest sample is always about ten seconds old by construction, so measuring from it
        // would report a healthy number during exactly the incident this metric exists to show: a
        // client draining a backlog after being offline.
        post(batch("""
            {"fromSecond": 0, "toSecond": 10, "rate": 1.0, "observedAt": "%s"},
            {"fromSecond": 10, "toSecond": 20, "rate": 1.0, "observedAt": "%s"}
            """.formatted(Instant.now().minus(Duration.ofMinutes(9)), Instant.now())));

        assertThat(meters.get("telemetry.playback.lag").summary().max())
            .as("nine minutes behind, not ten seconds")
            .isGreaterThan(Duration.ofMinutes(8).toSeconds());
    }

    // ------------------------------------------------------------------ the walker

    @Test
    void everyEndpointIsConsciouslyAccountedFor() {
        Set<String> unaccounted = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            boolean api = mapping.getPathPatternsCondition() != null
                && mapping.getPathPatternsCondition().getPatternValues().stream()
                    .anyMatch(path -> path.startsWith("/api/"));
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

    // ------------------------------------------------------------------ helpers

    private static String batch(String samples) {
        return """
            {"nodeId": "11111111-1111-4111-8111-111111111111",
             "playbackToken": "fake-token.ref.sub.1",
             "samples": [%s]}
            """.formatted(samples);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return postRaw(body, LEARNER);
    }

    private HttpResponse<String> post(String body, String token) throws Exception {
        return postRaw(body, token);
    }
    private HttpResponse<String> postRaw(String body, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/telemetry/playback"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM playback_heartbeat", Integer.class);
        return count == null ? 0 : count;
    }
}
