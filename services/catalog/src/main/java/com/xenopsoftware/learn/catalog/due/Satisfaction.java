package com.xenopsoftware.learn.catalog.due;

import com.xenopsoftware.learn.catalog.assign.ReferenceKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether a learner has already done what an obligation asks (T-5.6).
 *
 * <p>Its reason for existing is small and specific: <b>a reminder must not be sent to somebody who
 * has finished.</b> Nothing in the acceptance criteria says so, and mailing a department to nag
 * people who completed the training last week is the kind of thing that gets reminders switched
 * off entirely.
 *
 * <p><b>Completion within the cycle's window, not ever.</b> Annual training done in 2025 does not
 * satisfy the 2026 cycle, which is the entire point of a cycle having an {@code opens_at}. The
 * window is the cycle's; the check is a date comparison rather than a flag somebody has to reset.
 *
 * <h2>The limit, stated rather than discovered</h2>
 *
 * <p>{@code node_completion} currently carries {@code UNIQUE (learner_id, node_id, state)} — one
 * completion per node, ever. So a learner who finished the 2025 cycle and does the training again
 * in 2026 CANNOT have a second row, and this will keep answering "satisfied" for the new cycle
 * from the old completion. That constraint belongs to completion (T-3.7, in flight), not here, and
 * changing it underneath that work would be worse than naming it: the fix is for the unique key to
 * include the cycle, and it needs the writer and the reader changed together.
 *
 * <p>A {@link ReferenceKind#CONTENT_ITEM} assignment has no node and therefore no completion to
 * read; it answers "not satisfied", which errs towards reminding somebody who has finished rather
 * than towards staying silent about somebody who has not.
 */
@Component
public class Satisfaction {

    private final JdbcTemplate jdbc;

    public Satisfaction(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Which of these learners have satisfied the reference within the window.
     *
     * <p>ONE query for the whole set, whatever its size. A pass that asked per learner would make
     * the cost of a reminder run proportional to the size of the company, which is exactly the
     * shape T-5.5 avoided when it refused to materialise a row per member.
     *
     * @param since only completions at or after this instant count — the cycle's {@code opens_at}
     */
    public java.util.Set<UUID> whoHasFinished(String tenantId, ReferenceKind referenceType,
            UUID referenceId, List<UUID> learnerIds, Instant since) {
        if (learnerIds.isEmpty() || referenceType == ReferenceKind.CONTENT_ITEM) {
            return java.util.Set.of();
        }
        String nodesOfReference = switch (referenceType) {
            case COURSE -> """
                SELECT n.id FROM course_node n
                  JOIN course_module m ON m.id = n.module_id
                 WHERE m.course_id = ? AND n.required
                """;
            case MODULE -> "SELECT n.id FROM course_node n WHERE n.module_id = ? AND n.required";
            case NODE -> "SELECT n.id FROM course_node n WHERE n.id = ?";
            case CONTENT_ITEM -> throw new IllegalStateException("Handled above");
        };
        String placeholders = String.join(",", java.util.Collections.nCopies(learnerIds.size(), "?"));

        // "Has no required node left undone." Expressed as a NOT EXISTS over the missing ones
        // rather than a count comparison, so a course with no required nodes answers "finished"
        // -- which is right: there is nothing they must do.
        String sql = """
            WITH wanted AS (%s)
            SELECT l.learner_id FROM (SELECT unnest(ARRAY[%s]::uuid[]) AS learner_id) l
             WHERE NOT EXISTS (
                   SELECT 1 FROM wanted w
                    WHERE NOT EXISTS (
                          SELECT 1 FROM node_completion c
                           WHERE c.tenant_id = ? AND c.learner_id = l.learner_id
                             AND c.node_id = w.id AND c.recorded_at >= ?))
            """.formatted(nodesOfReference, placeholders);

        Object[] arguments = new Object[learnerIds.size() + 3];
        arguments[0] = referenceId;
        int index = 1;
        for (UUID learnerId : learnerIds) {
            arguments[index++] = learnerId;
        }
        arguments[index++] = tenantId;
        arguments[index] = java.sql.Timestamp.from(since);
        return java.util.Set.copyOf(jdbc.queryForList(sql, UUID.class, arguments));
    }

    /** The single-learner question, for a screen rather than a pass. */
    public boolean hasFinished(String tenantId, ReferenceKind referenceType, UUID referenceId,
            UUID learnerId, Instant since) {
        return whoHasFinished(tenantId, referenceType, referenceId, List.of(learnerId), since)
            .contains(learnerId);
    }
}
