package com.xenopsoftware.learn.catalog.home;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a cached home screen is keyed on (T-5.8).
 *
 * <h2>Version-keyed, not evicted</h2>
 *
 * The pattern identity's permission cache already uses (T-2.2's {@code authz_version}): a writer
 * bumps a counter in the same transaction as the change it describes, and a reader puts the counter
 * in the cache key. A stale entry is not deleted — it stops being addressed, and expires on its
 * own. Nobody has to remember to evict, and a missed eviction cannot serve a wrong answer; the
 * worst case is a key nobody asks for again.
 *
 * <p>The alternative, deleting the key on every change, has the failure that matters here: the
 * delete happens outside the transaction, so a rollback after a successful delete is a cold cache
 * (harmless) and a crash between the commit and the delete is a <b>wrong screen served until the
 * TTL</b>. Bumping inside the transaction cannot produce that: either both happened or neither did.
 *
 * <h2>Two scopes, because changes have two shapes</h2>
 *
 * <ul>
 *   <li><b>A learner's own version</b> moves for things about one person: their progress, a
 *       completion, the groups they are in, their profile.</li>
 *   <li><b>The tenant's epoch</b> moves for things that change what everybody sees: a course
 *       restructured, a gate rewritten, an item republished, an assignment made to a group or to
 *       the whole company. Bumping every learner's row for those would be a write per person, on
 *       the path of an administrator making one edit.</li>
 * </ul>
 *
 * <p>Both are read in one query, so keying the cache costs a single indexed row read.
 */
@Component
public class HomeVersions {

    private final JdbcTemplate jdbc;

    public HomeVersions(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The pair a cache key is built from: the company's epoch and this learner's own version. */
    public record Version(long epoch, long learner) {

        /** The part of a cache key that changes when anything the screen shows changes. */
        public String key() {
            return epoch + "." + learner;
        }
    }

    public Version of(String tenantId, UUID learnerId) {
        return jdbc.query("""
            SELECT coalesce(max(version) FILTER (WHERE learner_id IS NULL), 0) AS epoch,
                   coalesce(max(version) FILTER (WHERE learner_id = ?), 0) AS learner
              FROM home_version
             WHERE tenant_id = ? AND (learner_id IS NULL OR learner_id = ?)
            """, rows -> rows.next()
                ? new Version(rows.getLong("epoch"), rows.getLong("learner"))
                : new Version(0, 0), learnerId, tenantId, learnerId);
    }

    /** Something about this person changed: their progress, their groups, their assignments. */
    public void bumpLearner(String tenantId, UUID learnerId) {
        bump(tenantId, learnerId);
    }

    /**
     * Something changed that everybody in this company can see.
     *
     * <p>One row rather than one per learner, which is the whole reason the epoch exists: an author
     * republishing a course must not write a row per person to do it.
     */
    public void bumpTenant(String tenantId) {
        bump(tenantId, null);
    }

    private void bump(String tenantId, UUID learnerId) {
        // ON CONFLICT against the partial indexes: the first bump for a scope inserts, and every
        // one after it increments. Written as one statement so two concurrent writers cannot both
        // decide the row is missing.
        String conflict = learnerId == null
            ? "(tenant_id) WHERE learner_id IS NULL"
            : "(tenant_id, learner_id) WHERE learner_id IS NOT NULL";
        jdbc.update("""
            INSERT INTO home_version (tenant_id, learner_id, version, updated_at)
            VALUES (?, ?, 1, now())
            ON CONFLICT %s DO UPDATE
               SET version = home_version.version + 1, updated_at = now()
            """.formatted(conflict), tenantId, learnerId);
    }
}
