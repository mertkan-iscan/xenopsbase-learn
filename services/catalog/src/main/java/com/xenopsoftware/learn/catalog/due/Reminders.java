package com.xenopsoftware.learn.catalog.due;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which reminders an assignment wants, and which have already gone out (T-5.6).
 *
 * <p><b>{@link #claim} is where the third acceptance criterion lives.</b> A reminder is recorded
 * BEFORE the mail is handed to a provider, and the primary key on {@code reminder_sent} is what
 * makes the claim exclusive — a second instance of this service, or the same instance after a
 * cluster rebuild, finds the row and does nothing. Concurrency is settled by the database rather
 * than by a lock somebody has to remember to take.
 *
 * <p><b>What that costs, stated rather than discovered: reminder mail is AT-MOST-once.</b> A crash
 * between the claim committing and the provider accepting the message loses that reminder and
 * leaves a row saying {@code CLAIMED} forever. That is the deliberate trade. The other order —
 * send, then record — re-sends the entire backlog after any crash, which is the failure the
 * criterion names by its symptom: a week of mail arriving twice. A reminder that did not arrive is
 * visible here; a week of duplicates is visible to the customer.
 *
 * <p>JDBC rather than JPA, because the reminder pass runs on a scheduler with no tenant bound and
 * these tables have composite keys that would be an {@code @IdClass} apiece for no gain.
 */
@Component
public class Reminders {

    private final JdbcTemplate jdbc;

    public Reminders(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A reminder that is due to be sent to one person. */
    public record Due(String tenantId, UUID cycleId, UUID assignmentId, UUID learnerId,
                      int offsetDays, java.time.LocalDate dueOn) {}

    /**
     * The offsets this assignment wants, in days added to the due date.
     *
     * <p>Negative is before, zero is the day itself, positive is a nudge after it has passed —
     * the sign matches a timeline, which an earlier {@code days_before} column did not.
     */
    public List<Integer> offsetsOf(UUID assignmentId) {
        return jdbc.queryForList("""
            SELECT offset_days FROM assignment_reminder WHERE assignment_id = ?
             ORDER BY offset_days
            """, Integer.class, assignmentId);
    }

    /** Replaces the schedule wholesale: it is a set, and a set is easier to reason about. */
    public void setOffsets(String tenantId, UUID assignmentId, List<Integer> offsets) {
        jdbc.update("DELETE FROM assignment_reminder WHERE assignment_id = ?", assignmentId);
        for (Integer offset : List.copyOf(new java.util.LinkedHashSet<>(offsets))) {
            jdbc.update("""
                INSERT INTO assignment_reminder (assignment_id, tenant_id, offset_days)
                VALUES (?, ?, ?)
                """, assignmentId, tenantId, offset);
        }
    }

    /**
     * Claims one reminder, returning whether this caller won it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a select-then-insert: the check and the write
     * are one statement, so two instances racing cannot both see "not sent yet".
     */
    public boolean claim(String tenantId, UUID cycleId, UUID learnerId, int offsetDays,
            Instant now) {
        return jdbc.update("""
            INSERT INTO reminder_sent (tenant_id, cycle_id, learner_id, offset_days, claimed_at,
                                       outcome)
            VALUES (?, ?, ?, ?, ?, 'CLAIMED')
            ON CONFLICT (cycle_id, learner_id, offset_days) DO NOTHING
            """, tenantId, cycleId, learnerId, offsetDays, java.sql.Timestamp.from(now)) == 1;
    }

    /** Records what the provider did with it. Failures are kept, not deleted. */
    public void settle(UUID cycleId, UUID learnerId, int offsetDays, boolean sent, String detail) {
        jdbc.update("""
            UPDATE reminder_sent SET outcome = ?, detail = ?
             WHERE cycle_id = ? AND learner_id = ? AND offset_days = ?
            """, sent ? "SENT" : "FAILED", detail, cycleId, learnerId, offsetDays);
    }

    /** Reminders that were claimed and never left, for the administrator who has to care. */
    public List<Due> unsent(String tenantId) {
        return jdbc.query("""
            SELECT r.tenant_id, r.cycle_id, c.assignment_id, r.learner_id, r.offset_days, c.due_on
              FROM reminder_sent r JOIN assignment_cycle c ON c.id = r.cycle_id
             WHERE r.tenant_id = ? AND r.outcome <> 'SENT'
             ORDER BY r.claimed_at DESC
            """, (rows, index) -> new Due(rows.getString("tenant_id"),
                rows.getObject("cycle_id", UUID.class),
                rows.getObject("assignment_id", UUID.class),
                rows.getObject("learner_id", UUID.class), rows.getInt("offset_days"),
                rows.getObject("due_on", java.time.LocalDate.class)), tenantId);
    }

    /** Every tenant with a live assignment that wants reminders — what a pass iterates. */
    public List<String> tenantsWithReminders() {
        return jdbc.queryForList("""
            SELECT DISTINCT r.tenant_id FROM assignment_reminder r
              JOIN assignment a ON a.id = r.assignment_id
             WHERE a.revoked_at IS NULL AND a.due_kind <> 'NONE'
            """, String.class);
    }
}
