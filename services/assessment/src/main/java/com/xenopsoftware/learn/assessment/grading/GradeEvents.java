package com.xenopsoftware.learn.assessment.grading;

import com.xenopsoftware.learn.assessment.scoring.TestScore;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every verdict ever reached about an attempt, in the order they were reached (T-6.7).
 *
 * <p>The criterion is "grading is audited with the grader, and a regrade keeps the previous mark",
 * and both fall out of one decision: <b>this table is append-only, and the score columns on
 * {@code attempt} are a cache of its newest row.</b>
 *
 * <p><b>Ordered by a sequence and not by the clock.</b> Two verdicts reached in the same
 * microsecond — a mark and the recompute it triggers — tie on a timestamp, and "newest first" then
 * resolves to whichever id sorted higher, which can show a superseded verdict as the current one.
 * That is the single worst thing an audit can do, and a sequence is a total order where a clock is
 * not. Found by a test that marked and regraded against a stopped clock, which is the same instant
 * a fast machine produces on its own.
 *
 * <p>A regrade overwrites nothing. It adds a row, and the previous verdict stays legible with the
 * person and the moment attached to it — which is the only form in which "we changed this learner's
 * result" is defensible three months later. The database refuses an UPDATE or a DELETE outright,
 * for V2's reason: an audit somebody can edit is not an audit.
 */
@Component
public class GradeEvents {

    /**
     * @param gradedBy null when a machine produced this verdict — on submit, or on the recompute
     *                 that follows a person marking the last outstanding answer
     * @param note     why, when a person did it. A regrade with no reason is a regrade nobody can
     *                 defend
     */
    public record Event(UUID id, UUID attemptId, UUID gradedBy, Grading grading, BigDecimal raw,
                        BigDecimal scaled, Integer percent, Boolean passed, String note,
                        Instant at) {}

    private final JdbcTemplate jdbc;

    public GradeEvents(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Transactional
    public void record(UUID attemptId, UUID gradedBy, Grading grading, TestScore score, String note,
            Instant now) {
        jdbc.update("""
            INSERT INTO grade_event (id, tenant_id, attempt_id, graded_by, grading, score_raw,
                    score_scaled, score_percent, passed, note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), TenantContext.require(), attemptId, gradedBy, grading.name(),
            score.raw(), score.scaled(), (short) score.percent(),
            // Null rather than false while it is unsettled: the whole point of AWAITING_GRADING is
            // that "not yet" is not "no".
            grading.settled() ? score.passed() : null,
            note, Timestamp.from(now));
    }

    /** An attempt's grading history, newest first — what a dispute is read from. */
    @Transactional(readOnly = true)
    public List<Event> of(UUID attemptId) {
        return jdbc.query("""
            SELECT id, attempt_id, graded_by, grading, score_raw, score_scaled, score_percent,
                   passed, note, created_at
              FROM grade_event
             WHERE tenant_id = ? AND attempt_id = ?
             ORDER BY seq DESC
            """, (rows, index) -> new Event(
                rows.getObject("id", UUID.class),
                rows.getObject("attempt_id", UUID.class),
                rows.getObject("graded_by", UUID.class),
                Grading.valueOf(rows.getString("grading")),
                rows.getBigDecimal("score_raw"),
                rows.getBigDecimal("score_scaled"),
                (Integer) rows.getObject("score_percent"),
                (Boolean) rows.getObject("passed"),
                rows.getString("note"),
                rows.getTimestamp("created_at").toInstant()),
            TenantContext.require(), attemptId);
    }
}
