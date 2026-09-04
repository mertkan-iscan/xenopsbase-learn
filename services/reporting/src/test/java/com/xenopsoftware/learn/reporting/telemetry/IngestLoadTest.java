package com.xenopsoftware.learn.reporting.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.reporting.PostgresTestHarness;
import com.xenopsoftware.learn.reporting.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The measurement ADR-0107 says it owes (T-3.6).
 *
 * <p>The ADR states ~500 posts/second at 5,000 concurrent learners and marks it <b>estimated, not
 * measured</b>. This replaces the estimate for the ingest path with a number taken from a run, and
 * {@code docs/slos.md} records what the number does and does not mean.
 *
 * <p><b>What this is not:</b> a capacity statement about production. It runs against a
 * Testcontainers Postgres on a developer machine, with the load generator on the same machine
 * competing for the same cores — so it measures a floor, not a ceiling, and the honest use of the
 * result is "the design is not obviously wrong at this rate" rather than "the platform supports
 * N learners". ADR-0109 already had to learn the difference between a figure derived from
 * configuration and one taken from a running system; this is the second kind, on the wrong
 * hardware.
 *
 * <p>Tagged so it does not run in the ordinary build: it takes seconds rather than milliseconds
 * and its result is a number to read, not an assertion to pass. Run it with
 * {@code -Dgroups=load}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
@Tag("load")
class IngestLoadTest extends PostgresTestHarness {

    /** ADR-0107's figure: one batched post per learner per ten seconds. */
    private static final int CONCURRENT_LEARNERS = 5_000;
    private static final int SAMPLES_PER_BATCH = 10;
    private static final Duration HEARTBEAT_PERIOD = Duration.ofSeconds(10);

    /**
     * The load is PACED, not fired at once, and the difference is the whole point.
     *
     * <p>Five thousand concurrent learners each posting once per ten seconds is 500 posts/second
     * arriving continuously — not 5,000 arriving in the same instant. The first version of this
     * test did the latter and got connection refusals from the accept queue, which measured the
     * shape of a thundering herd nobody is claiming to support and said nothing about the load
     * that was actually specified.
     */
    private static final int POSTS_PER_SECOND = CONCURRENT_LEARNERS
        / (int) HEARTBEAT_PERIOD.toSeconds();

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void fiveThousandLearnersWorthOfHeartbeatsInOneTenSecondWindow() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM playback_heartbeat");

        HttpClient http = HttpClient.newBuilder()
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();
        URI endpoint = URI.create("http://localhost:"
            + environment.getProperty("local.server.port") + "/api/v1/telemetry/playback");

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Long> latenciesMicros = java.util.Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> inFlight = new ArrayList<>(CONCURRENT_LEARNERS);

        long periodNanos = Duration.ofSeconds(1).toNanos() / POSTS_PER_SECOND;
        Instant started = Instant.now();
        long nextAt = System.nanoTime();

        for (int learner = 0; learner < CONCURRENT_LEARNERS; learner++) {
            // Paced by a deadline rather than a sleep-per-request, so the generator's own
            // scheduling jitter does not accumulate into a slower offered rate than intended.
            nextAt += periodNanos;
            long waitFor = nextAt - System.nanoTime();
            if (waitFor > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(waitFor);
            }

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer learner-" + learner + "~acme~TENANT")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(batch()))
                .build();
            long sentAt = System.nanoTime();
            inFlight.add(http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    latenciesMicros.add((System.nanoTime() - sentAt) / 1_000);
                    if (response.statusCode() == 202) {
                        accepted.incrementAndGet();
                    } else {
                        refused.incrementAndGet();
                    }
                }));
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        Duration elapsed = Duration.between(started, Instant.now());

        long rows = jdbc.queryForObject("SELECT count(*) FROM playback_heartbeat", Long.class);
        double postsPerSecond = CONCURRENT_LEARNERS / (elapsed.toMillis() / 1000d);
        List<Long> sorted = latenciesMicros.stream().sorted().toList();

        System.out.printf("""

            === T-3.6 ingest load ================================================
            db pool          %d connections
            offered          %,d posts/second for %s (%,d learners x %d samples)
            accepted         %,d of %,d, %,d refused
            samples written  %,d
            elapsed          %s
            achieved         %.0f posts/second (%.0f samples/second)
            latency p50      %.1f ms
            latency p95      %.1f ms
            latency p99      %.1f ms
            latency max      %.1f ms
            ======================================================================
            %n""", poolSize(), POSTS_PER_SECOND, HEARTBEAT_PERIOD, CONCURRENT_LEARNERS, SAMPLES_PER_BATCH,
            accepted.get(), CONCURRENT_LEARNERS, refused.get(), rows, elapsed,
            postsPerSecond, postsPerSecond * SAMPLES_PER_BATCH,
            percentile(sorted, 0.50), percentile(sorted, 0.95),
            percentile(sorted, 0.99), percentile(sorted, 1.0));

        // Nothing may be dropped silently -- the property the whole task exists for. Throughput
        // is printed rather than asserted: a threshold on a developer machine would be a test
        // that fails when somebody else opens a browser.
        assertThat(refused.get()).as("every post accepted, none shed or errored").isZero();
        assertThat(rows).isEqualTo((long) CONCURRENT_LEARNERS * SAMPLES_PER_BATCH);
    }

    /**
     * The pool the run actually used, printed with the figures.
     *
     * <p>Not decoration: the connection pool turned out to be the thing that decides median
     * latency here, and a throughput number recorded without it is a number nobody can reproduce
     * or compare against.
     */
    private int poolSize() {
        return dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari
            ? hikari.getMaximumPoolSize() : -1;
    }

    private static double percentile(List<Long> sortedMicros, double at) {
        if (sortedMicros.isEmpty()) {
            return 0;
        }
        int index = Math.min(sortedMicros.size() - 1,
            (int) Math.round(at * (sortedMicros.size() - 1)));
        return sortedMicros.get(index) / 1000d;
    }

    private static String batch() {
        StringBuilder samples = new StringBuilder();
        Instant now = Instant.now();
        for (int n = 0; n < SAMPLES_PER_BATCH; n++) {
            if (n > 0) {
                samples.append(',');
            }
            samples.append("""
                {"fromSecond": %d, "toSecond": %d, "rate": 1.0, "observedAt": "%s"}"""
                .formatted(n * 10, n * 10 + 10, now));
        }
        return """
            {"nodeId": "11111111-1111-4111-8111-111111111111",
             "playbackToken": "fake-token.ref.sub.1",
             "samples": [%s]}
            """.formatted(samples);
    }
}
