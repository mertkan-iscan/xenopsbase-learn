package com.xenopsoftware.learn.identity.sso;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What runs when no realm admin credentials are configured (T-1.8).
 *
 * <p>It records the intent and says loudly that nothing was applied, which keeps two things true
 * at once: a developer with no Keycloak admin account still gets a working stack and can exercise
 * the whole configuration path, and nobody can mistake a green local run for a realm that
 * actually has the provider in it. The row's {@code applied_at} stays null, so the API says so
 * too rather than only the log.
 */
public class RecordingRealmProviders implements RealmProviders {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingRealmProviders.class);

    private final Map<String, TenantProvider> applied = new LinkedHashMap<>();

    @Override
    public void apply(TenantProvider provider, ProviderSecrets secrets) {
        applied.put(provider.alias(), provider);
        LOG.warn("NO REALM ADMIN CONFIGURED: provider {} for tenant {} was stored but NOT applied "
            + "to Keycloak. Nobody can sign in through it. Set identity.sso.realm-admin.* to "
            + "apply it for real.", provider.alias(), provider.tenantId());
    }

    @Override
    public void remove(String alias) {
        applied.remove(alias);
        LOG.warn("NO REALM ADMIN CONFIGURED: provider {} was forgotten here but is still in "
            + "Keycloak if it ever reached it.", alias);
    }

    @Override
    public boolean reachesTheRealm() {
        return false;
    }

    /** What this was asked to apply, for the tests that assert the tenant travelled correctly. */
    public Map<String, TenantProvider> applied() {
        return Map.copyOf(applied);
    }
}
