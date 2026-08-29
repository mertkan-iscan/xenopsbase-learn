package com.xenopsoftware.learn.identity.authz;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The first grant, made from outside the tenant's own rules — which is the only way it can be
 * made (T-2.6), and the way provisioning will make it (T-1.5).
 *
 * <p>Tests written before the escalation guard existed exercised role editing and assignment as
 * a caller who held nothing. That worked because nothing checked; now it correctly does not, so
 * those tests bootstrap their caller here rather than being weakened to avoid the guard.
 */
final class AuthzFixtures {

    private AuthzFixtures() {}

    /** Ensures the caller exists and holds every tenant-side permission, company-wide. */
    static UUID bootstrapAdmin(JdbcTemplate jdbc, String tenant, String username) {
        UUID userId = ensureUser(jdbc, tenant, username);
        UUID roleId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_role (id, tenant_id, name, description, side, system, created_at, updated_at)
            VALUES (?, ?, ?, 'Bootstrap grant for tests', 'TENANT', false, now(), now())
            """, roleId, tenant, "Bootstrap " + username);
        for (Permission permission : Permission.values()) {
            if (permission.side() == PermissionSide.TENANT) {
                jdbc.update("""
                    INSERT INTO role_permission (id, tenant_id, role_id, permission_code, created_at)
                    VALUES (?, ?, ?, ?, now())
                    """, UUID.randomUUID(), tenant, roleId, permission.code());
            }
        }
        jdbc.update("""
            INSERT INTO role_assignment (id, tenant_id, role_id, user_id, scope_type, granted_by, created_at)
            VALUES (?, ?, ?, ?, 'TENANT', ?, now())
            """, UUID.randomUUID(), tenant, roleId, userId, userId);
        return userId;
    }

    static UUID ensureUser(JdbcTemplate jdbc, String tenant, String username) {
        String sub = "sub-" + username;
        UUID existing = jdbc.query("SELECT id FROM app_user WHERE idp_sub = ?",
            rows -> rows.next() ? rows.getObject(1, UUID.class) : null, sub);
        if (existing != null) {
            return existing;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, idp_sub, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, now(), now())
            """, id, tenant, username + "@" + tenant + ".test", username, sub);
        return id;
    }
}
