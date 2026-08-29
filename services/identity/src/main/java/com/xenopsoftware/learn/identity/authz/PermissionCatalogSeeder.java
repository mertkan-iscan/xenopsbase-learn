package com.xenopsoftware.learn.identity.authz;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Projects the {@link Permission} enum into the {@code permission} table at startup (T-2.1).
 *
 * <p>Plain JDBC, deliberately, on two grounds. The catalog is not tenant data, and T-1.1's
 * resolver rightly refuses a Hibernate session on the tenant-less startup thread — the same
 * strictness that made repository bootstrap lazy. And the seeder is the only writer this table
 * has, so an entity would exist for one insert statement's benefit.
 *
 * <p>Idempotent by construction: the upsert touches a row only when something actually differs,
 * so a restart writes nothing and {@code updated_at} keeps meaning "when the catalog last
 * changed". A code the enum no longer declares is marked orphaned — never silently retained as
 * grantable, never deleted while roles may still reference it — and a code that returns is
 * revived.
 */
@Component
// First of the startup runners, and it has to be: role_permission has a foreign key to this
// table, so anything projecting a role -- the platform bootstrap, the system role seeder --
// depends on the catalog already being there.
@org.springframework.core.annotation.Order(0)
public class PermissionCatalogSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionCatalogSeeder.class);

    private final DataSource dataSource;

    public PermissionCatalogSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seed();
    }

    public void seed() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int changed = upsertDeclared(connection);
                int orphaned = orphanRetired(connection);
                connection.commit();
                if (changed > 0 || orphaned > 0) {
                    LOG.info("Permission catalog seeded: {} of {} entries changed, {} newly orphaned{}",
                        changed, Permission.values().length, orphaned,
                        orphaned > 0 ? " -- " + String.join(", ", orphanedCodes(connection)) : "");
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private int upsertDeclared(Connection connection) throws SQLException {
        String sql = """
            INSERT INTO permission (code, resource, action, side, min_scope, orphaned, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, false, now(), now())
            ON CONFLICT (code) DO UPDATE
               SET resource = excluded.resource,
                   action = excluded.action,
                   side = excluded.side,
                   min_scope = excluded.min_scope,
                   orphaned = false,
                   updated_at = now()
             WHERE (permission.resource, permission.action, permission.side, permission.min_scope, permission.orphaned)
                   IS DISTINCT FROM
                   (excluded.resource, excluded.action, excluded.side, excluded.min_scope, false)
            """;
        int changed = 0;
        try (PreparedStatement upsert = connection.prepareStatement(sql)) {
            for (Permission permission : Permission.values()) {
                upsert.setString(1, permission.code());
                upsert.setString(2, permission.resource());
                upsert.setString(3, permission.action());
                upsert.setString(4, permission.side().name());
                upsert.setString(5, permission.minScope().name());
                changed += upsert.executeUpdate();
            }
        }
        return changed;
    }

    private int orphanRetired(Connection connection) throws SQLException {
        String[] declared = new String[Permission.values().length];
        for (int i = 0; i < declared.length; i++) {
            declared[i] = Permission.values()[i].code();
        }
        Array codes = connection.createArrayOf("varchar", declared);
        try (PreparedStatement orphan = connection.prepareStatement(
            "UPDATE permission SET orphaned = true, updated_at = now() WHERE NOT orphaned AND code <> ALL (?)")) {
            orphan.setArray(1, codes);
            return orphan.executeUpdate();
        } finally {
            codes.free();
        }
    }

    private List<String> orphanedCodes(Connection connection) throws SQLException {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT code FROM permission WHERE orphaned ORDER BY code");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                codes.add(rows.getString(1));
            }
        }
        return codes;
    }
}
