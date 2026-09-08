package com.xenopsoftware.learn.common.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Every cache in this service that is allowed to be missing, in one place.
 *
 * <p>A caller registers once at construction and keeps the {@link DegradableCache} it is given.
 * The point of the registry is not the registration — it is that {@code /management/health} can
 * name every cache the service will silently run without, including the ones that have never
 * failed, so "which caches does this process depend on and are any of them gone" is one request
 * rather than a reading of four classes.
 *
 * <p>Unconditional, and deliberately so: {@code @ConditionalOnBean} on a component-scanned bean
 * is evaluated before auto-configuration has contributed anything, so the condition is always
 * false and the bean silently never exists. That mistake cost this repository the status gate in
 * every service but one (see {@code PublishedStatusLookup}); it is not repeated here.
 */
@Component
public class DegradableCaches {

    private final Duration defaultCooldown;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Map<String, DegradableCache> registered = new ConcurrentSkipListMap<>();

    // @Autowired because there are two constructors and the other one is for tests. With more
    // than one candidate Spring picks neither: it looks for this annotation and, not finding it,
    // falls back to a no-arg constructor that does not exist, so every context in the repository
    // fails with "No default constructor found" and names this class rather than the count.
    @Autowired
    public DegradableCaches(
            @Value("${platform.cache.cooldown:PT30S}") Duration defaultCooldown,
            ObjectProvider<MeterRegistry> meters) {
        this(defaultCooldown, Clock.systemUTC(), meters.getIfAvailable());
    }

    DegradableCaches(Duration defaultCooldown, Clock clock, MeterRegistry meters) {
        this.defaultCooldown = defaultCooldown;
        this.clock = clock;
        this.meters = meters;
    }

    /** A cache that exists, on the shared cooldown. */
    public DegradableCache register(String name) {
        return register(name, defaultCooldown, Map.of());
    }

    /**
     * A cache that exists, on its own cooldown.
     *
     * @param constants details that describe this cache and never change — a schema version, a
     *                  TTL. They appear beside its mode so that reading the health document does
     *                  not need the class that wrote it.
     */
    public DegradableCache register(String name, Duration cooldown, Map<String, Object> constants) {
        return add(new DegradableCache(name, cooldown, constants, null, clock));
    }

    /**
     * A cache that is not configured at all, and the one-line reason.
     *
     * <p>Registered rather than skipped, because "this service has no Valkey" is exactly as worth
     * seeing as "this service cannot reach the one it has", and a cache that is absent from the
     * health document is indistinguishable from one that is fine.
     */
    public DegradableCache absent(String name, String why) {
        return add(new DegradableCache(name, Duration.ZERO, Map.of(), why, clock));
    }

    private DegradableCache add(DegradableCache cache) {
        registered.put(cache.name(), cache);
        if (meters != null) {
            // 1 while this cache is not being consulted. The health detail is what a person
            // reads; this is what an alert would read, and it is deliberately registered even
            // though nothing can scrape it yet -- every SecurityConfiguration in this repository
            // permits only /management/health and /management/info, which is T-9.13's (#91)
            // decision to make. A meter that appears at the same time as the scrape is a meter
            // with no history on the day it is first needed.
            Gauge.builder("platform.cache.degraded", cache, c -> c.quiet() ? 1 : 0)
                .description("1 while this cache is being skipped, 0 while it is being used")
                .tag("cache", cache.name())
                .register(meters);
        }
        return cache;
    }

    /** Every registered cache, ordered by name so the health document is stable. */
    public Collection<DegradableCache> all() {
        return registered.values();
    }
}
