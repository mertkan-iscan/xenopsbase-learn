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
 * A blocking interstitial, enforced by arithmetic rather than by a pause (T-5.4).
 *
 * <p>T-5.4's second criterion is the one these tests exist for: the pause is enforced "by the same
 * interval accounting that measures progress, not by client honesty". So every test here drives a
 * player that <b>does not stop</b> — it plays straight through a marker at 300s, exactly as a
 * browser with the pause code deleted would — and asserts on what the server credited. A test with
 * a well-behaved player could not tell this design from one that asks nicely.
 *
 * <p>Over HTTP and against the stub clock, for the reasons {@link ProgressTest} gives: the status
 * and the code are the contract with the player, and every assertion here is about video seconds
 * against wall-clock seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubTokens.class, PlaybackTestBeans.class})
class InterstitialFrontierTest extends PostgresTestHarness {

    private static final String LEARNER = "acme-learner~acme~TENANT";

    /** Ten minutes of video, with a question five minutes in. */
    private static final int EXTENT_SECONDS = 600;
    private static final int MARKER = 300;

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
    void aTenMinuteVideoWithAQuestionInTheMiddle() {
        jdbc = new JdbcTemplate(dataSource);
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
        held(MARKER);
    }

    /**
     * THE TEST THIS TASK EXISTS FOR: playing straight through the question completes nothing.
     *
     * <p>The player never pauses. It reports honest, continuous, correctly paced playback of the
     * whole ten minutes — the batches an obedient player would send if the interstitial code had
     * simply been deleted — and the server credits five minutes of it, because the other five are
     * on the far side of a question nobody answered.
     */
    @Test
    void playingThroughAnUnansweredQuestionCreditsNothingPastIt() throws Exception {
        watchStraightThrough();

        assertThat(coveredOf(node))
            .as("the frontier is where coverage stops, whatever the player claimed")
            .isEqualTo("{[0," + MARKER + ")}");
        assertThat(number(get(node).body(), "coveredSeconds")).isEqualTo(MARKER);
        assertThat(get(node).body())
            .as("half a video is not ninety per cent of one, so nothing completed")
            .contains("\"completed\":false");
        assertThat(completions()).isEmpty();
    }

    /**
     * The honest player's heartbeat straddles the marker, and must not be told it did wrong.
     *
     * <p>[290, 301) against a marker at 300 is what a correct player produces when it pauses on the
     * right second: the heartbeat window does not align with the marker and never will. Refusing
     * that would make every conforming player look broken exactly once per interstitial.
     */
    @Test
    void aHeartbeatThatStraddlesTheMarkerIsClippedRatherThanRefused() throws Exception {
        for (int second = 0; second < 290; second += 10) {
            post(node, batch("session-1", second, second + 10));
            clock.advance(Duration.ofSeconds(10));
        }

        HttpResponse<String> straddling = post(node, batch("session-1", 290, 301));

        assertThat(straddling.statusCode())
            .as("the honest case answers 200; only a batch wholly past the marker is a conflict")
            .isEqualTo(200);
        assertThat(coveredOf(node)).isEqualTo("{[0," + MARKER + ")}");
        assertThat(refusals()).isEmpty();
    }

    /**
     * A batch entirely past the marker gets a code the player can act on.
     *
     * <p>There is nothing to clip and nothing honest to explain it, so a silent zero would leave a
     * learner watching a video that credits them nothing with no way to find out why. The refusal
     * is also written to the learner's own row, because "why is my progress stuck" is a support
     * question about one person.
     */
    @Test
    void aBatchWhollyPastTheMarkerIsRefusedAndCounted() throws Exception {
        for (int second = 0; second < MARKER; second += 10) {
            post(node, batch("session-1", second, second + 10));
            clock.advance(Duration.ofSeconds(10));
        }

        HttpResponse<String> past = post(node, batch("session-1", 320, 330));

        assertThat(past.statusCode())
            .as("a conflict, not a bad request: the batch is well formed and the player was told "
                + "where the frontier was")
            .isEqualTo(409);
        assertThat(past.body()).contains("INTERSTITIAL_UNANSWERED");
        assertThat(refusals()).containsExactly("INTERSTITIAL_UNANSWERED");
        assertThat(coveredOf(node)).isEqualTo("{[0," + MARKER + ")}");
    }

