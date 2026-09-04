package com.xenopsoftware.learn.identity.impersonation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a support engineer may stay inside a customer's account, and how much reason they
 * have to give for it (T-2.8).
 *
 * @param maxDuration the ceiling on one session. Short enough that "we forgot to close it" is
 *                    not a way to hold a standing key to a customer's account, long enough to
 *                    reproduce a problem and read the result. There is no renew endpoint; a
 *                    longer investigation is a second session with its own reason, which is the
 *                    record a customer should get
 * @param minReason   the shortest reason accepted. A required field somebody satisfies with "x"
 *                    is a required field that records nothing, and this log is read months later
 *                    by people who were not there
 */
@ConfigurationProperties(prefix = "identity.impersonation")
public record ImpersonationProperties(Duration maxDuration, int minReason) {

    public ImpersonationProperties {
        maxDuration = maxDuration == null ? Duration.ofMinutes(30) : maxDuration;
        minReason = minReason <= 0 ? 12 : minReason;
    }
}
