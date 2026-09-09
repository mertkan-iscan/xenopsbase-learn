package com.xenopsoftware.learn.assessment.attempt;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nothing stays open for ever (T-6.6).
 *
 * <p>The criterion: "an abandoned attempt reaches a terminal state <b>on a schedule</b> rather than
 * staying open forever". Two different situations reach it, and both are needed:
 *
 * <ul>
 *   <li><b>A timed attempt whose clock ran out.</b> It is ended the next time the learner touches
 *       it — but a learner who never comes back leaves a row saying {@code IN_PROGRESS} with a
 *       deadline in the past. Every report counting live attempts would be wrong, and the partial
 *       unique index would keep refusing that learner a new attempt at a test they finished with.
 *   <li><b>An untimed attempt nobody came back to.</b> Nothing else can ever end this one: there is
 *       no deadline to pass. Without a sweep it is open until the company stops existing.
 * </ul>
 *
 * <h2>Why the two get different states</h2>
 *
 * <p>{@code EXPIRED} is a fact about the test — the time you were given ran out. {@code ABANDONED}
 * is a fact about the learner — you walked away from something with no deadline. A report that
 * merged them could not tell "everybody runs out of time on question forty" from "half of them
 * never finish", and those have opposite fixes.
 *
 * <h2>Plain JDBC and a tenantless transaction, and this is the interesting part</h2>
 *
 * <p>The first version of this class used the JPA repository, and <b>every scheduled run threw</b>:
 * {@code SessionFactory configured for multi-tenancy, but no tenant identifier specified}. The
 * tests all passed, because they called the sweep inside a tenant the way a request would. The
 * production path — the only path this criterion is about — was broken from the first commit and
 * announced it once every five minutes into a log nobody was reading.
 *
 * <p>A reaper genuinely belongs to no tenant: it is one sweep across every company, and which
 * company owns a lapsed attempt is something only the row knows. That is exactly what streaming's
 * {@code TenantlessTransactionConfiguration} exists for, so this uses it — explicitly, and named,
 * so that stepping outside the discriminator is a decision somebody made rather than a default.
 *
 * <p>Two statements rather than a read and a loop, which also removes the "load every lapsed
 * attempt into memory" shape a Monday-morning sweep would have found.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not grade. Grading is T-6.7's (#66) and hangs off the same transition, whether the
 * learner made it or this class did. It does not discard answers either: an attempt reaped at its
 * deadline is marked from what was saved before it, which is all there can be — the save path
 * refuses anything later.
 */
@Component
public class AttemptReaper {

    private static final Logger LOG = LoggerFactory.getLogger(AttemptReaper.class);

    /**
     * Conditional on {@code IN_PROGRESS}, so a learner submitting at the same moment as this pass
     * cannot produce two endings. Whoever reaches the row first wins; the other updates nothing.
     */
    private static final String END_LAPSED = """
        UPDATE attempt
           SET state = ?, submitted_at = ?, updated_at = ?
         WHERE state = 'IN_PROGRESS'
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AttemptProperties properties;
    private final Clock clock;

    public AttemptReaper(DataSource dataSource, TransactionTemplate tenantlessTransactions,
            AttemptProperties properties, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = tenantlessTransactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${assessment.attempt.interval:PT5M}")
    public void scheduledSweep() {
        int ended = sweep();
        if (ended > 0) {
            LOG.info("Ended {} attempt(s) that had lapsed", ended);
        }
    }

    /**
     * @return how many attempts this pass ended
     */
    public int sweep() {
        Instant now = clock.instant();
        Instant abandonedBefore = now.minus(properties.abandonAfter());
        Timestamp at = Timestamp.from(now);

        return transactions.execute(status -> {
            int expired = jdbc.update(END_LAPSED
                + " AND expires_at IS NOT NULL AND expires_at <= ?",
                Attempt.State.EXPIRED.name(), at, at, at);
            int abandoned = jdbc.update(END_LAPSED
                + " AND expires_at IS NULL AND started_at <= ?",
                Attempt.State.ABANDONED.name(), at, at, Timestamp.from(abandonedBefore));
            return expired + abandoned;
        });
    }
}
