package com.xenopsoftware.learn.packaging.launch;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reading a package on a request that has no tenant bound, because it has no token (ADR-0105).
 *
 * <p><b>Plain JDBC, and it is the same decision {@code UploadReaper} makes in streaming.</b> A
 * request arriving from the content origin presents no credential — that is the whole design, and
 * a session on that origin would be the vulnerability the ADR exists to prevent. So there is no
 * verified tenant claim, {@code TenantFilter} binds nothing, and the T-1.1 resolver rightly refuses
 * a Hibernate session. Reaching for {@code TenantContext.set} would be worse than the problem: an
 * ArchUnit rule forbids it precisely so that no path can bind a tenant from anything but a verified
 * token, and a path whose input is an anonymous URL is exactly the path that must not.
 *
 * <p>So the tenant comes from the URL and is used as <b>a filter, not as a claim</b>. It has to
 * match the row, which means a URL naming the wrong company finds nothing — the same 404 an
 * unknown id gets. What that buys is not authorization (there is none here; see {@link LaunchUrls})
 * but the guarantee that one company's prefix can never serve another's object.
 */
@Component
public class PackageLookup {

    private final JdbcTemplate jdbc;

    public PackageLookup(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * What the content origin needs to serve a package, and nothing more.
     *
     * <p>Deliberately not the entity: this is a credential-free path, and handing it a mapped row
     * with every column on it is how a field nobody meant to publish ends up in a response.
     */
    public record Launchable(UUID id, String tenantId, String entryPath, String profile,
                             String kind) {}

    /** The package at this tenant and id, if it exists and can be opened. */
    public Optional<Launchable> launchable(String tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, tenant_id, entry_path, profile, kind
              FROM content_package
             WHERE id = ?
               AND tenant_id = ?
               AND state = 'READY'
            """, id, tenantId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new Launchable(
            (UUID) row.get("id"),
            (String) row.get("tenant_id"),
            (String) row.get("entry_path"),
            (String) row.get("profile"),
            (String) row.get("kind")));
    }
}
