package com.xenopsoftware.learn.identity.tenant;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant table (T-1.5, ADR-0102).
 *
 * <p>JdbcTemplate rather than an entity, and the reason is structural: every other table is
 * filtered BY the tenant, so an entity here would be filtered by the very column that identifies
 * it — a tenant able to see only itself, and platform staff, who must see all of them, seeing
 * none. This is the one table whose access is deliberately outside the discriminator, which is
 * also why it is small enough to read at a glance.
 */
@Component
public class Tenants {

    private final JdbcTemplate jdbc;

    public Tenants(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record Tenant(String tenantId, String name, String status, boolean archived) {}

    public boolean exists(String tenantId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM tenant WHERE tenant_id = ?", Long.class, tenantId);
        return count != null && count > 0;
    }

    public Optional<Tenant> find(String tenantId) {
        List<Tenant> found = jdbc.query("""
            SELECT tenant_id, name, status, archived_at FROM tenant WHERE tenant_id = ?
            """, (rows, index) -> new Tenant(rows.getString(1), rows.getString(2),
                rows.getString(3), rows.getTimestamp(4) != null), tenantId);
        return found.stream().findFirst();
    }

    /** Every customer. The platform's own reserved row is not one, and never appears here. */
    public List<Tenant> customers() {
        return jdbc.query("""
            SELECT tenant_id, name, status, archived_at FROM tenant
             WHERE tenant_id <> ? ORDER BY tenant_id
            """, (rows, index) -> new Tenant(rows.getString(1), rows.getString(2),
                rows.getString(3), rows.getTimestamp(4) != null), TenantFilter.PLATFORM_TENANT);
    }

    /** Creates the row. Participates in the caller's transaction, which is what makes
     *  provisioning all-or-nothing. */
    public void create(String tenantId, String name) {
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, name);
    }

    /** Ensures the reserved platform tenant exists, for a database that predates it. */
    public void ensurePlatformTenant() {
        jdbc.update("""
            INSERT INTO tenant (tenant_id, name) VALUES (?, 'XenOpsBase (platform)')
            ON CONFLICT (tenant_id) DO NOTHING
            """, TenantFilter.PLATFORM_TENANT);
    }

    public Map<String, Object> statusOf(String tenantId) {
        return jdbc.queryForMap("SELECT status, archived_at FROM tenant WHERE tenant_id = ?", tenantId);
    }
}
