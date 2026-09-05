package com.xenopsoftware.learn.catalog.home;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The home screen as the endpoint asks for it: cached, and assembled when it is not (T-5.8).
 *
 * <p>Separate from {@link HomeService} so that the assembly can be read, tested and reasoned about
 * without a cache in the way, and so the cache can be read without the assembly in the way. The
 * split also keeps the transaction where it belongs: assembling is one read-only transaction, and a
 * cache hit opens none at all.
 */
@Service
public class Home {

    private final HomeService assembly;
    private final HomeCache cache;
    private final HomeVersions versions;
    private final Clock clock;

    public Home(HomeService assembly, HomeCache cache, HomeVersions versions, Clock clock) {
        this.assembly = assembly;
        this.cache = cache;
        this.versions = versions;
        this.clock = clock;
    }

    /**
     * This learner's screen.
     *
     * <p>The version is read first and on every request — one indexed row — because it is what
     * makes the cached copy addressable at all. That read is the price of not re-running eleven
     * queries, and it is the whole invalidation mechanism: nothing is deleted, a changed version
     * simply addresses a key nobody has written yet.
     */
    public HomeView of(UUID learnerId) {
        String tenantId = TenantContext.require();
        String version = versions.of(tenantId, learnerId).key();
        return cache.get(tenantId, learnerId, version).orElseGet(() -> {
            HomeView assembled = assembly.forLearner(learnerId, clock.instant());
            cache.put(tenantId, learnerId, version, assembled);
            return assembled;
        });
    }
}
