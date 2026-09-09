package com.xenopsoftware.learn.assessment.grading;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * What each answer earned, and who said so (T-6.7).
 *
 * <p>Plain SQL for the reason the other projections here give: these columns are written in bulk by
 * the grader — one statement per answer for a whole attempt — and read back as a set. A mapped
 * collection would invite somebody to load them one at a time inside a loop over an exam.
 */
@Component
public class Marks {

    /** One answer's mark, as grading needs it. */
    public record Mark(UUID responseId, UUID formItemId, UUID questionVersionId,
                       BigDecimal awarded, Integer credited, Integer available,
                       boolean graded, UUID gradedBy, String comment) {}

    private final JdbcTemplate jdbc;

    public Marks(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Every answer in an attempt, with whatever mark it currently carries. */
    @Transactional(readOnly = true)
    public List<Mark> of(UUID attemptId) {
        return jdbc.query("""
            SELECT id, form_item_id, question_version_id, awarded, credited, available,
                   graded_at, graded_by, grader_comment
              FROM attempt_response
             WHERE tenant_id = ? AND attempt_id = ?
             ORDER BY form_item_id
            """, (rows, index) -> new Mark(
                rows.getObject("id", UUID.class),
                rows.getObject("form_item_id", UUID.class),
                rows.getObject("question_version_id", UUID.class),
                rows.getBigDecimal("awarded"),
                (Integer) rows.getObject("credited"),
                (Integer) rows.getObject("available"),
                rows.getTimestamp("graded_at") != null,
                rows.getObject("graded_by", UUID.class),
                rows.getString("grader_comment")),
            TenantContext.require(), attemptId);
    }

    /**
     * Records what the machine decided about one answer.
     *
     * <p>{@code graded_by} stays null: null means a comparison produced this mark, and
     * {@code graded_at} is what separates that from "unmarked". Every export has to be able to say
     * which of the two it is (ADR-0107's rule about sources, applied to answers).
     */
    @Transactional
    public void machineMarked(UUID responseId, BigDecimal awarded, Integer credited,
            Integer available, Instant now) {
        jdbc.update("""
            UPDATE attempt_response
               SET awarded = ?, credited = ?, available = ?, graded_by = NULL, graded_at = ?
             WHERE tenant_id = ? AND id = ?
            """, awarded, credited, available, Timestamp.from(now), TenantContext.require(),
            responseId);
    }

    /**
     * Records what a person decided about one answer.
     *
     * <p>{@code credited} and {@code available} are left alone: they are T-6.3's count of what a
     * comparison found right, and a person marking an essay is not counting parts. Reporting reads
     * {@code awarded} for the mark and those two only where a machine produced them, so writing a
     * made-up pair here would put fiction into item analysis (T-7.7).
     */
    @Transactional
    public void personMarked(UUID responseId, BigDecimal awarded, UUID graderId, String comment,
            Instant now) {
        int updated = jdbc.update("""
            UPDATE attempt_response
               SET awarded = ?, graded_by = ?, graded_at = ?, grader_comment = ?
             WHERE tenant_id = ? AND id = ?
            """, awarded, graderId, Timestamp.from(now), comment, TenantContext.require(),
            responseId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No such answer in this company.");
        }
    }

    /** Replaces the per-criterion breakdown of one marked answer. */
    @Transactional
    public void criterionMarksAre(UUID responseId, Map<UUID, BigDecimal> byCriterion) {
        String tenantId = TenantContext.require();
        jdbc.update("DELETE FROM response_criterion_mark WHERE tenant_id = ? AND response_id = ?",
            tenantId, responseId);
        byCriterion.forEach((criterionId, awarded) -> jdbc.update("""
            INSERT INTO response_criterion_mark (response_id, criterion_id, tenant_id, awarded)
            VALUES (?, ?, ?, ?)
            """, responseId, criterionId, tenantId, awarded));
    }

    /** The breakdown, for the screen that shows a learner why they got what they got. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> criterionMarksOf(UUID responseId) {
        Map<UUID, BigDecimal> marks = new LinkedHashMap<>();
        jdbc.query("""
            SELECT m.criterion_id, m.awarded
              FROM response_criterion_mark m
              JOIN rubric_criterion c ON c.id = m.criterion_id
             WHERE m.tenant_id = ? AND m.response_id = ?
             ORDER BY c.ordinal
            """, rows -> {
                marks.put(rows.getObject("criterion_id", UUID.class), rows.getBigDecimal("awarded"));
            }, TenantContext.require(), responseId);
        return marks;
    }

    /**
     * The answers to one attempt that a person still has to look at.
     *
     * <p>An answer is outstanding when its question has no machine grade and nobody has marked it.
     * Which questions those are is the <b>type's</b> answer, not a list kept here — see
     * {@link GradingService}, which asks {@code grade} and treats an empty result as "a human has
     * to look" (T-6.3 built that seam for exactly this).
     */
    @Transactional(readOnly = true)
    public List<UUID> unmarked(UUID attemptId) {
        List<UUID> outstanding = new ArrayList<>();
        jdbc.query("""
            SELECT id FROM attempt_response
             WHERE tenant_id = ? AND attempt_id = ? AND graded_at IS NULL
            """, rows -> {
                // A block body rather than an expression: `query` is overloaded for a
                // RowCallbackHandler and a ResultSetExtractor, and a lambda that returns a value
                // matches both.
                outstanding.add(rows.getObject("id", UUID.class));
            }, TenantContext.require(), attemptId);
        return outstanding;
    }
}
