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

/**
 * The scope matrix (T-2.3): one role, granted at each scope in turn, asserting the reachable set
 * differs correctly every time — which is the difference between a group admin and a company
 * admin, and the single most likely way this product could leak between departments.
 */
@SpringBootTest
class AssignmentScopeTest extends PostgresTestHarness {

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private RoleService roles;

    @Autowired
    private GroupService groups;

    @Autowired
    private ScopeResolver scopes;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID role;
    private UUID company;
    private UUID engineering;
    private UUID platform;
    private UUID sales;
    private UUID engineer;
    private UUID seller;

    @BeforeEach
    void aCompanyWithTwoDepartments() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
        AuthzFixtures.bootstrapAdmin(jdbc, "acme", "admin");
        actAs("admin");

        TenantContext.callWith("acme", () -> {
            company = groups.create("Company", null).getId();
            engineering = groups.create("Engineering", company).getId();
            platform = groups.create("Platform", engineering).getId();
            sales = groups.create("Sales", company).getId();
            role = roles.create("Reader", null, PermissionSide.TENANT).getId();
            roles.setPermissions(role, Set.of(Permission.GROUP_READ, Permission.USER_READ));
            engineer = user("engineer@acme.test");
            seller = user("seller@acme.test");
            groups.addMember(platform, engineer);
            groups.addMember(sales, seller);
            return null;
        });
    }

    /**
     * This class creates app_user rows and things that point at them. Anything left behind
     * blocks the next class that clears app_user — which surfaces there as a mystery failure in
     * a test that has nothing to do with assignments. The class that made the rows removes
     * them, in foreign-key order.
     */
    @AfterEach
    void removeWhatThisClassCreated() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }

    @AfterEach
    void clearTheCaller() {
        SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theSameRoleAtFourScopesReachesFourDifferentSets() throws Exception {
        TenantContext.callWith("acme", () -> {
            // 1. TENANT — the whole company, including groups made after this was granted.
            UUID tenantWide = assignments.assignToUser(role, engineer, ScopeGrant.tenantWide()).getId();
            actAs("engineer");
            assertThat(scopes.reachFor(Permission.GROUP_READ).wholeTenant()).isTrue();
            assertThat(scopes.reachableUsers(Permission.GROUP_READ)).contains(engineer, seller);
            actAs("admin");
            assignments.revoke(tenantWide);

            // 2. GROUP, high — Engineering and everything under it, and nothing sideways.
            UUID overEngineering =
                assignments.assignToUser(role, engineer, ScopeGrant.overGroup(engineering)).getId();
            actAs("engineer");
            Reach engineeringReach = scopes.reachFor(Permission.GROUP_READ);
            assertThat(engineeringReach.wholeTenant()).isFalse();
            assertThat(engineeringReach.groupIds()).containsExactlyInAnyOrder(engineering, platform);
            assertThat(engineeringReach.includesGroup(sales)).isFalse();
            assertThat(scopes.reachableUsers(Permission.GROUP_READ))
                .containsExactly(engineer)
                .doesNotContain(seller);
            actAs("admin");
            assignments.revoke(overEngineering);

            // 3. GROUP, low — one department, not its parent.
            UUID overPlatform =
                assignments.assignToUser(role, engineer, ScopeGrant.overGroup(platform)).getId();
            actAs("engineer");
            Reach platformReach = scopes.reachFor(Permission.GROUP_READ);
            assertThat(platformReach.groupIds()).containsExactly(platform);
            assertThat(platformReach.includesGroup(engineering)).isFalse();
            actAs("admin");
            assignments.revoke(overPlatform);

            // 4. COURSE — and the correct answer today is that it cannot be granted at all.
            // Every permission in the current catalog is floored at GROUP or wider, so a
            // course-scoped assignment of this role is refused by the floor rule rather than
            // creating a grant that reaches nothing. COURSE becomes grantable when E6 adds the
            // content-level permissions this issue itself used as its example (attempt:read);
            // the model carries the scope now so that arrival is a catalog entry, not a
            // migration of live assignments.
            assertThatThrownBy(() -> assignments.assignToUser(role, engineer,
                ScopeGrant.overCourse(UUID.randomUUID())))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("needs at least GROUP scope");
            return null;
        });
    }

    @Test
    void aCourseScopedGrantReachesContentAndNoGroups() {
        // The resolver half of the COURSE row, exercised directly because no catalog permission
        // can be granted at that scope yet (see the matrix above). A grant over content must
        // answer "no groups" rather than offering the course id as if it were one — a category
        // error that would read as a working answer.
        UUID course = UUID.randomUUID();
        Reach reach = new Reach(false, Set.of(), Set.of(course));

        assertThat(reach.includesCourse(course)).isTrue();
        assertThat(reach.groupIds()).isEmpty();
        assertThat(reach.includesGroup(course)).isFalse();
        assertThat(reach.isEmpty()).isFalse();
    }

    @Test
    void overlappingAssignmentsUnionWithTheWidestWinning() throws Exception {
        TenantContext.callWith("acme", () -> {
            assignments.assignToUser(role, engineer, ScopeGrant.overGroup(platform));
            assignments.assignToUser(role, engineer, ScopeGrant.tenantWide());

            actAs("engineer");
            // Being a group admin of Platform and separately a company admin makes someone a
            // company admin. Nothing here can express a deny that narrows it back.
            assertThat(scopes.reachFor(Permission.GROUP_READ).wholeTenant()).isTrue();
            assertThat(scopes.canReachGroup(Permission.GROUP_READ, sales)).isTrue();
            return null;
        });
    }

    @Test
    void aGroupAssignmentReachesTheMembersOfItsDescendants() throws Exception {
        TenantContext.callWith("acme", () -> {
            // The decision this issue asked to be recorded: assigned to Engineering, and the
            // engineer is filed under Platform, one level down. Containment is what the tree
            // means, so the assignment follows it.
            assignments.assignToGroup(role, engineering, ScopeGrant.overGroup(engineering));

            actAs("engineer");
            assertThat(scopes.reachFor(Permission.GROUP_READ).groupIds())
                .containsExactlyInAnyOrder(engineering, platform);

            // And it does not leak sideways: the seller is under Sales, which nothing granted.
            actAs("seller");
            assertThat(scopes.reachFor(Permission.GROUP_READ).isEmpty()).isTrue();
            return null;
        });
    }

    @Test
    void aScopeTooNarrowForThePermissionIsRefusedAtGrantTime() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID roleAdmin = roles.create("Role admin", null, PermissionSide.TENANT).getId();
            // role:manage is floored at TENANT (T-2.1): editing a role affects everyone holding
            // it, so no narrower scope can own the edit.
            roles.setPermissions(roleAdmin, Set.of(Permission.ROLE_MANAGE));

            assertThatThrownBy(() -> assignments.assignToUser(roleAdmin, engineer,
                ScopeGrant.overGroup(engineering)))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("needs at least TENANT scope");

            // The same role at tenant scope is fine.
            assertThat(assignments.assignToUser(roleAdmin, engineer, ScopeGrant.tenantWide()))
                .isNotNull();
            return null;
        });
    }

    @Test
    void platformScopeIsSeededNotGranted() throws Exception {
        TenantContext.callWith("acme", () -> {
            assertThatThrownBy(() -> assignments.assignToUser(role, engineer,
                new ScopeGrant(AssignmentScopeType.PLATFORM, null)))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("T-2.7");
            return null;
        });
    }

    @Test
    void grantsAndRevokesAreAuditedWithWhoGrantedThem() throws Exception {
        TenantContext.callWith("acme", () -> {
            RoleAssignment granted =
                assignments.assignToUser(role, engineer, ScopeGrant.overGroup(engineering));
            assignments.revoke(granted.getId());

            Long audited = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE target_type = 'assignment'
                   AND action IN ('assignment.grant', 'assignment.revoke')
                   AND payload -> 'grantedBy' IS NOT NULL
                   AND payload @> ('{"scopeType": "GROUP"}')::jsonb
                """, Long.class);
            assertThat(audited).isEqualTo(2);
            // Who granted it is a standing fact on the row too, not only in the log.
            assertThat(granted.getGrantedBy()).isNotNull();
            return null;
        });
    }

    @Test
    void aRoleWithAssignmentsCannotBeDeletedSilently() throws Exception {
        TenantContext.callWith("acme", () -> {
            assignments.assignToUser(role, engineer, ScopeGrant.tenantWide());
            assignments.assignToGroup(role, sales, ScopeGrant.overGroup(sales));

            // T-2.2 wrote this refusal against a port; this is the first time it can be true.
            assertThatThrownBy(() -> roles.delete(role))
                .isInstanceOf(RoleException.class)
                .hasMessageContaining("assigned 2 time(s)");
            return null;
        });
    }

    @Test
    void anotherTenantGrantsAreInvisible() throws Exception {
        TenantContext.callWith("acme",
            () -> assignments.assignToUser(role, engineer, ScopeGrant.tenantWide()));

        TenantContext.callWith("globex", () -> {
            assertThat(assignments.all()).isEmpty();
            return null;
        });
    }

    @Test
    void someoneWithNoAssignmentsHoldsNothing() throws Exception {
        TenantContext.callWith("acme", () -> {
            actAs("seller");
            // The empty-IN-list trap: a person in no groups must resolve to no grants, not to
            // every group-assigned grant in the tenant.
            assertThat(scopes.reachFor(Permission.GROUP_READ).isEmpty()).isTrue();
            assertThat(scopes.reachableUsers(Permission.GROUP_READ)).isEmpty();
            return null;
        });
    }

    /**
     * Binds a caller the way a real request does — including a FRESH request scope each time,
     * because the permission set is resolved once per request on purpose (T-2.4). Reusing one
     * scope across two callers would test a cache, not a resolution.
     */
    private void actAs(String username) {
        SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(
                new org.springframework.mock.web.MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            Jwt.withTokenValue("test").header("alg", "none").subject("sub-" + username)
                .claim("email", username + "@acme.test").claim("name", username)
                .claim("tenant_id", "acme").claim("side", "TENANT")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()));
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        String username = email.substring(0, email.indexOf('@'));
        jdbc.update("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, idp_sub, created_at, updated_at)
            VALUES (?, 'acme', ?, ?, 'ACTIVE', ?, now(), now())
            """, id, email, email, "sub-" + username);
        return id;
    }
}
