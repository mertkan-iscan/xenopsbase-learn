package com.xenopsoftware.learn.streaming.progress;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every number T-3.7 requires somebody to choose on purpose (ADR-0107).
 *
 * @param defaultThresholdPercent the coverage a learner needs to have completed an item when the
 *        item itself says nothing. <b>90%</b>: the last few seconds of a video are credits and a
 *        dropped final heartbeat is ordinary, so 100 would fail honest learners; 75 would pass a
 *        learner who skipped the last quarter, who did not do the training. An item that genuinely
 *        needs all of it sets its own number, which is why this is a default and not a constant
 * @param maxFragments the size bound on one learner's coverage set. <b>64</b>, from the
 *        distribution measured in {@code docs/slos.md}: a straight-through learner produces one
 *        fragment, and a deliberate scrubber in that simulation reached the low twenties over a
 *        full video. Sixty-four leaves an order of magnitude over observed behaviour and still
 *        bounds a heartbeat's work at a fixed size, which is the property ADR-0107 asked for.
 *        At the cap the smallest gap is closed and the row is flagged approximate
 * @param coalesceGapSeconds gaps this small are closed rather than kept. <b>2</b>, by ADR-0107:
 *        inside the sampling error of a ten-second heartbeat, and the largest inflation closing it
 *        can add — the rounding goes in the learner's favour, by less than one heartbeat
 * @param maxSamplesPerBatch the same 60 the player buffers and reporting accepts (T-3.6), so a
 *        batch the analytics path takes is never one this path refuses
 * @param maxIntervalSeconds the longest slice one sample may claim, well above a ten-second
 *        heartbeat at 2× and well below "the whole film in one sample"
 * @param minRate  the slowest rate the player offers
 * @param maxRate  the fastest rate the player offers, and therefore the most video wall clock can
 *        account for. Narrower than reporting's band on purpose: reporting validates a number a
 *        client reported, and this bounds what the platform will <em>credit</em>
 * @param rateGrace how much coverage may arrive ahead of wall clock. Two minutes, which is one
 *        full client buffer at the fastest rate: a learner returning from an outage posts sixty
 *        buffered samples at once and must not be called a liar for it
 * @param policyRefresh how long the item's threshold and seek rule may be assumed unchanged. An
 *        hour: an author changing a threshold expects it to take effect, and a heartbeat may not
 *        ask catalog for it. Both facts, and this is where they meet
 * @param seekToleranceSeconds how far past what has been watched a claim may start before an item
 *        that forbids seeking forward refuses it. Three seconds, matching the player's own
 *        "is this still continuous playback" threshold — the same rule on both sides, so an honest
 *        player is never refused by it
 */
@ConfigurationProperties(prefix = "streaming.progress")
public record ProgressProperties(
        @DefaultValue("90") int defaultThresholdPercent,
        @DefaultValue("64") int maxFragments,
        @DefaultValue("2") int coalesceGapSeconds,
        @DefaultValue("60") int maxSamplesPerBatch,
        @DefaultValue("120") int maxIntervalSeconds,
        @DefaultValue("0.25") double minRate,
        @DefaultValue("2.0") double maxRate,
        @DefaultValue("PT2M") Duration rateGrace,
        @DefaultValue("PT1H") Duration policyRefresh,
        @DefaultValue("3") int seekToleranceSeconds) {

    public ProgressProperties {
        if (defaultThresholdPercent < 1 || defaultThresholdPercent > 100) {
            throw new IllegalArgumentException(
                "streaming.progress.default-threshold-percent must be between 1 and 100");
        }
        if (maxFragments < 1) {
            throw new IllegalArgumentException("streaming.progress.max-fragments must be at least 1");
        }
        if (coalesceGapSeconds < 0) {
            throw new IllegalArgumentException(
                "streaming.progress.coalesce-gap-seconds cannot be negative");
        }
        if (maxSamplesPerBatch < 1 || maxIntervalSeconds < 1) {
            throw new IllegalArgumentException(
                "streaming.progress batch bounds must be positive; they are what make the work "
                + "per request finite");
        }
        if (minRate <= 0 || maxRate < minRate) {
            throw new IllegalArgumentException(
                "streaming.progress rate band must be positive and ordered");
        }
        if (rateGrace.isNegative()) {
            throw new IllegalArgumentException("streaming.progress.rate-grace cannot be negative");
        }
    }
}
