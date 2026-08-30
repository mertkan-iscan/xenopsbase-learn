package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The number a cached permission set is validated against (ADR-0103, T-2.2).
 *
 * <p>Bumped <b>in the caller's transaction</b>, which is the whole point: a version written to a
 * cache alongside a database change can end up describing a change that rolled back, or miss one
 * that committed. Here it cannot — the row moves exactly when the grant does.
 *
 * <p>T-2.5 built the cache this number exists for: {@link ValkeyPermissions} puts the version in
 * the cache key, so a bump orphans every entry describing the old grants without anything having
 * to delete one. The row stays the only source of truth and is read once per resolution — the
 * mirror-to-Valkey this javadoc used to promise was not built, because a copy written after
 * commit disagrees with the row for a window, and the gateway it was for does not exist yet
 * (T-1.4).
 *
 * <p>Per tenant, where ADR-0103 says per (tenant, user). Until assignments exist (T-2.3) nothing
 * can say which users a role edit reaches, so the only correct answer is "everyone in the
 * tenant": more invalidation than needed, never less, and narrowed when T-2.3 can answer.
 */
@Component
public class AuthzVersion {

    private final JdbcTemplate jdbc;

    public AuthzVersion(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Moves the tenant's version forward and returns it. */
    public long bump() {
        return jdbc.queryForObject("""
            INSERT INTO authz_version (tenant_id, version, updated_at)
            VALUES (?, 1, now())
            ON CONFLICT (tenant_id) DO UPDATE
               SET version = authz_version.version + 1, updated_at = now()
            RETURNING version
            """, Long.class, TenantContext.require());
    }

    /** The tenant's current version; 0 before anything has ever been granted. */
    public long current() {
        Long version = jdbc.queryForObject(
            "SELECT coalesce(max(version), 0) FROM authz_version WHERE tenant_id = ?",
            Long.class, TenantContext.require());
        return version == null ? 0 : version;
    }
}
