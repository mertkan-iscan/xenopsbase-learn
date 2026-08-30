package com.xenopsoftware.learn.identity.authz;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Every resolution from the database, for an installation that has turned the cache off (T-2.5)
 * — and the behaviour the cached implementation degrades into, which is why it is a real
 * implementation rather than a flag inside the other one.
 *
 * <p>It still records the resolution timer. A deployment running without a cache is exactly the
 * one whose resolution cost somebody will want to know, and a metric that appears only when the
 * cache is on is a metric missing when it matters.
 */
public class UncachedPermissions implements CachedPermissions, HealthIndicator {

    private final Timer fromDatabase;

    public UncachedPermissions(MeterRegistry meters) {
        this.fromDatabase = ValkeyPermissions.resolutionTimer(meters);
    }

    @Override
    public GrantedPermissions resolve(Jwt caller, Supplier<GrantedPermissions> fromDatabase) {
        return this.fromDatabase.record(fromDatabase);
    }

    @Override
    public Health health() {
        return Health.up().withDetail("mode", "database (cache disabled)").build();
    }
}
