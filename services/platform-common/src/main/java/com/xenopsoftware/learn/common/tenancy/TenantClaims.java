package com.xenopsoftware.learn.common.tenancy;

/**
 * What a verified token says about whose request this is (T-1.1, ADR-0111).
 *
 * <p>These four constants are the vocabulary of every authorization decision on the platform, and
 * until ADR-0111 they were fields on {@code TenantFilter} — a {@code OncePerRequestFilter}. That
 * was fine while every process was a servlet. It stopped being fine the moment the edge was not:
 * the gateway binds the same tenant from the same claim on a reactive stack, and it cannot see a
 * servlet filter's classpath, so it would have had to write {@code "tenant_id"} out again.
 *
 * <p>A second spelling of a claim name is not a tidiness problem. A gateway reading
 * {@code "tenantId"} while every service reads {@code "tenant_id"} does not fail — it binds
 * nothing, and a filter that binds nothing is a boundary that waves requests through. The same
 * failure shape {@link TenantStatusKeys} exists to prevent, one field over.
 *
 * <p>So the vocabulary lives here, in the module both stacks compile against, and the filters on
 * either side are two implementations of one contract rather than two contracts.
 */
public final class TenantClaims {

    /**
     * The claim carrying the tenant. Mapped in the realm onto each client rather than through a
     * shared client scope — declaring a {@code clientScopes} block in a realm import replaces
     * Keycloak's built-in scopes instead of adding to them, which strips {@code sub},
     * {@code preferred_username} and {@code realm_access.roles} from every token issued, with no
     * error anywhere. That is not hypothetical; it happened on the first import of this realm.
     */
    public static final String TENANT_CLAIM = "tenant_id";

    /** {@code PLATFORM} or {@code TENANT}. Decides which side's permissions may even be considered. */
    public static final String SIDE_CLAIM = "side";

    /** The value {@link #SIDE_CLAIM} carries for platform staff, who belong to no customer. */
    public static final String PLATFORM = "PLATFORM";

    /**
     * The tenant platform staff are bound to (T-1.5).
     *
     * <p>This reverses the narrower rule T-1.1 set, and the reason it is safe now is the reason
     * it was unsafe then. T-1.1 bound nothing for platform tokens because a sentinel with no
     * rows behind it is a filter matching nothing — a boundary that silently returns empty. The
     * reserved tenant now exists and platform staff have {@code app_user} rows in it (ADR-0104
     * applies to them too: "who provisioned this company" needs a durable answer), so binding it
     * matches exactly the platform's own data.
     *
     * <p>It is not a skeleton key. A platform caller bound here sees platform rows and no
     * customer's; reaching into a customer's data is a deliberate, audited act (tenant
     * provisioning, and impersonation in T-2.8), never a side effect of being staff.
     */
    public static final String PLATFORM_TENANT = "__platform";

    private TenantClaims() {}
}
