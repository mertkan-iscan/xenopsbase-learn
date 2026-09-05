package com.xenopsoftware.learn.streaming.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.streaming.PostgresTestHarness;
import com.xenopsoftware.learn.streaming.StubTokens;
import com.xenopsoftware.learn.streaming.playback.MutableClock;
import com.xenopsoftware.learn.streaming.playback.NodeEntitlement;
import com.xenopsoftware.learn.streaming.playback.PlaybackTestBeans;
import com.xenopsoftware.learn.streaming.playback.StubEntitlement;
import com.xenopsoftware.learn.streaming.playback.StubViewerDirectory;
import com.xenopsoftware.learn.streaming.playback.StubViewerPermissions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a learner is credited with, end to end (T-3.7, ADR-0107).
 *
 * <p>Over HTTP rather than against the service, because half of what this task promises is a
 * <em>refusal a client can act on</em>: the status and the code are the contract with the player,
 * and a test that called the method would prove the merge and none of that.
 *
 * <p>Time is the stub clock. Every one of these assertions is about the relationship between video
 * seconds and wall-clock seconds, and a test that used the real clock could only assert the parts
 * that do not matter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubTokens.class, PlaybackTestBeans.class})
class ProgressTest extends PostgresTestHarness {

    private static final String LEARNER = "acme-learner~acme~TENANT";

    /** Ten minutes of video: long enough that 90% of it is a number worth crossing. */
    private static final int EXTENT_SECONDS = 600;

    @Autowired
    private StubEntitlement catalog;
    @Autowired
    private StubViewerPermissions permissions;
    @Autowired
    private StubViewerDirectory directory;
    @Autowired
    private ViewerIdentities identities;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;
    private UUID node;
    private UUID asset;

    @BeforeEach
    void aLearnerInFrontOfATenMinuteVideo() {
        jdbc = new JdbcTemplate(dataSource);
        // One container per module, so start from a known table rather than from whatever
        // another class left behind (the mystery-409 lesson from T-3.2).
        jdbc.update("DELETE FROM progress_refusal");
        jdbc.update("DELETE FROM learner_node_progress");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM playback_refusal");
        jdbc.update("DELETE FROM video_asset");
        clock.reset();
        permissions.allow(true);
        directory.resolvesTo(StubViewerDirectory.LEARNER_ID);
        identities.forget();
        catalog.clear();
        node = UUID.randomUUID();
        asset = readyVideo("acme", Duration.ofSeconds(EXTENT_SECONDS));
        catalog.put(new NodeEntitlement(node, asset, true, true, null));
    }

    // ---------------------------------------------------------------- watching it

    @Test
    void watchingItThroughCompletesItAtTheThreshold() throws Exception {
        HttpResponse<String> lastResponse = null;
        for (int second = 0; second < 540; second += 10) {
            lastResponse = post(node, batch("session-1", second, second + 10));
            assertThat(lastResponse.statusCode()).isEqualTo(200);
            // The wall clock moves with the video, which is what an honest player produces.
            clock.advance(Duration.ofSeconds(10));
        }

        assertThat(number(lastResponse.body(), "coveredSeconds")).isEqualTo(540);
        assertThat(number(lastResponse.body(), "percent")).isEqualTo(90);
        assertThat(lastResponse.body())
            .as("ninety per cent of the extent is the threshold, and the server said so")
            .contains("\"completed\":true")
            .contains("\"completionSource\":\"DERIVED\"");
        assertThat(number(lastResponse.body(), "fragments"))
            .as("continuous playback is one run however many heartbeats carried it")
            .isEqualTo(1);
        assertThat(completions())
            .as("and the completion is announced once, in the transaction that derived it")
            .hasSize(1);
    }

