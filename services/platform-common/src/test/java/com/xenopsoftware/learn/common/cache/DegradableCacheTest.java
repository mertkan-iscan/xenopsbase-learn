package com.xenopsoftware.learn.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rule this class exists for: a caller pays for an unreachable cache once per window, not
 * once per request (#126).
 */
class DegradableCacheTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-09T00:00:00Z"));
    private final DegradableCaches caches = new DegradableCaches(Duration.ofSeconds(30), clock, null);

    @Test
    void aCacheThatHasNotFailedIsConsulted() {
        assertThat(caches.register("permissions").quiet()).isFalse();
    }

    @Test
    void oneFailureStopsEveryCallerForTheWholeWindow() {
        DegradableCache cache = caches.register("permissions");

        cache.failed("read", new IllegalStateException("connection refused"));

        assertThat(cache.quiet()).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThat(cache.quiet()).isTrue();
    }

    @Test
    void theWindowCloses() {
        DegradableCache cache = caches.register("permissions");
        cache.failed("read", new IllegalStateException("connection refused"));

        clock.advance(Duration.ofSeconds(31));

        assertThat(cache.quiet()).isFalse();
    }

    @Test
    void theReportSaysWhatFailedAndWhenItWillBeTriedAgain() {
        DegradableCache cache = caches.register("permissions", Duration.ofSeconds(30),
            Map.of("schema", 1));
        cache.failed("read", new IllegalStateException("connection refused"));

        assertThat(cache.report())
            .containsEntry("mode", "degraded")
            .containsEntry("cooldown", "PT30S")
            .containsEntry("lastFailure", "read: IllegalStateException")
            .containsEntry("retriesAt", "2026-09-09T00:00:30Z")
            .containsEntry("schema", 1);
    }

    /**
     * A failure is remembered after the window closes. Two minutes of "ok" that follow a
     * connection refusal is a different thing to read than two minutes of nothing having
     * happened, and the second is what a report that forgot would show.
     */
    @Test
    void theFailureIsStillReportedOnceTheCacheIsBeingUsedAgain() {
        DegradableCache cache = caches.register("permissions");
        cache.failed("read", new IllegalStateException("connection refused"));

        clock.advance(Duration.ofSeconds(31));

        assertThat(cache.report())
            .containsEntry("mode", "ok")
            .containsEntry("lastFailure", "read: IllegalStateException")
            .doesNotContainKey("retriesAt");
    }

    @Test
    void aCacheThatIsNotConfiguredIsNeverConsultedAndSaysWhy() {
        DegradableCache cache = caches.absent("home-screen", "no Valkey is configured");

        assertThat(cache.quiet()).isTrue();
        assertThat(cache.report())
            .containsEntry("mode", "absent")
            .containsEntry("absent", "no Valkey is configured")
            .doesNotContainKey("cooldown");
    }

    /** The registry is what {@code CacheHealth} reads, in an order that does not move. */
    @Test
    void everyRegisteredCacheIsListedByName() {
        caches.register("tenant-status");
        caches.register("permissions");
        caches.absent("home-screen", "no Valkey is configured");

        assertThat(caches.all()).extracting(DegradableCache::name)
            .containsExactly("home-screen", "permissions", "tenant-status");
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
