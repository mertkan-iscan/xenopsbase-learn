package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import com.xenopsoftware.learn.identity.group.GroupService;
import java.time.Instant;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The escalation paths, each one attempted and refused (T-2.6).
 *
 * <p>The setup is the shape the product actually has: a company administrator who holds the
 * tenant-side permissions, and a department manager who holds a few of them over one group. The
 * interesting attempts are the ones the manager makes.
 */
@SpringBootTest
class EscalationTest extends PostgresTestHarness {

    @Autowired
    private RoleService roles;

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private GroupService groups;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID adminUser;
    private UUID managerUser;
    private UUID engineering;
    private UUID adminRole;
    private UUID managerRole;

    @BeforeEach
    void aCompanyWithAnAdminAndAManager() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        clearEverything();
        adminUser = user("admin");
        managerUser = user("manager");

        // The first grant, made the way provisioning will make it (T-1.5): from outside the
        // tenant's own rules, because inside them nobody can grant anything yet.
        actAs("admin");
        TenantContext.callWith("acme", () -> {
            engineering = groups.create("Engineering", null).getId();
            adminRole = roles.create("Company admin", null, PermissionSide.TENANT).getId();
            managerRole = roles.create("Dept manager", null, PermissionSide.TENANT).getId();
            return null;
        });
        setPermissionsDirectly(adminRole, Permission.USER_READ, Permission.USER_MANAGE,
            Permission.GROUP_READ, Permission.GROUP_MANAGE, Permission.ROLE_READ,
            Permission.ROLE_MANAGE, Permission.ROLE_ASSIGN);
        setPermissionsDirectly(managerRole, Permission.USER_READ, Permission.USER_MANAGE);
        assignDirectly(adminRole, adminUser, "TENANT", null);
        assignDirectly(managerRole, managerUser, "GROUP", engineering);
    }

    @AfterEach
    void clearTheCaller() {
        clearEverything();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aManagerCannotPutAPermissionTheyDoNotHoldIntoARole() throws Exception {
        actAs("manager");
        TenantContext.callWith("acme", () -> {
            UUID mine = roles.create("Manager's own", null, PermissionSide.TENANT).getId();

            // group:manage is the admin's, not theirs. This is the plain escalation: build the
            // role, then assign it to yourself.
            assertThatThrownBy(() -> roles.setPermissions(mine, Set.of(Permission.GROUP_MANAGE)))
                .isInstanceOf(EscalationException.class)
                .hasMessageContaining("group:manage");

            assertThat(roles.currentCodes(mine)).isEmpty();
            return null;
        });
    }

    @Test
    void aManagerCanBuildARoleFromWhatTheyDoHold() throws Exception {
        actAs("manager");
        TenantContext.callWith("acme", () -> {
            UUID mine = roles.create("Manager's own", null, PermissionSide.TENANT).getId();
            // The rule refuses escalation, not delegation: they hold both of these.
            roles.setPermissions(mine, Set.of(Permission.USER_READ, Permission.USER_MANAGE));
            assertThat(roles.currentCodes(mine)).containsExactly("user:manage", "user:read");
            return null;
        });
    }

    @Test
    void cloningATemplateYouCouldNotBuildIsRefused() throws Exception {
        actAs("manager");
        TenantContext.callWith("acme", () -> {
            // The path T-2.6 does not list, and the shortest of all: a copy of the admin role
            // would carry its permissions and be editable and assignable by its owner.
            assertThatThrownBy(() -> roles.clone(adminRole, "Totally normal role"))
                .isInstanceOf(EscalationException.class)
                .hasMessageContaining("role:manage");

            assertThat(roles.all()).extracting(Role::getName).doesNotContain("Totally normal role");
            return null;
        });
    }

    @Test
    void aManagerCannotWidenTheirOwnScopeByAssigningTenantWide() throws Exception {
        actAs("manager");
        TenantContext.callWith("acme", () -> {
            // They hold user:manage, but only over Engineering. Granting the same role
            // company-wide would promote it -- T-2.6's explicit scope comparison.
            assertThatThrownBy(() -> assignments.assignToUser(managerRole, managerUser,
                ScopeGrant.tenantWide()))
                .isInstanceOf(EscalationException.class)
                .hasMessageContaining("user:manage");
            return null;
        });
    }

    @Test
    void aManagerMayAssignWithinTheScopeTheyHold() throws Exception {
        actAs("manager");
        TenantContext.callWith("acme", () -> {
            UUID colleague = user("colleague");
            // Same role, same scope they hold it at: delegation, not escalation.
            assertThat(assignments.assignToUser(managerRole, colleague,
                ScopeGrant.overGroup(engineering))).isNotNull();
            return null;
        });
    }

    @Test
    void anAdminMayAssignTenantWideBecauseTheyHoldItThere() throws Exception {
        actAs("admin");
        TenantContext.callWith("acme", () -> {
            assertThat(assignments.assignToUser(managerRole, managerUser, ScopeGrant.tenantWide()))
                .isNotNull();
            return null;
        });
    }

    @Test
    void aPlatformPermissionOnATenantRoleIsRefusedByItsOwnRule() throws Exception {
        // Checked SEPARATELY from the holder rule (T-2.6's third criterion): even the admin,
        // who holds the most this tenant has to give, cannot put a platform permission on a
        // tenant role -- and the message says side, not "you do not hold it".
        actAs("admin");
        TenantContext.callWith("acme", () -> {
            assertThatThrownBy(() -> roles.setPermissions(adminRole, Set.of(Permission.TENANT_SUSPEND)))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("PLATFORM permission");
            return null;
        });
    }

    @Test
    void everyRefusalIsAudited() throws Exception {
        actAs("manager");
        TenantContext.callWith("acme", () -> {
            UUID mine = roles.create("Manager's own", null, PermissionSide.TENANT).getId();
            try {
                roles.setPermissions(mine, Set.of(Permission.GROUP_MANAGE));
            } catch (EscalationException expected) {
                // The attempt is the interesting event.
            }
            try {
                assignments.assignToUser(managerRole, managerUser, ScopeGrant.tenantWide());
            } catch (EscalationException expected) {
                // As is this one.
            }
            return null;
        });

        // Written in their own transactions, which is why they survived the rollbacks that the
        // refusals caused.
        Long refusals = jdbc.queryForObject("""
            SELECT count(*) FROM audit_log
             WHERE action = 'grant.refused'
               AND payload -> 'missing' IS NOT NULL
            """, Long.class);
        assertThat(refusals).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log
             WHERE action = 'grant.refused' AND payload @> ('{"scope": "TENANT"}')::jsonb
            """, Long.class)).isEqualTo(1);
    }

    @Test
    void aTenantWhereNobodyHoldsAnythingCannotGrantAnything() throws Exception {
        // The state every new company starts in, and the reason the first grant cannot come
        // from inside it (T-1.5).
        jdbc.update("DELETE FROM role_assignment");
        actAs("admin");
        TenantContext.callWith("acme", () -> {
            assertThatThrownBy(() -> roles.setPermissions(adminRole, Set.of(Permission.USER_READ)))
                .isInstanceOf(EscalationException.class);
            assertThatThrownBy(() -> assignments.assignToUser(adminRole, adminUser,
                ScopeGrant.tenantWide()))
                .isInstanceOf(EscalationException.class);
            return null;
        });
    }

    private void clearEverything() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }

    /** The permissions a role holds, written the way the seeder does: outside the guarded path. */
    private void setPermissionsDirectly(UUID roleId, Permission... permissions) {
        for (Permission permission : permissions) {
            jdbc.update("""
                INSERT INTO role_permission (id, tenant_id, role_id, permission_code, created_at)
                VALUES (?, 'acme', ?, ?, now())
                """, UUID.randomUUID(), roleId, permission.code());
        }
    }

    /** The first grant, as provisioning will make it (T-1.5) rather than as a caller could. */
    private void assignDirectly(UUID roleId, UUID userId, String scopeType, UUID scopeId) {
        jdbc.update("""
            INSERT INTO role_assignment (id, tenant_id, role_id, user_id, scope_type, scope_id,
                                         granted_by, created_at)
            VALUES (?, 'acme', ?, ?, ?, ?, ?, now())
            """, UUID.randomUUID(), roleId, userId, scopeType, scopeId, adminUser);
    }

    private void actAs(String username) {
        SecurityContextHolder.clearContext();
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            Jwt.withTokenValue("test").header("alg", "none").subject("sub-" + username)
                .claim("email", username + "@acme.test").claim("name", username)
                .claim("tenant_id", "acme").claim("side", "TENANT")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()));
    }

    private UUID user(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, idp_sub, created_at, updated_at)
            VALUES (?, 'acme', ?, ?, 'ACTIVE', ?, now(), now())
            """, id, username + "@acme.test", username, "sub-" + username);
        return id;
    }
}
