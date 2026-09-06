package com.xenopsoftware.learn.common.tenancy;

/**
 * What a tenant, a group or a person is currently allowed to do (T-1.4).
 *
 * <p>Three states, and <b>the middle one is the useful one</b>: a wind-down or a payment dispute
 * needs a customer who can still read and export their data. Without it the only tool is a switch
 * that also destroys their ability to get their data out, which makes it a switch nobody is
 * willing to throw.
 *
 * <p>Ordered worst-last, so combining a chain is a maximum.
 */
public enum AccountStatus {

    /** Everything the caller holds a permission for. */
    ACTIVE,

    /** Reads and exports work. Writes do not, and neither do new playback tokens (T-3.4). */
    READ_ONLY,

    /** Everything is refused. */
    SUSPENDED;

    /** The effective status of a chain: the worst link decides. */
    public static AccountStatus worstOf(AccountStatus... chain) {
        AccountStatus worst = ACTIVE;
        for (AccountStatus link : chain) {
            if (link != null && link.ordinal() > worst.ordinal()) {
                worst = link;
            }
        }
        return worst;
    }

    public boolean permitsReads() {
        return this != SUSPENDED;
    }

    public boolean permitsWrites() {
        return this == ACTIVE;
    }

    /** The code a UI can branch on, rather than a sentence it would have to parse. */
    public String reasonCode() {
        return switch (this) {
            case ACTIVE -> "ACTIVE";
            case READ_ONLY -> "ACCOUNT_READ_ONLY";
            case SUSPENDED -> "ACCOUNT_SUSPENDED";
        };
    }

    /**
     * What a refused caller is told, given whether they were trying to change something.
     *
     * <p>Here rather than in the filter because two filters now produce it — the servlet
     * {@code StatusGateFilter} in every MVC service and the gateway's {@code StatusGateWebFilter}
     * at the edge (ADR-0111). A learner refused by the edge and a learner refused one hop in must
     * read the same sentence; two copies of it drift the first time one is reworded, and the
     * result is a product that describes the same state two ways depending on which process
     * happened to catch it.
     */
    public String messageFor(boolean write) {
        if (this == SUSPENDED) {
            return "This account is suspended. Contact your administrator.";
        }
        return write
            ? "This account is read only. You can view and export, but not change anything."
            : "This account is read only.";
    }

    /**
     * The refusal body, byte for byte, for both stacks.
     *
     * <p>Machine-readable because a UI has to say something true, and a message alone would make
     * "suspended" and "read only" indistinguishable without parsing prose. Built by string rather
     * than by a serialiser deliberately: this is the one response that has to be identical from a
     * WebFlux process and an MVC one, and going through each stack's own Jackson configuration is
     * exactly how two identical-looking bodies end up differing in field order or escaping.
     */
    public String refusalBody(boolean write) {
        return "{\"error\":{\"code\":\"" + reasonCode()
            + "\",\"message\":\"" + messageFor(write) + "\"}}";
    }

    /** Whether an HTTP method is a change, for the gate. {@code GET}/{@code HEAD}/{@code OPTIONS} are not. */
    public static boolean isWrite(String httpMethod) {
        if (httpMethod == null) {
            return true;
        }
        return switch (httpMethod.toUpperCase(java.util.Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> false;
            default -> true;
        };
    }
}
