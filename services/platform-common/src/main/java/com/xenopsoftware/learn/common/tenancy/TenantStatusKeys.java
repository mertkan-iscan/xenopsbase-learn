package com.xenopsoftware.learn.common.tenancy;

/**
 * Where identity publishes a tenant's status, written down once (T-1.4, ADR-0111).
 *
 * <p>This class exists because the platform now reads that entry from two web stacks. Every MVC
 * service reads it through {@code PublishedStatusLookup} on a blocking {@code StringRedisTemplate};
 * the gateway reads it through {@code StatusGateWebFilter} on a reactive template, because a
 * blocking Redis call on an event loop is a stall rather than a wait. Two clients are unavoidable.
 * <b>Two spellings of the key are not.</b>
 *
 * <p>The failure this prevents is the quiet one, and this codebase has already produced its twin:
 * a status gate that runs, finds nothing under the key it invented, and waves every request
 * through. Nothing throws, no probe fails, and a suspended company keeps reading until a customer
 * notices. {@code PublishedStatusLookup}'s javadoc describes that exact outcome arriving by a
 * different route.
 *
 * <p>So the key and the vocabulary are here, in the module both stacks depend on, and neither of
 * them spells either one.
 */
public final class TenantStatusKeys {

    private static final String PREFIX = "status:tenant:";

    private TenantStatusKeys() {}

    /** The Valkey key holding {@code tenantId}'s published {@link AccountStatus}. */
    public static String forTenant(String tenantId) {
        return PREFIX + tenantId;
    }

    /**
     * The published value as a status, or {@code null} if this build cannot read it.
     *
     * <p>Null rather than a permissive default, because the two reasons a caller gets nothing are
     * worth telling apart and only the caller can: <b>absent</b> is the normal state of a tenant
     * nobody has ever suspended, and <b>present but unrecognised</b> means a newer build wrote a
     * status this one does not know — which is a deployment skew somebody should see in a log.
     * Both end up ACTIVE at the gate; only one of them is worth a line.
     */
    public static AccountStatus parseOrNull(String published) {
        if (published == null) {
            return null;
        }
        for (AccountStatus status : AccountStatus.values()) {
            if (status.name().equals(published)) {
                return status;
            }
        }
        return null;
    }
}
