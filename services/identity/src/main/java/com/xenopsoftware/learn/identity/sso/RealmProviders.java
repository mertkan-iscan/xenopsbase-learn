package com.xenopsoftware.learn.identity.sso;

/**
 * The realm, as the one thing that may be told about a customer's identity provider (T-1.8).
 *
 * <p><b>The contract that matters is the second sentence of {@link #apply}.</b> Whatever an
 * implementation does with the connection details, it must make the provider stamp the tenant
 * onto every login from our configuration, and it must not let anything the provider asserts
 * reach that attribute. An implementation that merely copies an assertion claim would satisfy
 * every signature here and destroy the boundary, which is why the rule is written on the port
 * rather than left in one adapter.
 *
 * <p>A port because the realm is not always reachable and never should be required: a developer
 * with no Keycloak admin credentials still gets a working stack, the same trade
 * {@code MediaProvider} makes for video.
 */
public interface RealmProviders {

    /**
     * Makes the realm match this provider, creating it or updating it in place.
     *
     * <p>The implementation MUST configure the provider so that every login through it sets the
     * user's {@code tenant_id} to {@link TenantProvider#tenantId()} and {@code side} to
     * {@code TENANT}, from these arguments and never from the assertion — and it must apply on
     * every login rather than only the first, because a provider that changes what it asserts
     * after a user exists must not be able to move them.
     */
    void apply(TenantProvider provider, ProviderSecrets secrets);

    /** Removes the provider from the realm. Users who signed in through it keep their accounts. */
    void remove(String alias);

    /**
     * Whether {@link #apply} actually reached a realm, as opposed to recording the intent.
     *
     * <p>Its own question rather than something inferred from apply returning normally, because
     * "it did not throw" is exactly what an implementation that does nothing also does. The row's
     * {@code applied_at} is written from this, so an administrator reading the API can tell the
     * difference between a provider people can sign in through and one this installation merely
     * knows about.
     */
    default boolean reachesTheRealm() {
        return true;
    }

    /**
     * The parts of a provider's configuration that are credentials or endpoints — separated from
     * {@link TenantProvider} because that one is a row and this one must never become one.
     *
     * @param issuer      OIDC issuer, or the SAML entity id
     * @param clientId    OIDC client id at the customer's provider; null for SAML
     * @param clientSecret OIDC client secret; null for SAML, and never read back out
     * @param metadataUrl SAML metadata descriptor URL; null for OIDC
     */
    record ProviderSecrets(String issuer, String clientId, String clientSecret, String metadataUrl) {}
}
