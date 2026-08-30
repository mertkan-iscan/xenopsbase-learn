package com.xenopsoftware.learn.identity.authz;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The permission cache (T-2.5), and the reason it cannot serve a revoked permission.
 *
 * <p><b>The key carries the tenant's {@link AuthzVersion}.</b> Every role, permission and
 * assignment change bumps that number inside the transaction that made the change (T-2.2), so
 * the moment a grant moves, every entry describing the old state becomes unreachable — not
 * expired, not evicted, unreachable, because nothing will ever compute its key again. That is
 * what makes invalidation by enumeration unnecessary, and invalidation by enumeration is where
 * this normally goes wrong: the writer has to know which keys exist, and one day it does not.
 *
 * <p><b>Every failure here is a slower request, never a wrong answer and never a failed one.</b>
 * Four failure points, and the fourth is the one that gets missed:
 * <ul>
 *   <li>a read that misses — resolve from the database and store the result;
 *   <li>a read that <em>throws</em> — resolve from the database, and stop asking for a while;
 *   <li>a read that returns something this version cannot parse — treated as a miss;
 *   <li>a write that throws — the answer is already computed, so the request is unaffected, and
 *       nothing about a change that has already committed depends on it.
 * </ul>
 *
 * <p>The cooldown is the difference between degrading and pretending to. A Valkey that is gone
 * answers with a connection failure after {@code spring.data.redis.connect-timeout}; paying that
 * on every request would turn a cache outage into a latency outage. After a failure this stops
 * consulting the cache for {@link PermissionCacheProperties#cooldown()} and serves from the
 * database directly, which costs one timeout per cooldown window rather than one per request.
 *
 * <p>What this does <em>not</em> do is mirror the version into Valkey for a gateway to read.
 * {@link AuthzVersion} promised that; the gateway does not exist yet (T-1.4), and a mirror
 * written after commit is a second source of truth with a window in which it disagrees with the
 * first. The row stays the truth, and reading it is one indexed lookup that replaces a
 * membership scan, a bounded ancestor walk and an assignment join.
 */
public class ValkeyPermissions implements CachedPermissions, HealthIndicator {

    /**
     * The cache schema (see {@link CachedGrants}). Bump it when the JSON changes in a way an
     * older reader could misread; every existing entry becomes unreachable rather than
     * reinterpreted, and the cost of that is one resolution per caller.
     */
    static final int SCHEMA = 1;

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyPermissions.class);

    private final StringRedisTemplate valkey;
    private final AuthzVersion versions;
    private final Duration ttl;
    private final Duration cooldown;
    private final ObjectMapper json;
    private final Timer fromDatabase;
    private final Counter hits;
    private final Counter misses;
    private final Counter unreadable;
    private final Counter unreachable;
    private final Counter bypassed;
    private final AtomicLong quietUntil = new AtomicLong();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    public ValkeyPermissions(StringRedisTemplate valkey, AuthzVersion versions,
            PermissionCacheProperties properties, MeterRegistry meters) {
        this.valkey = valkey;
        this.versions = versions;
        this.ttl = properties.ttl();
        this.cooldown = properties.cooldown();
        // Its own mapper, like AuditLogger's and for the same reason: this JSON is read by
        // versions of this service that have not been written yet, so its behaviour must not
        // change because somebody tuned the API's serialisation. Unknown properties are ignored
        // explicitly -- a newer version adding a field must not make its entries unreadable to
        // the older one still running beside it during a rolling deploy.
        this.json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.fromDatabase = resolutionTimer(meters);
        this.hits = lookups(meters, "hit");
        this.misses = lookups(meters, "miss");
        this.unreadable = lookups(meters, "unreadable");
        this.unreachable = lookups(meters, "unreachable");
        this.bypassed = lookups(meters, "bypassed");
    }

    @Override
    public GrantedPermissions resolve(Jwt caller, Supplier<GrantedPermissions> fromDatabase) {
        if (quiet()) {
            bypassed.increment();
            return this.fromDatabase.record(fromDatabase);
        }

        // Reading the version is a database read, deliberately: it is the one number the
        // answer's correctness depends on, and a cached copy of it would be a cache whose
        // staleness nothing could detect.
        String key = keyFor(caller);
        try {
            String entry = valkey.opsForValue().get(key);
            if (entry != null) {
                GrantedPermissions cached = parse(entry, key);
                if (cached != null) {
                    hits.increment();
                    return cached;
                }
            } else {
                misses.increment();
            }
        } catch (RuntimeException e) {
            degrade("read", e);
            return this.fromDatabase.record(fromDatabase);
        }

        GrantedPermissions resolved = this.fromDatabase.record(fromDatabase);
        try {
            valkey.opsForValue().set(key, json.writeValueAsString(CachedGrants.of(resolved)), ttl);
        } catch (Exception e) {
            // The answer is already computed and about to be returned; the whole cost of this
            // failure is a miss next time.
            degrade("write", e);
        }
        return resolved;
    }

    /**
     * Never DOWN, and that is the point: serving from the database is a correct mode, and a
     * service that reported itself unhealthy for a cache outage would be taken out of rotation
     * for a dependency it is built to survive. The detail is what an operator needs — a cache
     * that has quietly stopped serving is otherwise invisible, which is what this and the meters
     * exist for.
     */
    @Override
    public Health health() {
        Health.Builder health = Health.up()
            .withDetail("mode", quiet() ? "database (cache degraded)" : "valkey")
            .withDetail("schema", SCHEMA)
            .withDetail("ttl", ttl.toString());
        String failure = lastFailure.get();
        return failure == null ? health.build() : health.withDetail("lastFailure", failure).build();
    }

    /** {@code authz:v<schema>:<tenant>:<caller>:<authz_version>}. */
    private String keyFor(Jwt caller) {
        // The token's subject, not app_user.id: resolving the id is a database read, and one of
        // the reads this exists to avoid. It is a key, never a stored reference (ADR-0104) -- a
        // re-link changes the subject, which orphans the old entries rather than mis-serving
        // them, and the TTL collects them.
        return "authz:v" + SCHEMA + ":" + TenantContext.require() + ":" + caller.getSubject()
            + ":" + versions.current();
    }

    private GrantedPermissions parse(String entry, String key) {
        try {
            CachedGrants document = json.readValue(entry, CachedGrants.class);
            if (document.schema() != SCHEMA) {
                // Only reachable if something wrote this key by hand: the schema is in the key.
                unreadable.increment();
                return null;
            }
            return document.toGranted();
        } catch (Exception e) {
            LOG.warn("Ignoring an unreadable permission cache entry at {}: {}", key, e.toString());
            unreadable.increment();
            return null;
        }
    }

    private void degrade(String operation, Exception failure) {
        unreachable.increment();
        lastFailure.set(operation + ": " + failure.getClass().getSimpleName());
        quietUntil.set(System.currentTimeMillis() + cooldown.toMillis());
        LOG.warn("Permission cache {} failed; serving from the database for the next {}",
            operation, cooldown, failure);
    }

    private boolean quiet() {
        return System.currentTimeMillis() < quietUntil.get();
    }

    static Timer resolutionTimer(MeterRegistry meters) {
        return Timer.builder("authz.permissions.resolution")
            .description("Resolving a caller's permission set from the database")
            .tag("source", "database")
            .register(meters);
    }

    private static Counter lookups(MeterRegistry meters, String result) {
        return Counter.builder("authz.permissions.cache")
            .description("Permission set lookups, by what the cache could do for them")
            .tag("result", result)
            .register(meters);
    }
}
