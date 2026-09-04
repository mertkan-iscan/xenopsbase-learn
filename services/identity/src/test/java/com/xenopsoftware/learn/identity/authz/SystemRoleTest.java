package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.time.Instant;
import java.util.List;
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
 * The templates and the clone rule (T-2.7): what gets seeded, what a customer may do to it, and
 * the two halves of "a change to a template reaches every customer and no clone".
 */
@SpringBootTest
class SystemRoleTest extends PostgresTestHarness {

    @Autowired
    private SystemRoleSeeder seeder;

    @Autowired
    private RoleService roles;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void aTenantWithItsTemplates() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM app_user");
        AuthzFixtures.bootstrapAdmin(jdbc, "acme", "template-admin");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            Jwt.withTokenValue("test").header("alg", "none").subject("sub-template-admin")
                .claim("email", "template-admin@acme.test").claim("name", "Template Admin")
                .claim("tenant_id", "acme").claim("side", "TENANT")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()));
        seeder.ensureSeededFor("acme");
    }

    @AfterEach
    void removeWhatThisClassCreated() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM app_user");
        SecurityContextHolder.clearContext();
    }

    @Test
    void everyTemplateIsDefinedInCodeWithASentenceEach() {
        // Seven since T-2.8 split writable impersonation into its own template: a separate
        // permission that arrives inside the same role is not a separate decision.
        assertThat(SystemRole.values()).hasSize(7);
        for (SystemRole template : SystemRole.values()) {
            assertThat(template.reach())
                .as("%s needs one sentence a customer could read", template.code())
                .isNotBlank()
                .endsWith(".");
            // A template that cannot be granted is worse than one that does not exist, because
            // it looks granted. The enum constructor enforces this; this is the standing proof.
            assertThat(template.permissions())
                .allMatch(permission -> permission.side() == template.side());
        }
    }

    @Test
    void theTenantSideTemplatesAreSeededAndThePlatformOnesAreNotYet() throws Exception {
        TenantContext.callWith("acme", () -> {
            List<String> seeded = roles.all().stream().filter(Role::isSystem)
                .map(Role::getName).sorted().toList();

            assertThat(seeded).containsExactly("Author", "Company administrator", "Group manager", "Learner");
            // The platform pair is defined in code and deliberately not materialised: a
            // platform-side row has no tenant to be read back under until T-1.5's root-tenant
            // opt-in, and writing rows nothing can fetch is what T-2.2 refused.
            assertThat(seeded).doesNotContain("Support", "Support (write)", "System administrator");
            return null;
        });
    }

    @Test
    void aSeededRoleCarriesExactlyItsTemplatePermissions() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID admin = roleNamed("Company administrator");
            assertThat(roles.currentCodes(admin)).containsExactlyInAnyOrderElementsOf(
                SystemRole.TENANT_ADMIN.permissions().stream().map(Permission::code).toList());

            // No longer empty: E3 arrived, and content:view is the first content-side ability a
            // learner holds (T-3.4). Derived from the enum rather than spelled out, so the next
            // E5 or E6 permission lands in the projection without editing this line -- what is
            // being asserted is that the projection MATCHES the template, not what is in it.
            assertThat(roles.currentCodes(roleNamed("Learner"))).containsExactlyInAnyOrderElementsOf(
                SystemRole.LEARNER.permissions().stream().map(Permission::code).toList());
            assertThat(SystemRole.LEARNER.permissions())
                .as("and the template is not empty, so the assertion above has something to check")
                .isNotEmpty();
            return null;
        });
    }

    @Test
    void seedingTwiceChangesNothing() throws Exception {
        List<java.util.Map<String, Object>> before = jdbc.queryForList(
            "SELECT id, name, updated_at FROM app_role WHERE system ORDER BY name");

        seeder.ensureSeededFor("acme");

        assertThat(jdbc.queryForList("SELECT id, name, updated_at FROM app_role WHERE system ORDER BY name"))
            .isEqualTo(before);
    }

    @Test
    void editingASystemRoleIsRefusedEveryWay() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID admin = roleNamed("Company administrator");

            assertThatThrownBy(() -> roles.rename(admin, "Mine now", null))
                .isInstanceOf(RoleException.class).hasMessageContaining("clone it");
            assertThatThrownBy(() -> roles.setPermissions(admin, Set.of(Permission.USER_READ)))
                .isInstanceOf(RoleException.class).hasMessageContaining("clone it");
            assertThatThrownBy(() -> roles.delete(admin))
                .isInstanceOf(RoleException.class).hasMessageContaining("clone it");

            // Untouched by all three attempts.
            assertThat(roles.get(admin).getName()).isEqualTo("Company administrator");
            assertThat(roles.currentCodes(admin)).contains("role:manage");
            return null;
        });
    }

    @Test
    void aCloneIsAnOrdinaryRoleWithNoLinkBack() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID template = roleNamed("Group manager");
            Role copy = roles.clone(template, "Department lead");

            assertThat(copy.isSystem()).isFalse();
            assertThat(copy.getId()).isNotEqualTo(template);
            assertThat(roles.currentCodes(copy.getId()))
                .isEqualTo(roles.currentCodes(template));

            // Editable, unlike what it came from — which is the entire point of cloning.
            roles.setPermissions(copy.getId(), Set.of(Permission.GROUP_READ));
            assertThat(roles.currentCodes(copy.getId())).containsExactly("group:read");
            assertThat(roles.currentCodes(template)).contains("user:manage");

            // No column, no reference, nothing to follow home: the copy records the name it was
            // made from in the audit log and nowhere in its own row.
            assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'app_role'",
                String.class)).doesNotContain("cloned_from", "template_id", "parent_role_id");
            return null;
        });
    }

    @Test
    void aChangeToATemplateReachesEveryCustomerAndNoClone() throws Exception {
        UUID clone = TenantContext.callWith("acme",
            () -> roles.clone(roleNamed("Group manager"), "Department lead").getId());

        // The template's projection is the code's, so simulating "somebody added a permission
        // to the template" is exactly a drifted row being put back: the seeder restores the
        // coded set on every run, whichever direction the drift went.
        UUID template = TenantContext.callWith("acme", () -> roleNamed("Group manager"));
        jdbc.update("DELETE FROM role_permission WHERE role_id = ? AND permission_code = 'user:manage'",
            template);
        jdbc.update("""
            INSERT INTO role_permission (id, tenant_id, role_id, permission_code, created_at)
            VALUES (?, 'acme', ?, 'role:read', now())
            """, UUID.randomUUID(), template);

        seeder.ensureSeededFor("acme");

        TenantContext.callWith("acme", () -> {
            // Back to exactly what the code says: the removed one restored, the added one gone.
            assertThat(roles.currentCodes(template)).containsExactlyInAnyOrderElementsOf(
                SystemRole.GROUP_MANAGER.permissions().stream().map(Permission::code).toList());
            // And the customer's copy is untouched by any of it.
            assertThat(roles.currentCodes(clone)).containsExactlyInAnyOrderElementsOf(
                SystemRole.GROUP_MANAGER.permissions().stream().map(Permission::code).toList());

            // Now the customer edits their copy, and a later re-projection still leaves it alone.
            roles.setPermissions(clone, Set.of(Permission.GROUP_READ));
            return null;
        });
        seeder.ensureSeededFor("acme");
        TenantContext.callWith("acme", () -> {
            assertThat(roles.currentCodes(clone)).containsExactly("group:read");
            assertThat(roles.currentCodes(template)).contains("user:manage");
            return null;
        });
    }

    @Test
    void aNewTenantGetsItsOwnTemplatesAndSeesNobodyElses() throws Exception {
        seeder.ensureSeededFor("globex");

        TenantContext.callWith("globex", () -> {
            assertThat(roles.all().stream().filter(Role::isSystem).toList())
                .extracting(Role::getName)
                .containsExactlyInAnyOrder("Author", "Company administrator", "Group manager", "Learner");
            return null;
        });
        TenantContext.callWith("acme", () -> {
            // Four templates each, and eight rows in the table: the tenant discriminator is
            // what keeps one customer's copy of a template out of another customer's list.
            assertThat(roles.all().stream().filter(Role::isSystem).toList()).hasSize(4);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE system", Long.class))
            .isEqualTo(8);
    }

    private UUID roleNamed(String name) {
        return roles.all().stream()
            .filter(role -> role.getName().equals(name))
            .findFirst().orElseThrow(() -> new AssertionError("No role named " + name))
            .getId();
    }
}