    /**
     * T-5.4's third criterion: reloading during an interstitial returns to it, not past it.
     *
     * <p>The learner's furthest second really is past the marker — they seeked there, or their
     * player played on — and resuming them there would hand them a video that credits them nothing
     * with no sign of why. So the resume point is clipped to the frontier, and the frontier travels
     * with it so the player knows what to render on arrival.
     */
    @Test
    void reloadingDuringTheQuestionReturnsToItRatherThanPastIt() throws Exception {
        watchStraightThrough();

        HttpResponse<String> onLoad = get(node);

        assertThat(number(onLoad.body(), "resumeSecond"))
            .as("back to the question, not to where the player got to")
            .isEqualTo(MARKER);
        assertThat(number(onLoad.body(), "blockedAfterSecond"))
            .as("and told why, so it renders the question instead of an unexplained stop")
            .isEqualTo(MARKER);
    }

    /**
     * T-5.4's fourth criterion, the forward half: seeking past the position triggers it.
     *
     * <p>Seeking is allowed on this item — the learner may drag the scrubber to 400 and the player
     * will happily show it to them. What they may not do is be credited for it, and the frontier
     * they are handed on the next read is still the marker they skipped.
     */
    @Test
    void seekingPastTheQuestionStillLeavesItInTheWay() throws Exception {
        HttpResponse<String> skipped = post(node, batch("session-1", 400, 410));

        assertThat(skipped.statusCode()).isEqualTo(409);
        assertThat(skipped.body()).contains("INTERSTITIAL_UNANSWERED");
        assertThat(number(get(node).body(), "blockedAfterSecond")).isEqualTo(MARKER);
        assertThat(number(get(node).body(), "resumeSecond")).isZero();
    }

    /**
     * Answering it moves the frontier, and the rest of the video counts again.
     *
     * <p>Catalog decides that an answer happened — this test moves the stub, which is what catalog
     * moving its own frontier looks like from here (T-5.4's projection is fed by an event from
     * assessment). What is asserted is that streaming notices without being restarted, without an
     * hour passing, and without the learner posting anything special: the next heartbeat that would
     * cross the old value re-reads it, which is the once-per-interstitial hop the design pays for.
     */
    @Test
    void answeringItLetsTheRestOfTheVideoCountAndTheItemComplete() throws Exception {
        watchStraightThrough();
        assertThat(coveredOf(node)).isEqualTo("{[0," + MARKER + ")}");

        nothingInTheWay();

        for (int second = MARKER; second < 540; second += 10) {
            assertThat(post(node, batch("session-2", second, second + 10)).statusCode())
                .isEqualTo(200);
            clock.advance(Duration.ofSeconds(10));
        }

        assertThat(coveredOf(node)).isEqualTo("{[0,540)}");
        HttpResponse<String> state = get(node);
        assertThat(state.body()).contains("\"completed\":true");
        assertThat(number(state.body(), "blockedAfterSecond"))
            .as("nothing is in the way any more, and the player is told that too")
            .isNull();
        assertThat(completions()).hasSize(1);
    }

    /**
     * A learner who has never opened the node is still told what is in the way.
     *
     * <p>There is no row yet and this read creates none (T-3.7), so the frontier has to come
     * straight from the entitlement — otherwise the very first thing a player does, before any
     * heartbeat, would be to render a video with no idea a question is coming.
     */
    @Test
    void aPlayerIsToldAboutTheQuestionBeforeItHasPlayedAnything() throws Exception {
        HttpResponse<String> fresh = get(node);

        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(number(fresh.body(), "blockedAfterSecond")).isEqualTo(MARKER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_node_progress", Integer.class))
            .as("a read still writes nothing")
            .isZero();
    }

    // ---------------------------------------------------------------- helpers

    /** A player with the pause code deleted: continuous, honestly paced, straight through. */
    private void watchStraightThrough() throws Exception {
        for (int second = 0; second < EXTENT_SECONDS; second += 10) {
            HttpResponse<String> response = post(node, batch("session-1", second, second + 10));
            assertThat(response.statusCode())
                .as("everything up to and across the marker is accepted; only batches wholly past "
                    + "it are refused, and the loop stops there")
                .isIn(200, 409);
            clock.advance(Duration.ofSeconds(10));
        }
    }

    /** Catalog says this learner is held at {@code second}. */
    private void held(int second) {
        catalog.put(new NodeEntitlement(node, asset, true, true, null, null, true, second));
    }

    /** Catalog says they have answered everything blocking. */
    private void nothingInTheWay() {
        catalog.put(new NodeEntitlement(node, asset, true, true, null, null, true, null));
    }

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
        return Map.of("playbackToken", session, "samples",
            List.of(Map.of("fromSecond", from, "toSecond", to, "rate", 1.0,
                "observedAt", "2026-09-04T09:00:00Z")));
    }

    private static String json(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) body.get("samples");
        StringBuilder out = new StringBuilder("{\"playbackToken\":\"")
            .append(body.get("playbackToken")).append("\",\"samples\":[");
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
            .compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
