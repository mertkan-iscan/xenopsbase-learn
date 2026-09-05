package com.xenopsoftware.learn.catalog.due;

import com.xenopsoftware.learn.catalog.assign.Assignment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who an assignment reaches, and when it started reaching them (T-5.6).
 *
 * <p><b>The second half is what makes {@link DueBasis#REACHED} possible.</b> "Within 30 days of
 * joining" needs a date for the joining, and a group assignment made a year before somebody arrived
 * has no such date on it — it is on the membership. So {@code learner_group_reach} carries a
 * {@code reached_at}, and this is what reads it.
 *
 * <p><b>Reached is the later of the two events.</b> Somebody who was already in the group when the
 * assignment was made is reached when the assignment is made, not when they joined the group two
 * years ago — otherwise a new onboarding course would land on the whole department already thirty
 * days overdue.
 *
 * <p>Resolved at read time and never materialised. Assigning a course to a five thousand person
 * department is still one row (T-5.5); this expands it when somebody needs the list, which is a
 * reminder pass and a compliance report and nothing on the hot path.
 */
@Component
public class Audience {

    private final JdbcTemplate jdbc;

    public Audience(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** One person this assignment reaches, and the moment it began to. */
    public record Reached(UUID learnerId, Instant reachedAt) {}

    public List<Reached> of(Assignment assignment) {
        String tenantId = assignment.getTenantId();
        Instant assignedAt = assignment.getAssignedAt();
        List<Reached> reached = switch (assignment.getTargetType()) {
            case USER -> List.of(new Reached(assignment.getTargetId(), assignedAt));
            case GROUP -> jdbc.query("""
                SELECT learner_id, min(reached_at) AS reached_at
                  FROM learner_group_reach
                 WHERE tenant_id = ? AND group_id = ?
                 GROUP BY learner_id
                """, (rows, index) -> new Reached(rows.getObject("learner_id", UUID.class),
                    rows.getTimestamp("reached_at").toInstant()), tenantId,
                    assignment.getTargetId());
            // Everybody catalog has heard of in this company. That is a projection of identity's
            // users and not identity's own list, so a person who exists but has never had a
            // profile event is missing here -- which is the same gap the group case has, and the
            // reason identity publishes a profile for every user rather than only on change.
            case TENANT -> jdbc.query("""
                SELECT learner_id, first_seen_at FROM learner_profile WHERE tenant_id = ?
                """, (rows, index) -> new Reached(rows.getObject("learner_id", UUID.class),
                    rows.getTimestamp("first_seen_at").toInstant()), tenantId);
        };

        List<Reached> notBeforeTheAssignment = new ArrayList<>(reached.size());
        for (Reached person : reached) {
            notBeforeTheAssignment.add(person.reachedAt().isBefore(assignedAt)
                ? new Reached(person.learnerId(), assignedAt)
                : person);
        }
        return List.copyOf(notBeforeTheAssignment);
    }
}
