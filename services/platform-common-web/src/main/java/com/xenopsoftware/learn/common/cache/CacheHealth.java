package com.xenopsoftware.learn.common.cache;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * What every degradable cache in this service is doing, on {@code /management/health}.
 *
 * <h2>Never DOWN, and that is the whole design</h2>
 *
 * <p>The stock Redis indicator answers DOWN when Valkey is unreachable, which is true and
 * useless: these caches are built to be missing, so DOWN on the aggregate would stop a rollout —
 * during a Valkey upgrade, which is exactly when it is most likely to be moving — for a
 * dependency no request needs. Every service therefore turns that indicator off. This one
 * replaces the information it was carrying without the claim that came with it.
 *
 * <p>The gap it closes is a real one. On 2026-09-08 no service in {@code learn} could open a
 * connection to Valkey on the dev cluster, for five hours, and the four signals that existed all
 * said the pods were fine: two services had the stock indicator off, the two that had it on
 * answered 503 on an aggregate nobody reads, and the liveness and readiness groups the kubelet
 * actually probes exclude the cache by design and answered 200 in 7ms. The degradation was
 * logged once per request and changed nothing an operator looks at.
 *
 * <p>So: {@code caches.tenant-status.mode = degraded} appears under an indicator that stays UP,
 * the pod stays in rotation, and the fact is somewhere a person can find it.
 */
@Component("caches")
public class CacheHealth implements HealthIndicator {

    private final DegradableCaches caches;

    public CacheHealth(DegradableCaches caches) {
        this.caches = caches;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        caches.all().forEach(cache -> health.withDetail(cache.name(), cache.report()));
        return health.build();
    }
}
