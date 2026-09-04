package com.xenopsoftware.learn.streaming.media;

import java.time.Duration;

/**
 * What an entitlement decision authorizes, once made (T-3.4 makes the decision; this carries
 * it). Short by design: the token IS the revocation window, so validity is a required argument
 * with a ceiling rather than a default someone widens for convenience.
 *
 * <h2>What "bound to the viewer" does and does not mean</h2>
 *
 * The token binds three things, and they are not equally enforced. <b>The asset</b> is the
 * token's subject and <b>the expiry</b> is a claim the edge checks, so both are enforced by
 * whoever serves the bytes, with no call back to us. <b>The viewer</b> is signed into the token
 * but is <em>not</em> checked by Cloudflare Stream, which offers no per-viewer rule — its
 * access rules are about addresses and countries, not people.
 *
 * <p>So this is worth stating rather than implying: a token handed to somebody else still plays
 * until it expires. Making that not true would need either a token exchange on our own origin —
 * which puts us back on the hot path and forecloses T-3.10 — or DRM, which ADR-0101 already
 * rules out and which nobody may claim this is. What the viewer binding buys is that a token
 * found in a log or a support ticket says whose it was, and that a share is attributable after
 * the fact. The short TTL is what keeps the window small; sharing is bounded, not prevented.
 *
 * @param viewerSubject the IdP subject of the person this was decided for — signed, recorded,
 *                      and not enforced by the edge
 */
public record PlaybackGrant(String viewerSubject, Duration validity) {

    /** ADR-0101: minutes, not hours. A leaked link dies with the token. */
    public static final Duration MAX_VALIDITY = Duration.ofMinutes(30);

    public PlaybackGrant {
        if (viewerSubject == null || viewerSubject.isBlank()) {
            // An unbound token is one nobody can be held to: it would be indistinguishable in
            // every log from every other token for the same video.
            throw new IllegalArgumentException("A playback grant names the viewer it was decided for");
        }
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
