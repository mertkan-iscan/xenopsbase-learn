package com.xenopsoftware.learn.assessment.attempt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The numbers T-6.6 requires somebody to choose on purpose.
 *
 * @param interval how often the reaper runs. Five minutes: an attempt that lapsed is wrong on a
 *        report and is holding a learner's one-in-progress slot, and neither is urgent to the
 *        minute. Parked to an hour in tests, which drive the sweep themselves
 * @param abandonAfter how long an UNTIMED attempt may sit untouched before it is called abandoned.
 *        <b>Seven days</b>, and the number is a compromise worth stating: short enough that a
 *        learner who walked away in March is not still blocking their own next attempt in June,
 *        long enough that somebody working through an untimed course over a fortnight of evenings
 *        is not cut off mid-way. It applies only where there is no deadline, because a timed
 *        attempt already has one
 */
@ConfigurationProperties(prefix = "assessment.attempt")
public record AttemptProperties(
        @DefaultValue("PT5M") Duration interval,
        @DefaultValue("P7D") Duration abandonAfter) {

    public AttemptProperties {
        if (abandonAfter.isZero() || abandonAfter.isNegative()) {
            throw new IllegalArgumentException(
                "assessment.attempt.abandon-after is how long somebody may leave an untimed "
                + "attempt open, so it is a positive duration: " + abandonAfter);
        }
    }
}
