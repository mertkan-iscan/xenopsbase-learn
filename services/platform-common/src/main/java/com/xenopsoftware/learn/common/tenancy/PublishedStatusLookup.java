package com.xenopsoftware.learn.common.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The status fast path for any service that does not own the rows (T-1.4).
 *
 * <p>Shared rather than copied: every service except {@code identity} answers this question the
 * same way — read the entry identity publishes — and two copies of it would be two places for
 * the key to drift.
 *
 * <p>Conditional on a Valkey template existing, and on the service not having a better answer of
 * its own: identity reads its own tables and its lookup wins.
 *
 * <p>Missing or unreadable means ACTIVE, deliberately. A cache outage that suspended every
 * customer at once would be a worse failure than one that briefly lets a suspended customer
 * read, and the writes that matter are refused by the module that owns the rows.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnMissingBean(TenantStatusLookup.class)
public class PublishedStatusLookup implements TenantStatusLookup {

    private static final Logger LOG = LoggerFactory.getLogger(PublishedStatusLookup.class);

    private final StringRedisTemplate valkey;

    public PublishedStatusLookup(StringRedisTemplate valkey) {
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
            LOG.warn("Could not read the status entry for tenant {}; this service is permissive "
                + "until Valkey returns", tenantId, valkeyDown);
            return AccountStatus.ACTIVE;
        }
    }
}
