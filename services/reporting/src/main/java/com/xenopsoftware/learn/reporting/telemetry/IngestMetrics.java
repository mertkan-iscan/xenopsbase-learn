package com.xenopsoftware.learn.reporting.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * What ingest is doing, as numbers (T-3.6).
 *
 * <p>The criterion these serve is not "have metrics". It is this: <b>losing a heartbeat is
 * acceptable and losing all of them silently is not.</b> Silent total failure is the only way a
 * compliance report fills with zeros while every dashboard stays green, and the shape of that
 * failure is an ingest rate at zero while playback tokens are still being minted — which is why
 * the alert in {@code docs/runbooks/telemetry-ingest.md} compares the two rather than watching
 * this counter alone. A counter at zero is indistinguishable from nobody watching.
 *
 * <p>Rejections are tagged by reason rather than counted as one number, because the reasons call
 * for different responses: a spike in MALFORMED_INTERVAL is a player release, a spike in
 * BATCH_TOO_LARGE is a network outage somewhere refilling buffers, and one number would show both
 * as "rejections up".
 */
@Component
public class IngestMetrics {

    private final Counter batchesAccepted;
    private final Counter samplesAccepted;
    private final DistributionSummary lagSeconds;
    private final MeterRegistry registry;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.batchesAccepted = Counter.builder("telemetry.playback.batches.accepted")
            .description("Heartbeat batches accepted and written")
            .register(registry);
        this.samplesAccepted = Counter.builder("telemetry.playback.samples.accepted")
            .description("Individual heartbeat samples written")
            .register(registry);
        this.lagSeconds = DistributionSummary.builder("telemetry.playback.lag")
            .description("Seconds between a player observing a sample and this service receiving it")
            .baseUnit("seconds")
            // Percentiles rather than a mean: lag is the measure that matters at its tail, and a
            // mean lag of two seconds hides the tenth of learners whose posts arrive minutes late
            // because their buffer was draining.
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }

    public void accepted(int samples, Duration lag) {
        batchesAccepted.increment();
        samplesAccepted.increment(samples);
        // Negative lag is a player clock ahead of ours, not a negative duration; clamped so one
        // skewed laptop cannot drag a percentile below zero and make the summary nonsense.
        lagSeconds.record(Math.max(0d, lag.toMillis() / 1000d));
    }

    public void rejected(RejectionReason reason) {
        Counter.builder("telemetry.playback.batches.rejected")
            .description("Heartbeat batches refused, by reason")
            .tag("reason", reason.name())
            .register(registry)
            .increment();
    }
}
