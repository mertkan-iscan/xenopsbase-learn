package com.xenopsoftware.learn.streaming.media;

import java.time.Duration;

/**
 * What an entitlement decision authorizes, once made (T-3.4 makes the decision; this carries
 * it). Short by design: the token IS the revocation window, so validity is a required argument
 * with a ceiling rather than a default someone widens for convenience.
 */
public record PlaybackGrant(Duration validity) {

    /** ADR-0101: minutes, not hours. A leaked link dies with the token. */
    public static final Duration MAX_VALIDITY = Duration.ofMinutes(30);

    public PlaybackGrant {
        if (validity.isNegative() || validity.isZero()) {
            throw new IllegalArgumentException("validity must be positive");
        }
        if (validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException(
                "validity " + validity + " exceeds the " + MAX_VALIDITY + " ceiling; a long-lived "
                + "playback token is an entitlement that cannot be revoked");
        }
    }
}
