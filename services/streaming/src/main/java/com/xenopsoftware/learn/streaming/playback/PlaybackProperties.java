package com.xenopsoftware.learn.streaming.playback;

import com.xenopsoftware.learn.streaming.media.PlaybackGrant;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two numbers T-3.4 requires somebody to choose on purpose, and the reasoning behind them.
 *
 * <h2>Why the TTL is a decision and not a default</h2>
 *
 * An issued playback token cannot be recalled from the edge — that is the whole point of
 * ADR-0101, and it is what lets playback survive every one of our services being stopped
 * (T-3.10). The consequence is exact: <b>the TTL is the platform's revocation window.</b>
 * Suspending a company, revoking an assignment, closing a gate and deactivating a user all take
 * effect on the next mint and not before, so whatever number is written here is the honest
 * answer to "how long does a suspended customer keep watching".
 *
 * <p>Five minutes, because the two pressures point in opposite directions and this is where
 * they balance. Shorter makes the revocation window tighter but puts renewal on the critical
 * path often enough that a slow identity call or a GC pause becomes a stall the learner sees.
 * Longer is cheaper per viewer and worse at the only job the number has. Five minutes means a
 * suspension lands within one advert break, a link pasted into a chat is dead before anyone
 * clicks it, and a viewer costs twelve mints an hour.
 *
 * <p>{@link PlaybackGrant#MAX_VALIDITY} is the ceiling nobody may raise past — 30 minutes — and
 * this is the operating number well inside it.
 *
 * <h2>Why renewal happens before expiry</h2>
 *
 * {@code renewAfter} is what the response tells the player, and it is deliberately much earlier
 * than the expiry: a player that renews when its token expires has already stalled. At three
 * minutes into a five-minute token there are two further minutes of valid playback to retry in,
 * so a failed renewal is invisible unless it keeps failing.
 *
 * <h2>Why minting is rate-limited</h2>
 *
 * The endpoint hands out signed URLs that work without us. Somebody who can call it in a loop
 * can farm a stock of tokens and hand them out, and the TTL stops mattering because there is
 * always a fresh one. A watching learner needs roughly one mint per three minutes per stream;
 * twenty in five minutes leaves an order of magnitude of headroom for reloads, seeks and a few
 * simultaneous tabs, and stops a loop within a second of it starting.
 *
 * @param tokenTtl       how long a minted token is valid — the revocation window
 * @param renewAfter     when the player should ask for the next one
 * @param mintsPerWindow how many tokens one viewer may mint per window
 * @param mintWindow     the window that count applies to
 */
@ConfigurationProperties(prefix = "streaming.playback")
public record PlaybackProperties(
        @DefaultValue("PT5M") Duration tokenTtl,
        @DefaultValue("PT3M") Duration renewAfter,
        @DefaultValue("20") int mintsPerWindow,
        @DefaultValue("PT5M") Duration mintWindow) {

    public PlaybackProperties {
        if (tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("streaming.playback.token-ttl must be positive");
        }
        if (tokenTtl.compareTo(PlaybackGrant.MAX_VALIDITY) > 0) {
            // Fails startup rather than being clamped, because a clamped value means the
            // configured number and the real one differ and only one of them is written down.
            throw new IllegalArgumentException("streaming.playback.token-ttl is " + tokenTtl
                + ", above the " + PlaybackGrant.MAX_VALIDITY + " ceiling. That ceiling is the "
                + "bound on how long a suspended company keeps watching (T-1.4); raising it is "
                + "an ADR-0101 conversation, not a configuration change.");
        }
        if (renewAfter.compareTo(tokenTtl) >= 0) {
            throw new IllegalArgumentException("streaming.playback.renew-after (" + renewAfter
                + ") must be before the token expires (" + tokenTtl + "); a player told to renew "
                + "at expiry has already stalled");
        }
        if (mintsPerWindow < 1) {
            throw new IllegalArgumentException("streaming.playback.mints-per-window must be at least 1");
        }
        if (mintWindow.isNegative() || mintWindow.isZero()) {
            throw new IllegalArgumentException("streaming.playback.mint-window must be positive");
        }
    }
}
