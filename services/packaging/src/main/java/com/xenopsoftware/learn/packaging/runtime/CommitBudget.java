package com.xenopsoftware.learn.packaging.runtime;

import com.xenopsoftware.learn.common.cache.DegradableCache;
import com.xenopsoftware.learn.common.cache.DegradableCaches;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * How often one registration may be written (T-4.4's last criterion).
 *
 * <h2>What the storm actually is</h2>
 *
 * <p>A conformant SCORM package calls {@code Commit} at every slide boundary, and some call it on
 * a timer measured in seconds. That is the normal behaviour of correct content, not abuse — which
 * is what makes this the write path most likely to dominate the database, and why T-4.4's own
 * issue text says so. A thousand learners in a course that commits every three seconds is over
 * three hundred writes a second against one table, each one replacing a {@code jsonb} document
 * that can be sixty kilobytes.
 *
 * <h2>Why refusing is safe here, and would not be almost anywhere else</h2>
 *
 * <p>Every commit carries the <b>whole</b> data model — a package's {@code Commit} means "this is
 * the state", never "this is what changed", and the endpoint is a {@code PUT} for that reason. So
 * a refused save loses nothing that the next save will not carry again, and the wrapper keeps its
 * map dirty and re-sends it. {@code Terminate} forces a commit, which makes the last one the one
 * that counts, and the last one is the one furthest from any burst.
 *
 * <p>The refusal is a {@code 429} with {@code Retry-After}, and the wrapper treats it as "not
 * yet", not as an error the package should hear about. A SCORM package that was told its
 * {@code Commit} failed would show the learner a dialog about it.
 *
 * <h2>Per registration, not per learner and not per address</h2>
 *
 * <p>The row is the thing being protected, so the row is the thing counted. Per learner would
 * throttle somebody legitimately working through two courses in two tabs; per address would
 * throttle a whole company behind one NAT, which is the mistake {@code MintRateLimiter} already
 * names.
 *
 * <h2>It fails open, for that limiter's reason</h2>
 *
 * <p>Valkey being unreachable must not stop a learner's progress being saved. The budget exists to
 * flatten a load curve, and an unbounded curve for the duration of a Valkey outage is a smaller
 * failure than every commit in the platform being refused. {@link DegradableCache}'s cooldown is
 * what keeps failing open cheap — without it, every save would wait out a connection timeout on a
 * path a learner is sitting in front of.
 */
@Component
public class CommitBudget {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate valkey;
    private final Clock clock;
    private final DegradableCache cache;
    private final int savesPerWindow;

    /**
     * @param savesPerWindow saves allowed per registration per minute. Twenty is one every three
     *                       seconds sustained, which is above what any authoring tool emits on its
     *                       own and far above what the wrapper — which coalesces to one every five
     *                       seconds — can produce; content has to be trying to exceed it.
     *                       Configurable because a customer with one pathological course should be
     *                       a value change rather than a release, and because a test that wants to
     *                       measure the shape of a storm rather than the limit on it has to be able
     *                       to lift the limit
     */
    public CommitBudget(StringRedisTemplate valkey, Clock clock, DegradableCaches caches,
            @Value("${packaging.runtime.saves-per-window:20}") int savesPerWindow) {
        this.valkey = valkey;
        this.clock = clock;
        this.cache = caches.register("package-commit-budget");
        this.savesPerWindow = savesPerWindow;
    }

    /** Whether this registration may be written now, counting the attempt. */
    public boolean permit(UUID learnerId, UUID packageId, UUID nodeId) {
        if (cache.quiet()) {
            return true;
        }
        long window = WINDOW.toSeconds();
        long bucket = clock.instant().getEpochSecond() / window;
        String key = "packaging:commit:" + learnerId + ":" + packageId + ":" + nodeId + ":" + bucket;
        try {
            Long used = valkey.opsForValue().increment(key);
            if (used != null && used == 1L) {
                // Only on the first hit of a window. Re-expiring on every request would let a
                // steady stream of them push the expiry forward forever, which is the one way a
                // fixed window can be turned into a key that never dies.
                valkey.expire(key, WINDOW);
            }
            return used == null || used <= savesPerWindow;
        } catch (RuntimeException valkeyDown) {
            cache.failed("count", valkeyDown);
            return true;
        }
    }
}
