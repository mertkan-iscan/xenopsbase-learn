package com.xenopsoftware.learn.common.messaging;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How a consumer meets the at-least-once contract (T-9.8's fifth criterion).
 *
 * <p>The relay publishes and then marks, so a crash between the two re-sends. That makes duplicate
 * delivery a certainty over a long enough period, not an edge case — and idempotency a requirement
 * on every consumer this platform will ever have rather than a property of the bus.
 *
 * <p><b>A unique key, not a lookup-then-insert.</b> Checking "have I seen this" and then handling
 * it has a window: two deliveries racing both find nothing and both handle. Inserting FIRST and
 * letting the primary key arbitrate has no window, because the database decides. The loser gets a
 * duplicate-key failure, which is the answer.
 *
 * <p>The insert must happen in the SAME transaction as the effect it guards. Committing the mark
 * separately would let a handler fail after the mark and never be retried — a message recorded as
 * handled that did nothing, which is worse than handling it twice.
 */
@Component
public class ConsumedMessages {

    private final JdbcTemplate jdbc;

    public ConsumedMessages(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Claims a message, or reports that somebody already did.
     *
     * @return true when this is the first delivery and the caller should do the work
     */
    public boolean claim(UUID messageId, String subject) {
        try {
            jdbc.update("""
                INSERT INTO consumed_message (message_id, topic, consumed_at)
                VALUES (?, ?, now())
                """, messageId, subject);
            return true;
        } catch (DuplicateKeyException alreadyHandled) {
            return false;
        }
    }
}
