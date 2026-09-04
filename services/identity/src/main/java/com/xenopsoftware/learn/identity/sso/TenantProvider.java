package com.xenopsoftware.learn.identity.sso;

import java.time.Instant;

/**
 * One customer's identity provider, as the row that decides which company its people are in
 * (T-1.8).
 *
 * @param alias       the Keycloak alias. Unique across the installation, which is what makes
 *                    "which provider authenticated this person" a complete answer to "which
 *                    company are they in"
 * @param tenantId    the company. Ours, not theirs — nothing in an assertion can reach it
 * @param kind        OIDC or SAML
 * @param displayName what a learner would read on a button
 * @param appliedAt   when the realm was last made to match this row, or null if it never was
 */
public record TenantProvider(String alias, String tenantId, ProviderKind kind, String displayName,
                             Instant appliedAt) {

    /**
     * Aliases are ours to shape, so they are shaped narrowly: they end up in URLs, in realm
     * configuration and in log lines, and a lenient rule here is the one that later turns into
     * an escaping question in three places.
     */
    public static final java.util.regex.Pattern ALIAS =
        java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");
}
