package com.xenopsoftware.learn.assessment.integrity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The numbers T-6.8 requires somebody to choose on purpose.
 *
 * @param retention how long an integrity signal is kept. <b>Ninety days</b>, and the criterion asks
 *        for this to be stated separately from anything else because these rows are behavioural
 *        data about a person rather than a record of their training. Long enough that a dispute
 *        about a result can be raised and reviewed; short enough that the platform is not keeping a
 *        permanent log of when somebody's attention wandered. The ATTEMPT survives this deletion,
 *        which is the whole point of the clock being its own
 * @param sweepInterval how often the reaper runs. Six hours: retention measured in months does not
 *        need a pass measured in minutes, and a sweep that runs rarely is a sweep whose cost is
 *        never anybody's problem
 * @param maxPerAttempt the cap. <b>500</b>: this is a write endpoint a learner's own browser calls,
 *        so it is a place somebody can push on. Five hundred is far more than an honest three-hour
 *        exam produces and far less than a loop can. Past it, signals are counted and dropped
 *        rather than refused -- refusing would make the player retry, which is the opposite of
 *        what a flood needs
 */
@ConfigurationProperties(prefix = "assessment.integrity")
public record IntegrityProperties(
        @DefaultValue("P90D") Duration retention,
        @DefaultValue("PT6H") Duration sweepInterval,
        @DefaultValue("500") int maxPerAttempt) {

    public IntegrityProperties {
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException(
                "assessment.integrity.retention is how long behavioural data about a person is "
                + "kept, so it is a positive duration: " + retention);
        }
        if (maxPerAttempt < 1) {
            throw new IllegalArgumentException(
                "assessment.integrity.max-per-attempt is a cap, not a switch: " + maxPerAttempt);
        }
    }
}
