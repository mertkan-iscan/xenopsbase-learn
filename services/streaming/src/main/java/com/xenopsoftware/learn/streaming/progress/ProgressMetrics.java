package com.xenopsoftware.learn.streaming.progress;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the merge is doing, as numbers (T-3.7).
 *
 * <p>The criterion these serve is ADR-0107's: an implausible batch is <b>rejected and counted</b>.
 * Counted matters as much as rejected — one refusal is a laptop resumed from sleep, and a thousand
 * of them from one tenant is either a broken player release or somebody automating a completion.
 * Only the count tells those apart, and only a count tagged by reason tells the two of them apart
 * from each other.
 *
 * <p>The fragment summary is here because the size bound is a claim this platform makes about
 * itself. {@code docs/slos.md} states a distribution measured in a simulation; this is how the
 * same distribution gets measured against real learners, and how the day the cap starts biting
 * becomes visible before somebody's completion is quietly approximate.
 *
 * <p><b>None of these are scrapeable yet</b> — every service in this repository permits only
 * {@code /management/health} and {@code /management/info}, which T-9.13 (#91) owns. That is why
 * the two counts about a particular learner are also kept on their progress row, where a support
 * question can reach them without a metrics stack.
 */
@Component
public class ProgressMetrics {

    private final Counter batches;
    private final Counter creditedSeconds;
    private final Counter completions;
    private final DistributionSummary fragments;
    private final MeterRegistry registry;

    public ProgressMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.batches = Counter.builder("progress.batches.accepted")
            .description("Interval batches merged into a learner's coverage")
            .register(registry);
        this.creditedSeconds = Counter.builder("progress.seconds.credited")
            .description("Seconds of content newly credited to learners")
            .baseUnit("seconds")
            .register(registry);
        this.completions = Counter.builder("progress.completions")
            .description("Items whose coverage crossed the threshold, derived server-side")
            .register(registry);
        this.fragments = DistributionSummary.builder("progress.coverage.fragments")
            .description("Fragments in a learner's coverage set after a merge")
            // Percentiles, because the cap is a tail question: a mean of 1.2 fragments says
            // nothing about the scrubber whose set is one merge away from being approximate.
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }

    public void merged(int newlyCoveredSeconds, int fragmentCount) {
        batches.increment();
        creditedSeconds.increment(Math.max(0, newlyCoveredSeconds));
        fragments.record(fragmentCount);
    }

    public void completed() {
        completions.increment();
    }

    public void rejected(ProgressRejection reason) {
        Counter.builder("progress.batches.rejected")
            .description("Interval batches refused, by reason")
            .tag("reason", reason.name())
            .register(registry)
            .increment();
    }
}
