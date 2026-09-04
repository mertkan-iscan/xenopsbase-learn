package com.xenopsoftware.learn.identity.impersonation;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Whether this thread is acting as somebody else, and on whose behalf (T-2.8).
 *
 * <p>A second ThreadLocal beside {@code TenantContext}, deliberately not a field on it. The
 * tenant is a property of every request; this is a property of a rare one, and folding an
 * exceptional state into the ordinary one is how the exceptional state gets forgotten by code
 * that only ever meant to read the tenant.
 *
 * <p>Set by {@link ImpersonationFilter} for the length of a request and cleared in a
 * {@code finally} for the reason {@code TenantContext} spells out: threads are pooled, and a
 * session left bound would make the next request on that thread act as a customer's user with a
 * support engineer's audit trail. That is the worst available failure, so the binding is never
 * open-coded — {@link #callWith} is the only way in, and it restores what it found.
 */
public final class ImpersonationContext {

    private static final ThreadLocal<Impersonation> CURRENT = new ThreadLocal<>();

    private ImpersonationContext() {}

    /** The session this thread is acting under, or empty for an ordinary request. */
    public static Optional<Impersonation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The user whose grants decide this request, if somebody is being impersonated. */
    public static Optional<java.util.UUID> impersonatedUserId() {
        return current().map(Impersonation::impersonatedUserId);
    }

    /** Runs {@code body} under {@code session}, restoring whatever was bound before. */
    public static <T> T callWith(Impersonation session, Supplier<T> body) {
        Impersonation previous = CURRENT.get();
        CURRENT.set(session);
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
}
