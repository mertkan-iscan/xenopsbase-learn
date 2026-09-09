package com.xenopsoftware.learn.assessment.grading;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is waiting to be marked, and how long it has waited (T-6.7).
 *
 * <h2>How long it has waited is the column that makes this a queue</h2>
 *
 * <p>A list of unmarked attempts is a backlog. The same list with a waiting time is a queue
 * somebody can be held to, and it is the number the person who owns the marking actually asks for.
 * It is measured from {@code submitted_at} rather than from when the last grader looked, because
 * the learner has been waiting since they finished — not since somebody noticed.
 *
 * <h2>Oldest first, and that is not a preference</h2>
 *
 * <p>Any other order lets one attempt sit for ever while newer ones are picked off, and the person
 * it happens to has no way to see that it is happening.
 *
 * <h2>The permission this is not scoped by</h2>
 *
 * <p>The criterion asks for a queue "scoped by permission". It is scoped by tenant and by nothing
 * else, which is the same gap every endpoint in this module carries and for the same reason: the
 * evaluator and its grants live inside {@code identity}, and a separate process cannot ask it
 * anything (T-9.11, ADR-0109). The permission belongs in the catalog with {@code bank:author}, and
 * a local "is this person a grader" shortcut written here would be exactly the special case
 * ADR-0103 exists to refuse — and it would be the thing an endpoint later trusts.
 *
 * <p>So the shape is right and the check is absent, deliberately and visibly. Every authenticated
 * member of a company can currently see what their company owes marking on, which is wrong and is
 * written down rather than discovered.
 */
@Component
public class MarkingQueue {

    /**
     * One attempt waiting for a person.
     *
     * @param waiting how long since the learner finished. The number the queue exists to show
     * @param outstanding how many answers still need marking, so a grader can tell a whole exam
     *                    of essays from an attempt with one loose end
     */
    public record Waiting(UUID attemptId, UUID testId, String testTitle, UUID learnerId,
                          int attemptNumber, Instant submittedAt, Duration waiting,
                          int outstanding) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MarkingQueue(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /**
     * Everything this company owes marking on, oldest first.
     *
     * @param testId optionally narrowed to one test, which is how somebody who owns one exam works
     *               through it without reading everybody else's
     */
    @Transactional(readOnly = true)
    public List<Waiting> waiting(UUID testId, int limit) {
        Instant now = clock.instant();
        String narrowed = testId == null ? "" : " AND a.test_id = ?";
        Object[] arguments = testId == null
            ? new Object[] {TenantContext.require(), limit}
            : new Object[] {TenantContext.require(), testId, limit};

        return jdbc.query("""
            SELECT a.id, a.test_id, t.title, a.learner_id, a.attempt_number, a.submitted_at,
                   (SELECT count(*) FROM attempt_response r
                     WHERE r.attempt_id = a.id AND r.graded_at IS NULL) AS outstanding
              FROM attempt a
              JOIN test t ON t.id = a.test_id
             WHERE a.tenant_id = ? AND a.grading = 'AWAITING_GRADING'
            """ + narrowed + """
             ORDER BY a.submitted_at
             LIMIT ?
            """, (rows, index) -> {
                Instant submitted = rows.getTimestamp("submitted_at").toInstant();
                return new Waiting(
                    rows.getObject("id", UUID.class),
                    rows.getObject("test_id", UUID.class),
                    rows.getString("title"),
                    rows.getObject("learner_id", UUID.class),
                    rows.getInt("attempt_number"),
                    submitted,
                    Duration.between(submitted, now),
                    rows.getInt("outstanding"));
            }, arguments);
    }

    /** How many attempts are waiting — the number a dashboard puts on a tile. */
    @Transactional(readOnly = true)
    public int depth() {
        Integer depth = jdbc.queryForObject("""
            SELECT count(*) FROM attempt
             WHERE tenant_id = ? AND grading = 'AWAITING_GRADING'
            """, Integer.class, TenantContext.require());
        return depth == null ? 0 : depth;
    }
}
