package com.xenopsoftware.learn.identity.authz;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the permission cache behaves (T-2.5).
 *
 * @param enabled  whether a cache is consulted at all; when false every resolution reads the
 *                 database, which is the behaviour every other failure mode falls back to
 * @param ttl      a backstop, not the invalidation mechanism — correctness comes from the
 *                 version in the key. This bounds how long an entry can outlive a change made
 *                 <em>without</em> a version bump: a restore from backup, a support fix applied
 *                 in SQL, a bug. Without it such a change would be invisible until the row it
 *                 contradicts happens to be written again, which may be never
 * @param cooldown how long to stop consulting an unreachable cache. A dead Valkey with no
 *                 cooldown costs every request a connection timeout, which converts a cache
 *                 outage into a latency outage — the thing degradation is supposed to prevent
 */
@ConfigurationProperties(prefix = "identity.authz.cache")
public record PermissionCacheProperties(boolean enabled, Duration ttl, Duration cooldown) {

    public PermissionCacheProperties {
        ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
        cooldown = cooldown == null ? Duration.ofSeconds(10) : cooldown;
    }
}
