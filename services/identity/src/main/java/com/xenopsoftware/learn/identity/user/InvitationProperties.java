package com.xenopsoftware.learn.identity.user;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long an invitation stands, and how big one import may be (T-1.9).
 *
 * @param ttl           how long a minted token is acceptable. Long enough to survive a weekend
 *                      and a forwarded message, short enough that a token found in a mailbox two
 *                      years later opens nothing
 * @param maxImportRows the ceiling on one file. A number, stated, because the alternative is a
 *                      request that runs until something else decides how big is too big — a
 *                      proxy timeout, a heap, or the database
 */
@ConfigurationProperties(prefix = "identity.invitations")
public record InvitationProperties(Duration ttl, int maxImportRows) {

    public InvitationProperties {
        ttl = ttl == null ? Duration.ofDays(7) : ttl;
        maxImportRows = maxImportRows <= 0 ? 5000 : maxImportRows;
    }
}
