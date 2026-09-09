package com.xenopsoftware.learn.assessment.integrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Behavioural data has its own clock (T-6.8).
 *
 * <p>The criterion asks for retention "stated separately -- they are behavioural data about a
 * person". Separately is the operative word, and this class is what makes it true rather than
 * stated: <b>the signals are deleted while the attempt survives.</b> An attempt is a record of
 * somebody's training and is kept as long as the training matters; a log of when their attention
 * wandered is not, and merging the two clocks would quietly make the shorter one the longer one.
 *
 * <p>Ninety days by default: long enough that a dispute about a result can be raised and reviewed,
 * short enough that this is not a permanent behavioural record of a person.
 *
 * <p>Tenantless, and for T-6.6's reason -- the one its own reaper's first version got wrong. A
 * retention sweep belongs to no company: it is one pass across every row, and which company owns a
 * row is something only the row knows. Running it through JPA would throw
 * {@code no tenant identifier specified} on every scheduled run while every test passed.
 */
@Component
public class AttemptEventReaper {

    private static final Logger LOG = LoggerFactory.getLogger(AttemptEventReaper.class);

    private final AttemptEvents events;
    private final IntegrityService integrity;
    private final TransactionTemplate transactions;

    public AttemptEventReaper(AttemptEvents events, IntegrityService integrity,
            TransactionTemplate tenantlessTransactions) {
        this.events = events;
        this.integrity = integrity;
        this.transactions = tenantlessTransactions;
    }

    @Scheduled(fixedDelayString = "${assessment.integrity.sweep-interval:PT6H}")
    public void scheduledSweep() {
        int forgotten = sweep();
        if (forgotten > 0) {
            LOG.info("Forgot {} integrity signal(s) past their retention", forgotten);
        }
    }

    /**
     * @return how many signals this pass deleted
     */
    public int sweep() {
        return transactions.execute(status -> events.forgetOlderThan(integrity.retentionCutoff()));
    }
}
