package com.xenopsoftware.learn.common.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A cache whose absence is a correct mode, and the cost of that absence bounded to once per
 * window.
 *
 * <h2>Why this is one class and not four</h2>
 *
 * <p>Four callers in this codebase read Valkey and continue without it — the permission cache
 * (T-2.5), the home screen (T-5.8), the published tenant status (T-1.4) and the playback mint
 * limit (T-3.4). Two of them had written the same twelve lines; the two that had not are the two
 * on the path of <em>every</em> request, and on 2026-09-08 that is what turned a network policy
 * missing a namespace into an outage.
 *
 * <p>A cache that cannot be reached does not fail fast. Lettuce shares one connection, so a
 * connection that can never be established makes each caller wait for it in turn: {@code
 * reporting} served about one request per second at 20–60m of CPU, and at 64 concurrent the
 * failures serialised past the liveness probe's deadline and the kubelet restarted the container.
 * Nothing about that reads as "the cache is missing"; it reads as a service that is inexplicably
 * slow.
 *
 * <p>So the rule this class exists to enforce: <b>a caller pays the timeout once per
 * {@code cooldown}, not once per request.</b> After a failure the cache is not consulted again
 * until the window closes, and the caller takes the path it was always going to take when the
 * entry was missing.
 *
 * <h2>And why it is reported</h2>
 *
 * <p>Failing open is correct and it is also invisible. The same outage logged a line per request
 * and moved no health signal at all: the stock Redis indicator was off in two services and
 * excluded from the probe groups in the other two, so the pods were Ready, Argo was green, and
 * the permission cache had never once been read on that cluster. {@link #report()} is what a
 * degraded cache says about itself, and {@code CacheHealth} puts it on {@code
 * /management/health} as a detail of an <b>UP</b> indicator — never DOWN, because a service
 * built to survive this must not be taken out of rotation for it.
 */
public final class DegradableCache {

    private static final Logger LOG = LoggerFactory.getLogger(DegradableCache.class);

    private final String name;
    private final Duration cooldown;
    private final Map<String, Object> constants;
    private final Clock clock;

    /** Why there is no cache at all, or null when there is one. Set once, at registration. */
    private final String absence;

    private final AtomicLong quietUntil = new AtomicLong(Long.MIN_VALUE);
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    DegradableCache(String name, Duration cooldown, Map<String, Object> constants, String absence,
            Clock clock) {
        this.name = name;
        this.cooldown = cooldown;
        this.constants = Map.copyOf(constants);
        this.absence = absence;
        this.clock = clock;
    }

    /** The name this cache reports itself under. */
    public String name() {
        return name;
    }

    /**
     * Whether to skip the cache entirely and take the path a miss would have taken.
     *
     * <p>True while a cooldown window is open, and permanently true when there is no cache
     * configured — a service with no Valkey must not pay a connection attempt per request either.
     */
    public boolean quiet() {
        return absence != null || clock.millis() < quietUntil.get();
    }

    /**
     * Records a failed cache operation and opens the cooldown window.
     *
     * <p>Logged every time it is called, which is once per window rather than once per request:
     * the window is what stops the next caller reaching the cache at all.
     *
     * @param operation what was being attempted, in one word — it appears in the health detail
     * @param cause     the failure, logged whole
     */
    public void failed(String operation, Throwable cause) {
        lastFailure.set(operation + ": " + cause.getClass().getSimpleName());
        quietUntil.set(clock.millis() + cooldown.toMillis());
        LOG.warn("The {} cache failed on {}; running without it for the next {}",
            name, operation, cooldown, cause);
    }

    /** What this cache is doing right now: {@code absent}, {@code degraded} or {@code ok}. */
    public String mode() {
        if (absence != null) {
            return "absent";
        }
        return quiet() ? "degraded" : "ok";
    }

    /** What an operator needs to see, as health details. */
    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", mode());
        if (absence != null) {
            report.put("absent", absence);
        } else {
            report.put("cooldown", cooldown.toString());
        }
        String failure = lastFailure.get();
        if (failure != null) {
            report.put("lastFailure", failure);
            long until = quietUntil.get();
            if (clock.millis() < until) {
                report.put("retriesAt", Instant.ofEpochMilli(until).toString());
            }
        }
        report.putAll(constants);
        return report;
    }
}
