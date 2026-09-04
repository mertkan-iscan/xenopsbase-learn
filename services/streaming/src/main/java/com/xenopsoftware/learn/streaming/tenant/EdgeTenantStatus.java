package com.xenopsoftware.learn.streaming.tenant;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantStatusLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The fast path, for a service with no tables to ask (T-1.4).
 *
 * <p>{@code streaming} cannot compute a status chain: tenants, groups and people are identity's
 * rows, and reading another module's schema is the thing this platform refuses on principle. So
 * it reads the entry identity publishes, and is explicitly <b>not the boundary</b> — the
 * authoritative refusal for a playback token is the entitlement decision itself (T-3.4), which
 * is a call this service makes with the tenant's status in hand once services can ask each other
 * questions (T-9.11).
 *
 * <p>Missing or unreadable means ACTIVE, deliberately. A cache outage that suspended every
 * customer at once would be a worse failure than one that briefly lets a suspended customer
 * read, and the writes that matter are refused by the module that owns the rows.
 */
@Component
public class EdgeTenantStatus implements TenantStatusLookup {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeTenantStatus.class);

    private final StringRedisTemplate valkey;

    public EdgeTenantStatus(StringRedisTemplate valkey) {
        this.valkey = valkey;
    }

    @Override
    public AccountStatus statusOf(String tenantId, String idpSub) {
        try {
            String published = valkey.opsForValue().get("status:tenant:" + tenantId);
            if (published == null) {
                return AccountStatus.ACTIVE;
            }
            for (AccountStatus status : AccountStatus.values()) {
                if (status.name().equals(published)) {
                    return status;
                }
            }
            LOG.warn("Unreadable status entry for tenant {}: {}", tenantId, published);
            return AccountStatus.ACTIVE;
        } catch (RuntimeException valkeyDown) {
            LOG.warn("Could not read the status entry for tenant {}; the edge is permissive "
                + "until Valkey returns", tenantId, valkeyDown);
            return AccountStatus.ACTIVE;
        }
    }
}
