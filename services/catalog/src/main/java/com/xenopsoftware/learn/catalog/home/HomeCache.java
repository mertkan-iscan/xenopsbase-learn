package com.xenopsoftware.learn.catalog.home;

import com.xenopsoftware.learn.common.cache.DegradableCache;
import com.xenopsoftware.learn.common.cache.DegradableCaches;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The assembled home screen, kept for a minute (T-5.8's last criterion).
 *
 * <h2>What is safe to cache here, and what makes it safe</h2>
 *
 * The whole answer, keyed on a version that moves whenever anything in it changes
 * ({@link HomeVersions}). A learner reloading their screen twice in a minute does not re-read
 * assignments, structure, gates, completions and progress the second time; a learner who has just
 * finished a video does, because finishing it moved their version.
 *
 * <p><b>Version-keyed rather than evicted</b>, for the reason {@code HomeVersions} states: the bump
 * happens inside the transaction that changed something, so there is no window in which a commit
 * has happened and the cache has not been told.
 *
 * <h2>The one thing the version cannot catch, and the TTL that bounds it</h2>
 *
 * <b>Time passes.</b> A screen assembled at 23:59 says a deadline is not overdue, and nothing
 * "changes" at midnight for a version to move on. So the entry lives for one minute — long enough
 * to absorb a reload and a second tab, short enough that "overdue" is never wrong by more than that
 * against a deadline measured in days.
 *
 * <h2>When Valkey is not there</h2>
 *
 * The screen is built from Postgres, which is what happens on every miss anyway. A failure is
 * logged once and then the cache is skipped for a cooldown, so a Valkey outage costs one timeout
 * rather than one per request.
 *
 * <p>That cooldown used to live here, in twelve lines this class owned, and identically in
 * identity's permission cache. It is now {@link DegradableCache}, which is where the two callers
 * that never wrote it — the tenant status gate and the playback mint limit — have it too. Both of
 * those sit on the path of every request, and on 2026-09-08 that is what turned an unreachable
 * Valkey into a service serving one request per second (#126).
 */
@Component
public class HomeCache {

    private static final Logger LOG = LoggerFactory.getLogger(HomeCache.class);

    /**
     * Bumped when the shape of {@link HomeView} changes.
     *
     * <p>Old entries are then unreachable rather than misread, which matters during a rolling
     * deploy: two versions of this service share one Valkey, and the older one must not be handed
     * JSON it will parse into something subtly wrong.
     */
    private static final int SCHEMA = 1;

    private final StringRedisTemplate valkey;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Duration ttl;
    private final DegradableCache cache;

    public HomeCache(ObjectProvider<StringRedisTemplate> valkey,
            @Value("${catalog.home.cache-ttl:PT1M}") Duration ttl,
            @Value("${catalog.home.cache-cooldown:PT30S}") Duration cooldown,
            DegradableCaches caches) {
        this.valkey = valkey.getIfAvailable();
        this.ttl = ttl;
        if (this.valkey == null) {
            LOG.warn("No Valkey is configured, so every home screen is assembled from Postgres. "
                + "Correct, and slower than it needs to be.");
            this.cache = caches.absent("home-screen",
                "no Valkey is configured, so every screen is assembled from Postgres");
        } else {
            this.cache = caches.register("home-screen", cooldown,
                Map.of("schema", SCHEMA, "ttl", ttl.toString()));
        }
    }

    Optional<HomeView> get(String tenantId, UUID learnerId, String version) {
        if (cache.quiet()) {
            return Optional.empty();
        }
        try {
            String cached = valkey.opsForValue().get(key(tenantId, learnerId, version));
            return cached == null ? Optional.empty()
                : Optional.of(json.readValue(cached, HomeView.class));
        } catch (RuntimeException unreachableOrUnreadable) {
            // Unreadable is as survivable as unreachable: the screen is rebuilt either way, and an
            // entry this version cannot parse is one a previous version wrote.
            cache.failed("read", unreachableOrUnreadable);
            return Optional.empty();
        }
    }

    void put(String tenantId, UUID learnerId, String version, HomeView view) {
        if (cache.quiet()) {
            return;
        }
        try {
            valkey.opsForValue().set(key(tenantId, learnerId, version),
                json.writeValueAsString(view), ttl);
        } catch (RuntimeException unreachable) {
            cache.failed("write", unreachable);
        }
    }

    private static String key(String tenantId, UUID learnerId, String version) {
        return "home:v" + SCHEMA + ":" + tenantId + ":" + learnerId + ":" + version;
    }
}
