package com.xenopsoftware.learn.common.tenancy;

/**
 * The current tenant, per request (T-1.1).
 *
 * <p><b>Unlike the stemcell's seam, this one is not inert.</b> There it is a shape waiting to be
 * activated, because a template must behave as a single-tenant application until something says
 * otherwise. Here multi-tenancy is the product, so a request that reaches domain code without a
 * tenant is a bug rather than a default, and {@link #require()} says so instead of quietly
 * substituting one.
 *
 * <h2>Where the value may come from</h2>
 *
 * A verified claim in the JWT. Nowhere else. A tenant taken from a header, a query parameter or a
 * request body is a tenant the caller chooses, which is not a boundary at all — it is a
 * cross-tenant read with extra steps, and it will be added by someone reasonable who needs it for
 * testing.
 *
 * <h2>Why clearing is not optional</h2>
 *
 * Threads are pooled. Failing to clear leaks one request's tenant into the next request that
 * reuses the thread. That reads as data from the wrong tenant appearing intermittently under load
 * — no error, no pattern, and impossible to reproduce on a quiet machine. {@link TenantFilter}
 * clears in a {@code finally} for that reason.
 *
 * <p>Thread pools also do not inherit a {@code ThreadLocal}. Anything that hands work to another
 * thread — {@code @Async}, a message consumer, a scheduled job — has to carry the tenant across
 * explicitly. There is no ambient mechanism that does it for you.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** The current tenant, or {@code null} outside a tenant-scoped request. */
    public static String get() {
        return CURRENT.get();
    }

    /**
     * The current tenant, or a failure.
     *
     * <p>Used by anything that reads or writes tenant-scoped data. The alternative — falling back
     * to a default — is what turns a wiring mistake into a silent cross-tenant write.
     */
    public static String require() {
        String tenant = CURRENT.get();
        if (tenant == null) {
            throw new IllegalStateException(
                "No tenant bound to this thread. Either the request never passed TenantFilter, " +
                "or work was handed to another thread without carrying the tenant across."
            );
        }
        return tenant;
    }

    public static void set(String tenant) {
        CURRENT.set(tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code body} bound to {@code tenant} without the checked exception — for callers
     * inside a transaction, where an unchecked failure is what rolls it back.
     *
     * <p>Binding a tenant other than the request's own is deliberate and rare: platform-side
     * provisioning creating a company (T-1.5) is the one place today. It is not a way around
     * T-1.1's rule, because the tenant here is not something a caller sent — it is the one being
     * created.
     */
    public static <T> T callWithUnchecked(String tenant, java.util.function.Supplier<T> body) {
        String previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Runs {@code body} bound to {@code tenant}, restoring whatever was bound before. */
    public static <T> T callWith(String tenant, java.util.concurrent.Callable<T> body) throws Exception {
        String previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            return body.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
