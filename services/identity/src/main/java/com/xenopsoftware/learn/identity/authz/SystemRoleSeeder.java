package com.xenopsoftware.learn.identity.authz;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Projects {@link SystemRole} into every tenant's {@code app_role} table (T-2.7).
 *
 * <p>Plain JDBC and cross-tenant, like the permission catalog seeder and for the same reason:
 * this runs on a startup thread that binds no tenant, which the T-1.1 resolver rightly refuses a
 * Hibernate session for.
 *
 * <p><b>Re-projection is the whole mechanism.</b> System roles are made to match this code on
 * every run, so adding a permission to a template in code reaches every existing customer;
 * clones are ordinary tenant roles and are never touched, so the same change reaches none of
 * them. That pair is the defined effect T-2.7 asks for, and both halves are tested.
 *
 * <p>Which tenants exist is not yet a question anything can answer — the tenant table is T-1.5 —
 * so the answer used here is "every tenant that has a person in it", read from {@code app_user}.
 * A tenant whose first user has not logged in yet has nobody to grant anything to; that user's
 * first login seeds it ({@code UserProvisioningService}).
 */
@Component
public class SystemRoleSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SystemRoleSeeder.class);

    private final DataSource dataSource;

    public SystemRoleSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (String tenant : tenantsWithPeople()) {
            ensureSeededFor(tenant);
        }
    }

    /** Makes this tenant's system roles match the code. Idempotent; safe to call per login. */
    public void ensureSeededFor(String tenant) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int changed = 0;
                for (SystemRole template : SystemRole.values()) {
                    if (template.side() == PermissionSide.PLATFORM) {
                        // Defined in code, deliberately not materialised: a platform-side row
                        // has no tenant to be read back under until the root-tenant opt-in
                        // lands (T-1.5), and writing rows nothing can fetch is the failure
                        // T-2.2 refused for platform roles created by hand.
                        continue;
                    }
                    changed += project(connection, tenant, template);
                }
                connection.commit();
                if (changed > 0) {
                    LOG.info("System roles projected for tenant {}: {} change(s)", tenant, changed);
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not seed system roles for tenant " + tenant, e);
        }
    }

    /** One template into one tenant: the row, then its permission set, exactly as coded. */
    private int project(Connection connection, String tenant, SystemRole template) throws SQLException {
        UUID roleId = existingRoleId(connection, tenant, template);
        int changed = 0;
        if (roleId == null) {
            roleId = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO app_role (id, tenant_id, name, description, side, system, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, true, now(), now())
                """)) {
                insert.setObject(1, roleId);
                insert.setString(2, tenant);
                insert.setString(3, template.displayName());
                insert.setString(4, template.reach());
                insert.setString(5, template.side().name());
                insert.executeUpdate();
            }
            changed++;
        } else {
            // The sentence and the display name are code too, so a wording change reaches every
            // customer. Only writes when something actually differs, so updated_at keeps
            // meaning "when this role last changed".
            try (PreparedStatement update = connection.prepareStatement("""
                UPDATE app_role SET name = ?, description = ?, updated_at = now()
                 WHERE id = ? AND (name, description) IS DISTINCT FROM (?, ?)
                """)) {
                update.setString(1, template.displayName());
                update.setString(2, template.reach());
                update.setObject(3, roleId);
                update.setString(4, template.displayName());
                update.setString(5, template.reach());
                changed += update.executeUpdate();
            }
        }
        changed += projectPermissions(connection, tenant, roleId, template);
        return changed;
    }

    /**
     * The permission set is made to equal the template's — added where missing, removed where
     * the code no longer lists it. A system role that drifted from its template would make the
     * next customer's copy differ from this one's for no reason anybody could reconstruct.
     */
    private int projectPermissions(Connection connection, String tenant, UUID roleId,
            SystemRole template) throws SQLException {
        List<String> wanted = new ArrayList<>();
        for (Permission permission : template.permissions()) {
            wanted.add(permission.code());
        }
        int changed = 0;
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM role_permission WHERE role_id = ? AND permission_code <> ALL (?)")) {
            delete.setObject(1, roleId);
            delete.setArray(2, connection.createArrayOf("varchar", wanted.toArray()));
            changed += delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO role_permission (id, tenant_id, role_id, permission_code, created_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (role_id, permission_code) DO NOTHING
            """)) {
            for (String code : wanted) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, tenant);
                insert.setObject(3, roleId);
                insert.setString(4, code);
                changed += insert.executeUpdate();
            }
        }
        return changed;
    }

    private UUID existingRoleId(Connection connection, String tenant, SystemRole template)
            throws SQLException {
        // Matched on the projected name within the tenant, which the unique index already
        // guarantees is at most one row.
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM app_role WHERE tenant_id = ? AND system AND lower(name) = lower(?)")) {
            query.setString(1, tenant);
            query.setString(2, template.displayName());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getObject(1, UUID.class) : null;
            }
        }
    }

    private List<String> tenantsWithPeople() throws SQLException {
        List<String> tenants = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                 "SELECT DISTINCT tenant_id FROM app_user ORDER BY tenant_id");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                tenants.add(rows.getString(1));
            }
        }
        return tenants;
    }
}
