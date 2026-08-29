package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Roles against a real Postgres (T-2.2): the side rule, the one-transaction edit with its
 * version bump, the audit trail carrying before and after, and the delete that refuses.
 */
@SpringBootTest
class RoleServiceTest extends PostgresTestHarness {

    /**
     * Assignments are T-2.3, so production cannot yet produce a role that is in use. The
     * refusal path is finished code, and this is what proves it: a counter that answers with a
     * real number, exactly as T-2.3 will.
     */
    @MockitoBean
    private RoleUsageCounter usage;

    @Autowired
    private RoleService roles;

    @Autowired
    private AuthzVersion authzVersion;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void aCallerToAttributeChangesTo() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM authz_version");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM app_user");
        // The caller has to hold what it hands out now (T-2.6), so it starts with the grant
        // provisioning will give a company's first administrator.
        AuthzFixtures.bootstrapAdmin(jdbc, "acme", "role-admin");
        // Audited work runs inside a request; CurrentUser reads the SecurityContext and
        // provisions on first sight, the same path /me takes.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            Jwt.withTokenValue("test").header("alg", "none").subject("sub-role-admin")
                .claim("email", "role-admin@acme.test").claim("name", "Role Admin")
                .claim("tenant_id", "acme").claim("side", "TENANT")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60)).build()));
    }

    @AfterEach
    void clearTheCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aTenantRoleCannotCarryAPlatformSidePermission() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("Course admin", "Runs the catalogue", PermissionSide.TENANT).getId();

            // The grant that would read as real in every screen and work nowhere: the evaluator
            // refuses cross-side at request time, so this has to be refused at write time.
            assertThatThrownBy(() -> roles.setPermissions(roleId, Set.of(Permission.TENANT_SUSPEND)))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("PLATFORM permission");

            assertThat(roles.currentCodes(roleId)).isEmpty();
            return null;
        });
    }

    @Test
    void platformRolesAreSeededNotBuiltAtRuntime() throws Exception {
        TenantContext.callWith("acme", () -> {
            assertThatThrownBy(() -> roles.create("Ops", "platform", PermissionSide.PLATFORM))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("T-2.7");
            return null;
        });
    }

    @Test
    void editingThePermissionSetIsOneTransactionAndBumpsTheVersion() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("Group admin", null, PermissionSide.TENANT).getId();
            long afterCreate = authzVersion.current();

            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ, Permission.USER_READ));

            assertThat(roles.currentCodes(roleId)).containsExactly("group:read", "user:read");
            assertThat(authzVersion.current())
                .as("a cached permission set must be able to notice this change")
                .isGreaterThan(afterCreate);
            return null;
        });
    }

    @Test
    void replacingTheSetRemovesWhatIsNoLongerInIt() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("Shrinking", null, PermissionSide.TENANT).getId();
            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ, Permission.GROUP_MANAGE));

            // Keeps one, drops one: the kept row must survive the delete-then-insert without
            // tripping the unique constraint on (role_id, permission_code).
            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ, Permission.USER_MANAGE));

            assertThat(roles.currentCodes(roleId)).containsExactly("group:read", "user:manage");
            return null;
        });
    }

    @Test
    void everyChangeIsAuditedWithTheBeforeAndAfterSets() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("Audited", null, PermissionSide.TENANT).getId();
            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ));
            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ, Permission.GROUP_MANAGE));

            List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT action, payload::text AS payload, actor_user_id, target_id
                  FROM audit_log
                 WHERE tenant_id = 'acme' AND target_type = 'role'
                 ORDER BY created_at, action
                """);

            assertThat(entries).hasSize(3);
            // Asserted as jsonb rather than as text: the payload is data, and a test matching
            // Postgres rendering would break on whitespace that means nothing.
            Long secondEdit = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action = 'role.permissions'
                   AND payload -> 'before' = '["group:read"]'::jsonb
                   AND payload -> 'after' @> '["group:manage"]'::jsonb
                """, Long.class);
            assertThat(secondEdit).as("the change as it actually was, both ends of it").isEqualTo(1);
            // Attributed to an app_user.id, never a username (ADR-0104).
            assertThat(entries.getFirst().get("actor_user_id")).isNotNull();
            assertThat(entries.getFirst().get("target_id")).isEqualTo(roleId);
            return null;
        });
    }

    @Test
    void renamingDoesNotChangeTheRoleIdentity() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("Old name", "before", PermissionSide.TENANT).getId();
            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ));
            long versionBefore = authzVersion.current();

            Role renamed = roles.rename(roleId, "New name", "after");

            // Same id, same grants: everything pointing at this role still points at it.
            assertThat(renamed.getId()).isEqualTo(roleId);
            assertThat(roles.get(roleId).getName()).isEqualTo("New name");
            assertThat(roles.currentCodes(roleId)).containsExactly("group:read");
            // And no version bump, because nobody effective permissions moved.
            assertThat(authzVersion.current()).isEqualTo(versionBefore);
            return null;
        });
    }

    @Test
    void aRoleInUseIsRefusedWithItsAssignmentCount() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("In use", null, PermissionSide.TENANT).getId();
            org.mockito.Mockito.when(usage.assignmentsOf(roleId)).thenReturn(7L);

            assertThatThrownBy(() -> roles.delete(roleId))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("assigned 7 time(s)");

            assertThat(roles.all()).extracting(Role::getId).contains(roleId);
            return null;
        });
    }

    @Test
    void anExplicitCascadeRecordsHowManyAssignmentsItTookWithIt() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("Cascading", null, PermissionSide.TENANT).getId();
            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ));
            org.mockito.Mockito.when(usage.assignmentsOf(roleId)).thenReturn(3L);

            roles.deleteCascading(roleId);

            Long recorded = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action = 'role.delete.cascade'
                   AND payload @> '{\"assignments\": 3}'::jsonb
                   AND payload -> 'permissions' @> '[\"group:read\"]'::jsonb
                """, Long.class);
            assertThat(recorded).isEqualTo(1);
            assertThat(roles.all()).extracting(Role::getId).doesNotContain(roleId);
            return null;
        });
    }

    @Test
    void anUnusedRoleDeletesAndTakesItsPermissionRowsWithIt() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleId = roles.create("Temporary", null, PermissionSide.TENANT).getId();
            roles.setPermissions(roleId, Set.of(Permission.GROUP_READ));

            roles.delete(roleId);

            // Not "no roles at all": every tenant carries its four seeded templates now
            // (T-2.7). What must be gone is this role.
            assertThat(roles.all()).extracting(Role::getId).doesNotContain(roleId);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM role_permission WHERE role_id = ?", Long.class, roleId)).isZero();
            return null;
        });
    }

    @Test
    void anotherTenantRolesAreNotVisibleOrEditable() throws Exception {
        UUID acmeRole = TenantContext.callWith("acme",
            () -> roles.create("Acme only", null, PermissionSide.TENANT).getId());

        TenantContext.callWith("globex", () -> {
            assertThat(roles.all()).extracting(Role::getId).doesNotContain(acmeRole);
            assertThatThrownBy(() -> roles.get(acmeRole))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            return null;
        });
    }

    @Test
    void theVersionStartsAtZeroAndOnlyMovesForward() throws Exception {
        TenantContext.callWith("globex", () -> {
            assertThat(authzVersion.current()).isZero();
            long first = authzVersion.bump();
            long second = authzVersion.bump();
            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(2);
            assertThat(authzVersion.current()).isEqualTo(2);
            return null;
        });
    }
}