    /**
     * THE TEST THIS TASK EXISTS FOR: dragging the scrubber to the end completes nothing.
     *
     * <p>The seek itself is allowed here — this item permits it — and the learner really does end
     * up at the last second of the video. What they do not have is coverage, because a position is
     * a claim and only the seconds actually presented are a measurement (ADR-0107).
     */
    @Test
    void scrubbingToTheEndDoesNotCompleteAnything() throws Exception {
        post(node, batch("session-1", 0, 8));
        clock.advance(Duration.ofSeconds(10));
        // Straight to the end, then the few seconds a player would genuinely show there.
        HttpResponse<String> response = post(node, batch("session-1", 592, 600));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .as("sixteen seconds of a ten-minute video is not a completion, however far along "
                + "the scrubber is")
            .contains("\"completed\":false");
        assertThat(number(response.body(), "coveredSeconds")).isEqualTo(16);
        assertThat(number(response.body(), "percent")).isEqualTo(2);
        assertThat(number(response.body(), "resumeSecond"))
            .as("resume still goes where they are, which is a different question")
            .isEqualTo(600);
        assertThat(completions()).isEmpty();
    }

    @Test
    void aRepeatedOrReorderedBatchChangesNothing() throws Exception {
        List<Map<String, Object>> samples = List.of(
            sample(0, 10), sample(10, 20), sample(20, 30));
        post(node, Map.of("playbackToken", "session-1", "samples", samples));
        clock.advance(Duration.ofSeconds(30));

        HttpResponse<String> repeated = post(node,
            Map.of("playbackToken", "session-1", "samples", samples));
        List<Map<String, Object>> backwards = new ArrayList<>(samples);
        java.util.Collections.reverse(backwards);
        HttpResponse<String> reordered = post(node,
            Map.of("playbackToken", "session-1", "samples", backwards));

        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(reordered.statusCode()).isEqualTo(200);
        assertThat(number(reordered.body(), "coveredSeconds"))
            .as("a duplicate is not an error and not a credit -- the union already contains it")
            .isEqualTo(30);
        assertThat(coveredOf(node)).isEqualTo("{[0,30)}");
    }

    @Test
    void anItemMayDemandAllOfItselfRatherThanTheDefault() throws Exception {
        // The threshold is per item precisely so that an item which genuinely needs all of it can
        // say so. It travels on the entitlement answer, which is catalog's to give.
        catalog.put(new NodeEntitlement(node, asset, true, true, null, 100, true));

        for (int second = 0; second < 540; second += 60) {
            post(node, batch("session-1", second, second + 60));
            clock.advance(Duration.ofSeconds(60));
        }
        assertThat(post(node, batch("session-1", 540, 570)).body())
            .as("ninety-five per cent is not a completion of an item that asked for all of it")
            .contains("\"completed\":false")
            .contains("\"thresholdPercent\":100");
        clock.advance(Duration.ofSeconds(60));

        assertThat(post(node, batch("session-1", 570, 600)).body()).contains("\"completed\":true");
        assertThat(completions()).hasSize(1);
    }

    // ---------------------------------------------------------------- what it refuses

    @Test
    void moreContentThanWallClockAllowsIsRefusedAndCounted() throws Exception {
        // Ten minutes of video claimed seconds after the learner started. Everything about the
        // batch is well formed; it is only impossible.
        List<Map<String, Object>> wholeVideo = new ArrayList<>();
        for (int second = 0; second < 600; second += 60) {
            wholeVideo.add(sample(second, second + 60));
        }
        post(node, batch("session-1", 0, 5));
        clock.advance(Duration.ofSeconds(5));

        HttpResponse<String> response = post(node,
            Map.of("playbackToken", "session-1", "samples", wholeVideo));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("IMPLAUSIBLE_RATE");
        assertThat(refusals())
            .as("recorded where it can be read, because no meter in this repository is "
                + "scrapeable yet and 'why is my progress stuck' is a question about one person")
            .containsExactly("IMPLAUSIBLE_RATE");
        assertThat(coveredOf(node))
            .as("and nothing from the refused batch was credited")
            .isEqualTo("{[0,5)}");
    }

