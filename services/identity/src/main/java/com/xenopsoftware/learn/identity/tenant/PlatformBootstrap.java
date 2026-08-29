package com.xenopsoftware.learn.identity.tenant;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import com.xenopsoftware.learn.identity.authz.AssignmentScopeType;
import com.xenopsoftware.learn.identity.authz.SystemRole;
import com.xenopsoftware.learn.identity.authz.SystemRoleSeeder;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The installation's root of trust (T-1.5).
 *
 * <p>Every grant in this platform comes from a grant somebody already held (T-2.6), which is a
 * chain that has to start somewhere outside itself. It starts here: the operator who deploys
 * this service names the platform administrators in configuration, and those people are invited
 * and given the platform's own sys-admin role. From there, platform staff provision companies,
 * a company's first admin is granted tenant-admin by that provisioning, and tenant admins grant
 * within their own company.
 *
 * <p>They are <b>invited</b>, exactly as a customer's first admin is: a row with no identity
 * link, claimed on first sign-in. This service never holds anybody's credential, not even its
 * own operators'.
 *
 * <p>Configuration and not an API, deliberately. An endpoint that could grant platform
 * administration would need someone already holding it to call — the chain again — and an
 * endpoint that could do it without would be the escalation path T-2.6 exists to close.
 */
@Component
@Order(100)
@ConfigurationProperties(prefix = "identity.platform")
public class PlatformBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformBootstrap.class);

    private final JdbcTemplate jdbc;
    private final Tenants tenants;
    private final SystemRoleSeeder systemRoles;
    private List<String> administrators = List.of();

    public PlatformBootstrap(DataSource dataSource, Tenants tenants, SystemRoleSeeder systemRoles) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tenants = tenants;
        this.systemRoles = systemRoles;
    }

    /** Email addresses of the people who administer this installation. */
    public void setAdministrators(List<String> administrators) {
        this.administrators = administrators == null ? List.of() : administrators;
    }

    public List<String> getAdministrators() {
        return administrators;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenants.ensurePlatformTenant();
        systemRoles.ensureSeededFor(TenantFilter.PLATFORM_TENANT);
        if (administrators.isEmpty()) {
            // Loud, because an installation with no platform administrator cannot create a
            // single customer: nobody holds tenant:provision and nobody can be given it.
            LOG.warn("No identity.platform.administrators configured. Nobody can provision a "
                + "company on this installation until at least one is named.");
            return;
        }
        for (String email : administrators) {
            ensureAdministrator(email.trim().toLowerCase());
        }
    }

    private void ensureAdministrator(String email) {
        UUID userId = jdbc.query("""
            SELECT id FROM app_user WHERE tenant_id = ? AND lower(email) = ?
            """, rows -> rows.next() ? rows.getObject(1, UUID.class) : null,
            TenantFilter.PLATFORM_TENANT, email);
        if (userId == null) {
            userId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO app_user (id, tenant_id, email, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'INVITED', now(), now())
                """, userId, TenantFilter.PLATFORM_TENANT, email, email);
            LOG.info("Invited platform administrator {}", email);
        }
        UUID roleId = jdbc.query("""
            SELECT id FROM app_role WHERE tenant_id = ? AND system AND name = ?
            """, rows -> rows.next() ? rows.getObject(1, UUID.class) : null,
            TenantFilter.PLATFORM_TENANT, SystemRole.SYS_ADMIN.displayName());
        if (roleId == null) {
            throw new IllegalStateException("The platform sys-admin template was not projected");
        }
        Long held = jdbc.queryForObject("""
            SELECT count(*) FROM role_assignment
             WHERE tenant_id = ? AND role_id = ? AND user_id = ? AND scope_type = 'PLATFORM'
            """, Long.class, TenantFilter.PLATFORM_TENANT, roleId, userId);
        if (held != null && held == 0) {
            jdbc.update("""
                INSERT INTO role_assignment (id, tenant_id, role_id, user_id, scope_type, granted_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), TenantFilter.PLATFORM_TENANT, roleId, userId,
                AssignmentScopeType.PLATFORM.name(), userId);
            LOG.info("Granted {} the platform {} role", email, SystemRole.SYS_ADMIN.displayName());
        }
    }
}
