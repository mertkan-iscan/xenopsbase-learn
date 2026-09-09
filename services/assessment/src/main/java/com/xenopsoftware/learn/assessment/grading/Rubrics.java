package com.xenopsoftware.learn.assessment.grading;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
 * The criteria a person marks an answer against (T-6.7).
 *
 * <p><b>On the question, not on the version</b>, and ADR-0106 is the reason rather than an
 * exception to it: a rubric is marking guidance, and an examiner improving how they mark has not
 * changed what anybody was asked. It sits with the internal name and the difficulty on the
 * non-versioned side.
 *
 * <p>What protects the record is not the rubric but the marks against it: {@code
 * response_criterion_mark} says what was awarded per criterion at the time, and a criterion
 * somebody has marked against cannot be deleted. A rubric edited next term cannot rewrite last
 * term's marks.
 *
 * <p><b>The criteria do not have to add up to what the question is worth.</b> A rubric is a way to
 * think — "structure 3, evidence 5, clarity 2" — and how much the question counts for comes from
 * the form (T-6.5) and the section's weighting (T-6.4). {@link #check} is where those two meet, and
 * it scales rather than assuming they agree.
 */
@Component
public class Rubrics {

    /** One thing a grader marks against. */
    public record Criterion(UUID id, UUID questionId, String name, BigDecimal maxPoints,
                            int ordinal) {}

    private final JdbcTemplate jdbc;

    public Rubrics(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Transactional(readOnly = true)
    public List<Criterion> of(UUID questionId) {
        return jdbc.query("""
            SELECT id, question_id, name, max_points, ordinal
              FROM rubric_criterion
             WHERE tenant_id = ? AND question_id = ?
             ORDER BY ordinal, name
            """, (rows, index) -> new Criterion(
                rows.getObject("id", UUID.class),
                rows.getObject("question_id", UUID.class),
                rows.getString("name"),
                rows.getBigDecimal("max_points"),
                rows.getInt("ordinal")),
            TenantContext.require(), questionId);
    }

    /** The rubric of whatever question this served version belongs to. */
    @Transactional(readOnly = true)
    public List<Criterion> forVersion(UUID questionVersionId) {
        UUID questionId = jdbc.query("""
            SELECT question_id FROM question_version WHERE tenant_id = ? AND id = ?
            """, rows -> rows.next() ? rows.getObject(1, UUID.class) : null,
            TenantContext.require(), questionVersionId);
        return questionId == null ? List.of() : of(questionId);
    }

    @Transactional
    public Criterion add(UUID questionId, String name, BigDecimal maxPoints, int ordinal) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A criterion is something a grader can look for, so it has a name.");
        }
        if (maxPoints == null || maxPoints.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A criterion worth nothing is not a criterion: " + maxPoints);
        }
        Criterion criterion = new Criterion(UUID.randomUUID(), questionId, name.strip(), maxPoints,
            ordinal);
        jdbc.update("""
            INSERT INTO rubric_criterion (id, tenant_id, question_id, name, max_points, ordinal,
                    created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, criterion.id(), TenantContext.require(), questionId, criterion.name(), maxPoints,
            (short) ordinal, Timestamp.from(Instant.now()));
        return criterion;
    }

    /**
     * Refuses a mark that does not match the rubric, and answers what the total is.
     *
     * <p>Three rules, and each one is a way a mark can be meaningless rather than merely wrong:
     *
     * <ul>
     *   <li><b>A rubric means the breakdown is required.</b> A single number against a question
     *       whose author wrote three criteria tells a learner nothing about which part they lost,
     *       which is the only thing a rubric is for.
     *   <li><b>Every criterion, and no others.</b> A missing one is a grader who stopped halfway;
     *       an unknown one is a mark against something nobody wrote.
     *   <li><b>None over its maximum.</b> Five out of three is not a generous grader, it is a
     *       number nobody can interpret.
     * </ul>
     *
     * <p>When there is no rubric, {@code awarded} stands on its own — which is the ordinary case
     * for a short written answer nobody wanted a scheme for.
     *
     * @return the mark to store: the sum of the criteria, or {@code awarded} when there is no
     *         rubric
     */
    @Transactional(readOnly = true)
    public BigDecimal check(UUID questionVersionId, BigDecimal awarded,
            Map<UUID, BigDecimal> criterionMarks) {
        List<Criterion> criteria = forVersion(questionVersionId);

        if (criteria.isEmpty()) {
            if (criterionMarks != null && !criterionMarks.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This question has no rubric, so a per-criterion breakdown is a set of numbers "
                    + "against criteria nobody wrote.");
            }
            if (awarded == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A mark is a number. This question has no rubric to derive one from.");
            }
            return awarded;
        }

        if (criterionMarks == null || criterionMarks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This question has a rubric of " + criteria.size() + " criteria. A single number "
                + "tells the learner nothing about which part they lost, which is the only thing a "
                + "rubric is for.");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (Criterion criterion : criteria) {
            BigDecimal mark = criterionMarks.get(criterion.id());
            if (mark == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nothing was marked for '" + criterion.name() + "'. Every criterion is marked "
                    + "or the total means something different from what it says.");
            }
            if (mark.compareTo(criterion.maxPoints()) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'" + criterion.name() + "' is worth at most " + criterion.maxPoints()
                    + " and was given " + mark + ".");
            }
            if (mark.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'" + criterion.name() + "' was given a negative mark.");
            }
            total = total.add(mark);
        }

        List<UUID> known = criteria.stream().map(Criterion::id).toList();
        for (UUID given : criterionMarks.keySet()) {
            if (!known.contains(given)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "There is no criterion " + given + " on this question.");
            }
        }
        return total;
    }
}
