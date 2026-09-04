package com.xenopsoftware.learn.common.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The status fast path for any service that does not own the rows (T-1.4).
 *
 * <p>Shared rather than copied: every service except {@code identity} answers this question the
 * same way — read the entry identity publishes — and two copies of it would be two places for
 * the key to drift.
 *
 * <p>Registered unconditionally, and that is a correction rather than a preference. This class
 * used to carry {@code @ConditionalOnBean(StringRedisTemplate.class)} and
 * {@code @ConditionalOnMissingBean(TenantStatusLookup.class)}, which reads exactly right and
 * does not work: those conditions are for auto-configuration, and on a component-scanned bean
 * they are evaluated while user components are being registered — before
 * {@code RedisAutoConfiguration} has contributed the template. The condition was therefore
 * always false, this bean never existed anywhere, and <b>every service except identity ran with
 * no status gate at all</b>: {@code StatusGateFilter} logged its "no lookup configured" warning
 * on every startup and waved every request through. A suspension stopped writes in identity and
 * nothing else, which is most of what T-1.4 exists to prevent. Found by T-3.4, whose own status
 * check reported a suspended company as ACTIVE.
 *
 * <p>So: no conditions, an {@link ObjectProvider} for the template that may genuinely be absent,
 * and identity's own lookup marked {@code @Primary} because it owns the rows. Both beans now
 * exist in identity and the authoritative one wins by declaration instead of by registration
 * order, which is not something to depend on.
 *
 * <p>Missing or unreadable means ACTIVE, deliberately. A cache outage that suspended every
 * customer at once would be a worse failure than one that briefly lets a suspended customer
 * read, and the writes that matter are refused by the module that owns the rows.
 */
@Component
public class PublishedStatusLookup implements TenantStatusLookup {

    private static final Logger LOG = LoggerFactory.getLogger(PublishedStatusLookup.class);

    private final StringRedisTemplate valkey;

    public PublishedStatusLookup(ObjectProvider<StringRedisTemplate> valkey) {
        this.valkey = valkey.getIfAvailable();
        if (this.valkey == null) {
            LOG.warn("No Valkey template, so this service cannot read the published tenant "
                + "status and treats every account as active (T-1.4). Only the module owning "
                + "the rows enforces status here.");
        }
    }

    @Override
    public AccountStatus statusOf(String tenantId, String idpSub) {
        if (valkey == null) {
            return AccountStatus.ACTIVE;
        }
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
