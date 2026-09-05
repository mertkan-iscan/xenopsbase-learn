package com.xenopsoftware.learn.common.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * How anyone finds out the relay has stopped (T-9.8's sixth criterion).
 *
 * <p><b>A stalled relay is completely silent.</b> Nothing throws, no request fails, no log line
 * appears. The first symptom is downstream and much later: a report a day behind, a gate that never
 * opens, a person who never got the course they were assigned — and each of those gets investigated
 * as a bug in the thing that looks broken, which is never the relay.
 *
 * <p><b>Age, not count.</b> A busy service always has unpublished rows; a count alarms on health
 * and stays quiet on failure. The oldest row's age is near zero when the relay is working, whatever
 * the throughput, and climbs without limit when it is not.
 *
 * <pre>
 *   platform_outbox_oldest_seconds &gt; 60 for 5m   -- the relay is not draining
 * </pre>
 *
 * <p>Sampled on a schedule rather than computed inside the gauge, because a gauge is read by the
 * metrics endpoint and an aggregate over the outbox on every scrape would make Prometheus the
 * heaviest reader of the table.
 */
public class OutboxMetrics {

    private final OutboxRelay relay;
    private final AtomicReference<Double> oldestSeconds = new AtomicReference<>(0.0);
    private final AtomicReference<Double> pending = new AtomicReference<>(0.0);

    public OutboxMetrics(OutboxRelay relay, MeterRegistry meters) {
        this.relay = relay;
        Gauge.builder("platform.outbox.oldest.seconds", oldestSeconds, AtomicReference::get)
            .description("Age of the oldest unpublished outbox row. Climbs without limit when the "
                + "relay has stopped, which is otherwise silent.")
            .register(meters);
        Gauge.builder("platform.outbox.pending", pending, AtomicReference::get)
            .description("Unpublished outbox rows. Normal on a busy service; only meaningful "
                + "beside the age.")
            .register(meters);
    }

    @Scheduled(fixedDelayString = "${platform.outbox.metrics-interval:PT10S}")
    public void sample() {
        var backlog = relay.backlog();
        oldestSeconds.set(backlog.get("oldestSeconds").doubleValue());
        pending.set(backlog.get("pending").doubleValue());
    }
}