    @Test
    void theSameCoverageAtAWatchablePaceIsAccepted() throws Exception {
        // The same 600 seconds, claimed over 600 seconds of wall clock. This is the honest
        // learner the rate check must never refuse.
        for (int second = 0; second < 600; second += 60) {
            assertThat(post(node, batch("session-1", second, second + 60)).statusCode())
                .isEqualTo(200);
            clock.advance(Duration.ofSeconds(60));
        }
        assertThat(coveredOf(node)).isEqualTo("{[0,600)}");
    }

    @Test
    void anItemThatForbidsSkippingAheadRefusesCoveragePastWhatWasWatched() throws Exception {
        catalog.put(new NodeEntitlement(node, asset, true, true, null, null, false));

        assertThat(post(node, batch("session-1", 0, 30)).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> skipped = post(node, batch("session-1", 400, 410));

        assertThat(skipped.statusCode())
            .as("a conflict, not a bad request: the batch is well formed and the caller was "
                + "told this rule")
            .isEqualTo(409);
        assertThat(skipped.body()).contains("SEEK_NOT_ALLOWED");
        assertThat(refusals()).containsExactly("SEEK_NOT_ALLOWED");
        assertThat(coveredOf(node)).isEqualTo("{[0,30)}");

        // And the player is told the rule it is expected to enforce, with the ceiling on it.
        HttpResponse<String> state = get(node);
        assertThat(state.body()).contains("\"allowSeekForward\":false");
        assertThat(number(state.body(), "seekCeilingSecond")).isEqualTo(30);
    }

    @Test
    void continuingFromWhereTheLastHeartbeatStoppedIsNotASkip() throws Exception {
        catalog.put(new NodeEntitlement(node, asset, true, true, null, null, false));
        assertThat(post(node, batch("session-1", 0, 30)).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(10));
        // Two seconds after where they were: sampling error, not a seek. An honest player must
        // never be refused by the rule aimed at a dishonest one.
        assertThat(post(node, batch("session-1", 32, 40)).statusCode()).isEqualTo(200);
    }

    @Test
    void aMalformedOrOversizedBatchIsToldSoRatherThanRetriedForever() throws Exception {
        assertThat(post(node, Map.of("playbackToken", "session-1", "samples",
            List.of(sample(50, 20)))).statusCode()).isEqualTo(400);

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int index = 0; index < 61; index++) {
            tooMany.add(sample(index, index + 1));
        }
        HttpResponse<String> oversized = post(node,
            Map.of("playbackToken", "session-1", "samples", tooMany));
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(oversized.body()).contains("BATCH_TOO_LARGE");

        HttpResponse<String> unattributed = post(node,
            Map.of("playbackToken", "", "samples", List.of(sample(0, 10))));
        assertThat(unattributed.statusCode()).isEqualTo(400);
        assertThat(unattributed.body()).contains("MISSING_ATTRIBUTION");
    }

