package com.xenopsoftware.learn.identity.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this installation configures customer identity providers (T-1.8).
 *
 * @param domainVerification {@code dns} (the default and the only one that verifies anything) or
 *                           {@code trusting} for the local stack
 * @param realmAdmin         credentials for the realm's admin API. Absent means providers are
 *                           recorded and not applied, which is a working developer stack rather
 *                           than a broken one
 */
@ConfigurationProperties(prefix = "identity.sso")
public record SsoProperties(String domainVerification, RealmAdmin realmAdmin) {

    public SsoProperties {
        domainVerification = domainVerification == null ? "dns" : domainVerification;
    }

    /**
     * @param clientId     a service account holding {@code manage-identity-providers}, and not
     *                     {@code realm-admin}: this service has no business editing users in the
     *                     realm, and a credential that could is a credential that will
     */
    public record RealmAdmin(String url, String realm, String clientId, String clientSecret) {

        /** Configured enough to try. Half-configured is treated as absent, and said out loud. */
        public boolean isComplete() {
            return notBlank(url) && notBlank(realm) && notBlank(clientId) && notBlank(clientSecret);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
