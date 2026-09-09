package com.xenopsoftware.learn.assessment.integrity;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading and writing integrity signals (T-6.8).
 *
 * <p><b>Nothing in the grading path may reach this class</b>, and that is not a convention — an
 * ArchUnit rule in {@code TechnicalStructureTest} fails the build if {@code grading} or
 * {@code scoring} ever depends on this package. "No automatic failure, score reduction or
 * termination from any signal" is a promise, and a promise that only lives in a comment is one
 * somebody keeps until the week they are asked to catch a cheat.
 */
@Component
public class AttemptEvents {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * One signal, as a reviewer reads it.
     *
     * @param reportedAt the browser's clock, which belongs to the learner and can say anything.
     *                   Kept for the order of a burst within one second, and for nothing else
     * @param recordedAt ours. What everything sorts by and what retention is measured from
     */
    public record Event(UUID id, UUID attemptId, IntegritySignal kind, Instant reportedAt,
                        Instant recordedAt, JsonNode detail) {}

    private final JdbcTemplate jdbc;

    public AttemptEvents(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Records one signal.
     *
     * @param recordedAt our clock, supplied rather than defaulted -- see the comment on the insert
     * @return false when the attempt has already produced more than the cap allows, in which case
     *         nothing is written. The caller answers the client normally: refusing would make a
     *         player retry, which is the opposite of what a flood needs
     */
    @Transactional
    public boolean record(UUID attemptId, IntegritySignal kind, Instant reportedAt, JsonNode detail,
            Instant recordedAt, int cap) {
        String tenantId = TenantContext.require();
        Integer already = jdbc.queryForObject(
            "SELECT count(*) FROM attempt_event WHERE tenant_id = ? AND attempt_id = ?",
            Integer.class, tenantId, attemptId);
        if (already != null && already >= cap) {
            return false;
        }
        // recorded_at is written EXPLICITLY, from the service's clock, rather than left to the
        // column default. The default is now() -- the DATABASE's clock -- and retention is measured
        // against the application's, so leaving it would put two clocks either side of one rule.
        // Found by a test that advanced ninety-one days and deleted nothing: the row had been
        // stamped by Postgres at the real wall time and the cutoff was computed from a clock the
        // test had moved. The default stays as a belt for any writer that forgets.
        jdbc.update("""
            INSERT INTO attempt_event (id, tenant_id, attempt_id, kind, reported_at, recorded_at,
                    detail)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """, UUID.randomUUID(), tenantId, attemptId, kind.name(),
            reportedAt == null ? null : Timestamp.from(reportedAt), Timestamp.from(recordedAt),
            detail == null || detail.isNull() ? null : JSON.writeValueAsString(detail));
        return true;
    }

    /**
     * This attempt's signals, in the order they reached us.
     *
     * <p>Ordered by the sequence and not by {@code recorded_at}: a burst arrives inside one
     * millisecond by design -- focus lost, tab hidden, tab visible, focus regained is four rows and
     * one gesture -- and a timestamp cannot separate them. Reading them out of order would show a
     * reviewer somebody's attention wandering in a way that never happened.
     */
    @Transactional(readOnly = true)
    public List<Event> of(UUID attemptId) {
        return jdbc.query("""
            SELECT id, attempt_id, kind, reported_at, recorded_at, detail::text AS detail
              FROM attempt_event
             WHERE tenant_id = ? AND attempt_id = ?
             ORDER BY seq
            """, (rows, index) -> new Event(
                rows.getObject("id", UUID.class),
                rows.getObject("attempt_id", UUID.class),
                IntegritySignal.valueOf(rows.getString("kind")),
                rows.getTimestamp("reported_at") == null
                    ? null : rows.getTimestamp("reported_at").toInstant(),
                rows.getTimestamp("recorded_at").toInstant(),
                rows.getString("detail") == null ? null : JSON.readTree(rows.getString("detail"))),
            TenantContext.require(), attemptId);
    }

    /**
     * Deletes signals older than the retention, across every company.
     *
     * <p>Tenantless on purpose, the way T-6.6's reaper is: retention is one sweep and which company
     * owns a row is something only the row knows. The caller supplies the transaction.
     *
     * @return how many were deleted
     */
    public int forgetOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM attempt_event WHERE recorded_at < ?",
            Timestamp.from(cutoff));
    }
}
