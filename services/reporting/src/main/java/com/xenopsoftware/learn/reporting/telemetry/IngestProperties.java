package com.xenopsoftware.learn.reporting.telemetry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The bounds on one posted batch (T-3.6).
 *
 * <p>Every number here exists to make the work per request finite. This endpoint is the one write
 * path in the product allowed to shed load, and a request whose cost is set by the caller is one
 * that cannot be shed usefully — it is already holding a connection by the time anybody decides to.
 *
 * @param maxSamplesPerBatch a player buffers a sample every ten seconds, so 60 is ten minutes of
 *        being offline. Past that the client drops its OLDEST samples rather than growing (T-3.6's
 *        bounded-buffer criterion), so a batch this size means the learner was away for ten
 *        minutes, not that something is wrong
 * @param maxIntervalSeconds the longest slice one sample may claim. A ten-second heartbeat at 2x
 *        covers twenty seconds of video; this is well above that and well below "the whole film in
 *        one sample", which is what an interval claim would look like if it were forged
 * @param minRate  slowest rate any player offers
 * @param maxRate  fastest rate any player offers
 * @param maxSkew  how far a player's clock may be from ours before the sample is suspect. Recorded
 *        rather than rejected here: a resumed laptop is far more common than an attack, and T-3.7
 *        owns what to do about it
 */
@ConfigurationProperties(prefix = "reporting.telemetry")
public record IngestProperties(
        @DefaultValue("60") int maxSamplesPerBatch,
        @DefaultValue("120") int maxIntervalSeconds,
        @DefaultValue("0.25") double minRate,
        @DefaultValue("4.0") double maxRate,
        @DefaultValue("PT10M") Duration maxSkew) {

    public IngestProperties {
        if (maxSamplesPerBatch < 1) {
            throw new IllegalArgumentException("reporting.telemetry.max-samples-per-batch must be at least 1");
        }
        if (maxIntervalSeconds < 1) {
            throw new IllegalArgumentException("reporting.telemetry.max-interval-seconds must be at least 1");
        }
        if (minRate <= 0 || maxRate < minRate) {
            throw new IllegalArgumentException("reporting.telemetry rate band must be positive and ordered");
        }
    }
}