    @Test
    void progressForContentNobodyAssignedIsTheSameBare404AsPlaybackIs() throws Exception {
        catalog.put(new NodeEntitlement(node, asset, false, true, null));

        HttpResponse<String> response = post(node, batch("session-1", 0, 10));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertThat(jdbc.queryForList("SELECT reason FROM playback_refusal", String.class))
            .as("distinguishable in the audit and nowhere else, exactly as T-3.4 has it")
            .containsExactly("NOT_ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_node_progress", Integer.class))
            .isZero();
    }

    // ---------------------------------------------------------------- what it tells the player

    @Test
    void aPlayerLoadingTheNodeIsToldWhereToResume() throws Exception {
        HttpResponse<String> fresh = get(node);
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(number(fresh.body(), "resumeSecond"))
            .as("nothing watched, nothing to resume")
            .isZero();
        assertThat(fresh.body()).contains("\"allowSeekForward\":true");
        assertThat(number(fresh.body(), "extentSeconds")).isEqualTo(EXTENT_SECONDS);

        post(node, batch("session-1", 0, 120));
        clock.advance(Duration.ofSeconds(120));

        assertThat(number(get(node).body(), "resumeSecond"))
            .as("T-3.5 left the resume position open for this task; this is it")
            .isEqualTo(120);
    }

    @Test
    void aScrubbedRecordStaysBoundedAndSaysWhenItIsApproximate() throws Exception {
        // Sixty-five separate runs against a cap of sixty-four, at a pace wall clock allows.
        for (int index = 0; index < 65; index++) {
            assertThat(post(node, batch("session-1", index * 9, index * 9 + 5)).statusCode())
                .isEqualTo(200);
            clock.advance(Duration.ofSeconds(10));
        }
        HttpResponse<String> state = get(node);

        assertThat(number(state.body(), "fragments"))
            .as("the row cannot grow past the cap however much somebody scrubs")
            .isLessThanOrEqualTo(64);
        assertThat(state.body())
            .as("and it says so, because an approximate completion is still a completion and "
                + "somebody may have to explain it")
            .contains("\"approximate\":true");
    }

    // ---------------------------------------------------------------- helpers

    private HttpResponse<String> post(UUID nodeId, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(nodeId))
            .header("Authorization", "Bearer " + LEARNER)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json(body)))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(UUID nodeId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(nodeId))
            .header("Authorization", "Bearer " + LEARNER)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(UUID nodeId) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port")
            + "/api/v1/me/nodes/" + nodeId + "/progress");
    }

    private static Map<String, Object> batch(String session, int from, int to) {
        return Map.of("playbackToken", session, "samples", List.of(sample(from, to)));
    }

    private static Map<String, Object> sample(int from, int to) {
        return Map.of("fromSecond", from, "toSecond", to, "rate", 1.0,
            "observedAt", "2026-09-04T09:00:00Z");
    }

    /**
     * Hand-written JSON, because the point of posting over HTTP is to exercise the same body a
     * player sends — including the ones a serialiser would refuse to build, like an inverted
     * interval or a batch of sixty-one samples.
     */
    private static String json(Map<String, Object> body) {
        StringBuilder out = new StringBuilder("{");
        out.append("\"playbackToken\":\"").append(body.get("playbackToken")).append("\",");
        out.append("\"samples\":[");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) body.get("samples");
        for (int index = 0; index < samples.size(); index++) {
            Map<String, Object> sample = samples.get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"fromSecond\":").append(sample.get("fromSecond"))
                .append(",\"toSecond\":").append(sample.get("toSecond"))
                .append(",\"rate\":").append(sample.get("rate"))
                .append(",\"observedAt\":\"").append(sample.get("observedAt")).append("\"}");
        }
        return out.append("]}").toString();
    }

    private UUID readyVideo(String tenant, Duration duration) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO video_asset (id, tenant_id, provider, provider_ref, state,
                                     duration_seconds, size_bytes, max_duration_seconds,
                                     created_at, updated_at)
            VALUES (?, ?, 'fake', ?, 'READY', ?, 1024, 7200, now(), now())
            """, id, tenant, "ref-" + id, (double) duration.toSeconds());
        return id;
    }

    private String coveredOf(UUID nodeId) {
        return jdbc.queryForObject(
            "SELECT covered::text FROM learner_node_progress WHERE node_id = ?", String.class,
            nodeId);
    }

    private List<String> refusals() {
        return jdbc.queryForList(
            "SELECT reason FROM progress_refusal ORDER BY created_at", String.class);
    }

    private List<String> completions() {
        return jdbc.queryForList(
            "SELECT payload::text FROM outbox WHERE topic = 'streaming.node.completed'",
            String.class);
    }

    private static Integer number(String body, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + field + "\":(-?\\d+)").matcher(body);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
