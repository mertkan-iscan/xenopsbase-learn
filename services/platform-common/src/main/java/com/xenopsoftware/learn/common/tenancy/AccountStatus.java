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
}
