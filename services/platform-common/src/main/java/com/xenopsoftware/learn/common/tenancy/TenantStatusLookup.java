package com.xenopsoftware.learn.common.tenancy;

/**
 * Where a service learns whether a tenant may act (T-1.4).
 *
 * <p>Two implementations, deliberately different in what they promise. The module that owns the
 * rows answers from them and is authoritative. Every other service reads the published entry in
 * Valkey and is a <b>fast path, not the boundary</b> — it can be stale in the permissive
 * direction for the length of one request, which is why the owner re-checks inside every write
 * transaction rather than trusting what the edge already allowed.
 */
public interface TenantStatusLookup {

    /**
     * The effective status for this tenant, and — where the caller is known — for that person.
     * Never null: a lookup that cannot answer returns {@link AccountStatus#ACTIVE} and says so
     * in its own logs, because a cache outage must not suspend every customer at once.
     */
    AccountStatus statusOf(String tenantId, String idpSub);
}
