package com.xenopsoftware.learn.catalog.assign;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The little identity knows about a learner that catalog needs to reach them (T-5.6).
 *
 * <p>A timezone, an address and a name — projected from identity's events, the same shape as
 * {@link LearnerGroupReach} beside it. Three reasons it is a copy rather than a call:
 *
 * <ul>
 *   <li><b>Catalog must not read identity's schema</b> (ADR-0109), and identity owns the
 *       person (ADR-0104).</li>
 *   <li><b>This is read on the screen a learner opens first.</b> A home screen that fails when
 *       identity is slow is a home screen that fails.</li>
 *   <li><b>The reminder pass reads it for everybody it is about to mail.</b> One call per learner
 *       across a company would make a nightly job into a denial of service against identity.</li>
 * </ul>
 *
 * <p><b>It holds a copy of an email address, which is worth saying out loud.</b> That is personal
 * data living in a second place, and the deletion path has to know: when identity erases a person
 * it publishes the change, and the handler removes the row like any other update. The alternative
 * — asking identity for an address at send time — trades that for a mail run that stops when
 * identity does, and for a service-to-service call per recipient.
 */
@Component
public class LearnerProfiles {

    private final JdbcTemplate jdbc;

    public LearnerProfiles(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * What catalog knows about one person.
     *
     * @param timeZone an IANA zone id, or null when they have not told us — which is a different
     *                 state from UTC, and the fallback is applied where the deadline is computed
     *                 rather than here
     */
    public record Profile(UUID learnerId, String timeZone, String email, String displayName,
                          Instant firstSeenAt) {}

    public Optional<Profile> of(String tenantId, UUID learnerId) {
        return jdbc.query("""
            SELECT learner_id, time_zone, email, display_name, first_seen_at
              FROM learner_profile WHERE tenant_id = ? AND learner_id = ?
            """, rows -> rows.next() ? Optional.of(read(rows)) : Optional.<Profile>empty(),
            tenantId, learnerId);
    }

    /** Many at once, for a pass that is about to mail a department. */
    public Map<UUID, Profile> allOf(String tenantId, java.util.Collection<UUID> learnerIds) {
        if (learnerIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(learnerIds.size(), "?"));
        Object[] arguments = new Object[learnerIds.size() + 1];
        arguments[0] = tenantId;
        int index = 1;
        for (UUID learnerId : learnerIds) {
            arguments[index++] = learnerId;
        }
        List<Profile> found = jdbc.query("""
            SELECT learner_id, time_zone, email, display_name, first_seen_at
              FROM learner_profile WHERE tenant_id = ? AND learner_id IN (%s)
            """.formatted(placeholders), (rows, row) -> read(rows), arguments);
        return found.stream().collect(java.util.stream.Collectors.toMap(Profile::learnerId,
            profile -> profile));
    }

    /**
     * Applies what identity said. Idempotent by being a whole-row replacement.
     *
     * <p>The event carries the person's current profile rather than a delta, so applying it twice
     * leaves the same state as applying it once — the property the at-least-once bus requires,
     * achieved by the shape of the message rather than by the handler being careful.
     */
    public void put(String tenantId, UUID learnerId, String timeZone, String email,
            String displayName, Instant updatedAt) {
        jdbc.update("""
            INSERT INTO learner_profile (tenant_id, learner_id, time_zone, email, display_name,
                                         first_seen_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, learner_id) DO UPDATE
               SET time_zone = EXCLUDED.time_zone, email = EXCLUDED.email,
                   display_name = EXCLUDED.display_name, updated_at = EXCLUDED.updated_at
             WHERE learner_profile.updated_at <= EXCLUDED.updated_at
            """, tenantId, learnerId, timeZone, email, displayName,
            java.sql.Timestamp.from(updatedAt), java.sql.Timestamp.from(updatedAt));
    }

    /** Forgets a person, for the erasure identity announces. */
    public void remove(String tenantId, UUID learnerId) {
        jdbc.update("DELETE FROM learner_profile WHERE tenant_id = ? AND learner_id = ?",
            tenantId, learnerId);
    }

    private static Profile read(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new Profile(rows.getObject("learner_id", UUID.class), rows.getString("time_zone"),
            rows.getString("email"), rows.getString("display_name"),
            rows.getTimestamp("first_seen_at").toInstant());
    }
}
