package com.xenopsoftware.learn.identity.tenant;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantStatusLookup;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Identity's own status lookup: authoritative from its tables, and the writer of the entry every
 * other service reads (T-1.4).
 *
 * <p><b>Written through rather than version-keyed</b>, unlike T-2.5's grants, and the difference
 * is forced rather than chosen. A version-keyed entry is invalidated by bumping a number the
 * reader also reads — which works because the grant reader can reach the database for that
 * number. A status reader in another service cannot: it has no tables of its own to ask. So the
 * entry carries the status directly and is rewritten the moment it changes, which is what makes
 * a change effective on the next request everywhere.
 *
 * <p>The trade is stated where it matters: this entry can only ever be stale in the permissive
 * direction, for the length of one request, and the module owning the rows re-checks inside every
 * write transaction ({@code StatusGuard}). A cache that could suspend a customer nobody suspended
 * would be worse than one that briefly lets a suspended one read.
 */
@Component
public class PublishedTenantStatus implements TenantStatusLookup {

    /** Long enough to be a cache, short enough that a missed publish repairs itself. */
    static final Duration TTL = Duration.ofMinutes(5);

    private static final Logger LOG = LoggerFactory.getLogger(PublishedTenantStatus.class);

    private final StringRedisTemplate valkey;
    private final EffectiveStatus effective;

    public PublishedTenantStatus(StringRedisTemplate valkey, EffectiveStatus effective) {
        this.valkey = valkey;
        this.effective = effective;
    }

    @Override
    public AccountStatus statusOf(String tenantId, String idpSub) {
        // Identity owns the rows, so it answers from them. The published entry exists for the
        // services that cannot. Membership rather than the whole chain: the person's own status
        // is DeactivatedUserFilter's to refuse, with a reason of its own (T-1.9).
        AccountStatus status = effective.ofMembership(tenantId, idpSub);
        publish(tenantId, effective.ofTenant(tenantId));
        return status;
    }

    /** Rewrites the entry other services read. Called on every status change (T-1.4). */
    public void publish(String tenantId, AccountStatus status) {
        try {
            valkey.opsForValue().set(key(tenantId), status.name(), TTL);
        } catch (RuntimeException valkeyDown) {
            // The fast path being unavailable degrades the edge to permissive, and the write
            // transactions still refuse. Loud, because it means suspensions are only being
            // enforced at the boundary until it is back.
            LOG.warn("Could not publish status for tenant {}; the edge fast path is stale until "
                + "Valkey returns. Writes are still refused by the database check.", tenantId, valkeyDown);
        }
    }

    static String key(String tenantId) {
        return "status:tenant:" + tenantId;
    }
}
