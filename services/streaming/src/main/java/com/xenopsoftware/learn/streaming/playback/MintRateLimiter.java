package com.xenopsoftware.learn.streaming.playback;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * How many playback tokens one viewer may mint (T-3.4's last criterion).
 *
 * <p>Per principal rather than per address, which is T-8.7's rule arriving early here because
 * this endpoint needs it first: a company's learners share one office NAT, so an address limit
 * either throttles a whole customer or is set so high it stops nothing. The subject from the
 * verified token is the only identifier that means one person.
 *
 * <h2>A fixed window, not a sliding one</h2>
 *
 * One {@code INCR} and one {@code EXPIRE} against a key that names its own window, which costs a
 * round trip and no state of ours. A fixed window lets a burst of twice the limit straddle a
 * boundary; against token farming that does not matter, because the thing being bounded is the
 * sustained rate at which signed URLs can be extracted, not the shape of any one second. A
 * sliding window would buy precision this limit has no use for.
 *
 * <h2>It fails open, and that is the smaller failure</h2>
 *
 * Valkey being down should not stop every learner watching. The limiter exists to stop farming,
 * which is an abuse case, and the entitlement decision itself — status, permission, assignment,
 * gate — is unaffected and still refuses everything it would otherwise refuse. Trading "abuse is
 * briefly unbounded" against "nobody can watch anything" is not a close call, and it is the same
 * direction {@code PublishedStatusLookup} chose for the same reason.
 */
@Component
public class MintRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(MintRateLimiter.class);

    private final StringRedisTemplate valkey;
    private final PlaybackProperties properties;
    private final Clock clock;

    public MintRateLimiter(StringRedisTemplate valkey, PlaybackProperties properties, Clock clock) {
        this.valkey = valkey;
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether this viewer may mint now, counting the attempt. */
    public boolean permit(Viewer viewer) {
        long window = properties.mintWindow().toSeconds();
        long bucket = clock.instant().getEpochSecond() / window;
        String key = "playback:mint:" + viewer.tenantId() + ":" + viewer.subject() + ":" + bucket;
        try {
            Long used = valkey.opsForValue().increment(key);
            if (used != null && used == 1L) {
                // Only on the first hit of a window: re-expiring on every request would let a
                // steady stream of them push the key's expiry forward forever.
                valkey.expire(key, properties.mintWindow());
            }
            return used == null || used <= properties.mintsPerWindow();
        } catch (RuntimeException valkeyDown) {
            LOG.warn("Could not count playback mints for {}; the rate limit is not being applied "
                + "until Valkey returns", viewer.subject(), valkeyDown);
            return true;
        }
    }
}
