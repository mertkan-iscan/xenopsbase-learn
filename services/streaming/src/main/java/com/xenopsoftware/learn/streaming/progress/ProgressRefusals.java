package com.xenopsoftware.learn.streaming.progress;

import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record of a batch that was not credited, which has to survive the refusal that produced it
 * (T-3.7, ADR-0107).
 *
 * <p>A rejected batch rolls its transaction back — that is the point of rejecting it — so a count
 * written inside that transaction rolls back with it. These run in their own, exactly as
 * {@code PlaybackAudit} does for a refused token and for the same reason: the record of a refusal
 * must not depend on the refusal succeeding at anything.
 *
 * <p><b>Its own table, and not two columns on the progress row.</b> The rejecting transaction is
 * holding that row under {@code SELECT ... FOR UPDATE} while it waits for this one to return, so a
 * new transaction updating the same row waits for a lock that cannot be released until it does.
 * Postgres reports that as a request that never comes back rather than as a deadlock, which is why
 * it is worth a paragraph rather than a line — this repository has paid for the same shape once
 * already, provisioning against {@code audit_log}.
 *
 * <p>Why any of it is written down when Micrometer already counts it: no meter in this repository
 * is scrapeable, because every service permits only {@code /management/health} and
 * {@code /management/info} (T-9.13 owns that decision). Until it is decided, "why is this one
 * learner's progress not moving" would be unanswerable from a dashboard nobody can read.
 */
@Component
public class ProgressRefusals {

    private static final Logger LOG = LoggerFactory.getLogger(ProgressRefusals.class);

    private final JdbcTemplate jdbc;

    public ProgressRefusals(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String tenantId, UUID learnerId, UUID nodeId, ProgressRejection reason,
            String detail) {
        try {
            jdbc.update("""
                INSERT INTO progress_refusal (id, tenant_id, learner_id, node_id, reason, detail,
                        created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, learnerId, nodeId, reason.name(), detail);
        } catch (RuntimeException e) {
            // The refusal stands either way. Losing the record is worse than losing nothing and
            // better than turning a refusal into a 500 the client would retry forever.
            LOG.error("Could not record the refused batch for node {} ({})", nodeId, reason, e);
        }
    }
}
