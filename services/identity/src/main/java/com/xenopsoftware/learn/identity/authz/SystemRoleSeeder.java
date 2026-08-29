package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Projects {@link SystemRole} into a tenant's {@code app_role} table (T-2.7).
 *
 * <p>JdbcTemplate rather than a Hibernate session: this runs on a startup thread that binds no
 * tenant, which the T-1.1 resolver rightly refuses a session for, and it writes into whichever
 * tenant it is told about rather than the caller's.
 *
 * <p><b>It joins the caller's transaction, and that is load-bearing.</b> An earlier version took
 * its own connection and committed on its own, which meant a failed provisioning left four role
 * templates behind for a company that did not exist (T-1.5's "leaves nothing behind" test caught
 * exactly that). JdbcTemplate participates in whatever transaction is active, so the same code
 * is atomic inside provisioning and self-contained at startup.
 *
 * <p>Re-projection is the mechanism: system roles are made to match this code on every run, so
 * adding a permission to a template reaches every existing customer; clones are ordinary tenant
 * roles and are never touched.
 *
 * <p>Which tenants exist is answered by the {@code tenant} table since T-1.5; before it there
 * was only "every tenant that has a person in it", which is why this still tolerates a database
 * where a tenant row is missing.
 */
@Component
public class SystemRoleSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SystemRoleSeeder.class);

    private final JdbcTemplate jdbc;

    public SystemRoleSeeder(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> tenants = new ArrayList<>(jdbc.queryForList(
            "SELECT tenant_id FROM tenant ORDER BY tenant_id", String.class));
        for (String tenant : jdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM app_user ORDER BY tenant_id", String.class)) {
            if (!tenants.contains(tenant)) {
                tenants.add(tenant);
            }
        }
        for (String tenant : tenants) {
            ensureSeededFor(tenant);
        }
    }

    /** Makes this tenant's system roles match the code. Idempotent; safe to call per login. */
    public void ensureSeededFor(String tenant) {
        // Each side's templates go into the tenant that can hold them: the platform's own, or a
        // customer's. T-2.7 could only do half of this, because a platform-side row had no
        // tenant to be read back under until T-1.5 made one.
        boolean platformTenant = TenantFilter.PLATFORM_TENANT.equals(tenant);
        int changed = 0;
        for (SystemRole template : SystemRole.values()) {
            if ((template.side() == PermissionSide.PLATFORM) != platformTenant) {
                continue;
            }
            changed += project(tenant, template);
        }
        if (changed > 0) {
            LOG.info("System roles projected for tenant {}: {} change(s)", tenant, changed);
        }
    }

    /** One template into one tenant: the row, then its permission set, exactly as coded. */
    private int project(String tenant, SystemRole template) {
        UUID roleId = jdbc.query("""
            SELECT id FROM app_role WHERE tenant_id = ? AND system AND lower(name) = lower(?)
            """, rows -> rows.next() ? rows.getObject(1, UUID.class) : null,
            tenant, template.displayName());
        int changed = 0;
        if (roleId == null) {
            roleId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO app_role (id, tenant_id, name, description, side, system, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, true, now(), now())
                """, roleId, tenant, template.displayName(), template.reach(), template.side().name());
            changed++;
        } else {
            // The name and the sentence are code too, so a wording change reaches every
            // customer. Only writes when something differs, so updated_at keeps meaning "when
            // this role last changed".
            changed += jdbc.update("""
                UPDATE app_role SET name = ?, description = ?, updated_at = now()
                 WHERE id = ? AND (name, description) IS DISTINCT FROM (?, ?)
                """, template.displayName(), template.reach(), roleId,
                template.displayName(), template.reach());
        }
        return changed + projectPermissions(tenant, roleId, template);
    }

    /**
     * The permission set is made to equal the template's — added where missing, removed where
     * the code no longer lists it. A system role that drifted from its template would make the
     * next customer's copy differ from this one's for no reason anybody could reconstruct.
     */
    private int projectPermissions(String tenant, UUID roleId, SystemRole template) {
        List<String> wanted = new ArrayList<>();
        for (Permission permission : template.permissions()) {
            wanted.add(permission.code());
        }
        int changed;
        if (wanted.isEmpty()) {
            // A template with no permissions yet (learner, author) still owns its emptiness:
            // anything sitting on it is drift.
            changed = jdbc.update("DELETE FROM role_permission WHERE role_id = ?", roleId);
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
            Object[] args = new Object[wanted.size() + 1];
            args[0] = roleId;
            for (int i = 0; i < wanted.size(); i++) {
                args[i + 1] = wanted.get(i);
            }
            changed = jdbc.update(
                "DELETE FROM role_permission WHERE role_id = ? AND permission_code NOT IN ("
                + placeholders + ")", args);
        }
        for (String code : wanted) {
            changed += jdbc.update("""
                INSERT INTO role_permission (id, tenant_id, role_id, permission_code, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (role_id, permission_code) DO NOTHING
                """, UUID.randomUUID(), tenant, roleId, code);
        }
        return changed;
    }
}
