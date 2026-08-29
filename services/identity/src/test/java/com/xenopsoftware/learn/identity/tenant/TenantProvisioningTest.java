package com.xenopsoftware.learn.identity.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import com.xenopsoftware.learn.identity.authz.Permission;
import com.xenopsoftware.learn.identity.authz.Role;
import com.xenopsoftware.learn.identity.authz.RoleRepository;
import com.xenopsoftware.learn.identity.authz.ScopeResolver;
import com.xenopsoftware.learn.identity.authz.SystemRole;
import com.xenopsoftware.learn.identity.user.AppUser;
import com.xenopsoftware.learn.identity.user.AppUserRepository;
import com.xenopsoftware.learn.identity.user.UserProvisioningService;
import com.xenopsoftware.learn.identity.user.UserStatus;
import java.time.Instant;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Provisioning a company (T-1.5): everything it creates, that it creates all of it or none, and
 * the invitation that lets the first admin sign in without us ever holding a password.
 */
@SpringBootTest
class TenantProvisioningTest extends PostgresTestHarness {

    @Autowired
    private TenantProvisioningService provisioning;

    @Autowired
    private Tenants tenants;

    @Autowired
    private PlatformBootstrap platformBootstrap;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private UserProvisioningService userProvisioning;

    @Autowired
    private ScopeResolver scopes;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void aPlatformWithAnAdministrator() {
        jdbc = new JdbcTemplate(dataSource);
        clearEverything();
        // The installation's root of trust, exactly as an operator's configuration creates it.
        platformBootstrap.run(null);
        // The platform administrator signs in for the first time, which claims their invitation.
        actAs("platform-admin", "platform-admin@xenopslearn.test", "PLATFORM");
        TenantContext.callWithUnchecked(TenantFilter.PLATFORM_TENANT,
            () -> userProvisioning.provision(callerToken("platform-admin",
                "platform-admin@xenopslearn.test", "PLATFORM")));
    }

    @AfterEach
    void clearTheCaller() {
        clearEverything();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void oneCallCreatesTheCompanyItsRolesItsAdminAndTheGrantThatMattersMost() {
        provisionAsPlatform("northwind", "Northwind Traders", "chief@northwind.test", "The Chief");

        assertThat(tenants.find("northwind")).get()
            .extracting(Tenants.Tenant::name, Tenants.Tenant::status, Tenants.Tenant::archived)
            .containsExactly("Northwind Traders", "ACTIVE", false);

        assertThat(jdbc.queryForList(
            "SELECT name FROM app_role WHERE tenant_id = 'northwind' AND system", String.class))
            .containsExactlyInAnyOrder("Learner", "Author", "Group manager", "Company administrator");

        // Invited, not created with a password by us: no identity link yet.
        assertThat(jdbc.queryForMap("""
            SELECT status, idp_sub FROM app_user WHERE tenant_id = 'northwind'
            """)).containsEntry("status", "INVITED").containsEntry("idp_sub", null);

        // And the grant that the whole authorization chain has been waiting on: the first
        // admin can actually administer, because provisioning gave them the role from outside
        // the tenant -- which is the only place it could come from (T-2.6).
        actAs("chief", "chief@northwind.test", "TENANT");
        TenantContext.callWithUnchecked("northwind", () -> {
            userProvisioning.provision(callerToken("chief", "chief@northwind.test", "TENANT"));
            assertThat(scopes.reachFor(Permission.ROLE_MANAGE).wholeTenant()).isTrue();
            assertThat(scopes.reachFor(Permission.GROUP_MANAGE).wholeTenant()).isTrue();
            return null;
        });
    }

    @Test
    void theRolesComeFromTheCatalogNotFromAnotherTenant() {
        provisionAsPlatform("northwind", "Northwind", "chief@northwind.test", "Chief");
        // A customer whose templates somebody edited... which they cannot (T-2.7), but the
        // point stands: the second company is projected from code, so it cannot inherit
        // anything the first one's rows happen to say.
        jdbc.update("DELETE FROM role_permission WHERE role_id IN "
            + "(SELECT id FROM app_role WHERE tenant_id = 'northwind')");

        provisionAsPlatform("initech", "Initech", "boss@initech.test", "Boss");

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM role_permission rp
              JOIN app_role r ON r.id = rp.role_id
             WHERE r.tenant_id = 'initech' AND r.name = ?
            """, Long.class, SystemRole.TENANT_ADMIN.displayName()))
            .isEqualTo((long) SystemRole.TENANT_ADMIN.permissions().size());
    }

    @Test
    void aFailurePartWayLeavesNothingBehind() {
        // The injected failure: an admin email that validation accepts and the insert cannot,
        // because the column is bounded. Everything before it -- tenant row, four role
        // templates, their permissions -- is already written when it blows up.
        String tooLong = "x".repeat(400) + "@northwind.test";

        assertThatThrownBy(() -> provisionAsPlatform("northwind", "Northwind", tooLong, "Chief"))
            .isInstanceOf(RuntimeException.class);

        // Nothing at all: no tenant, no roles, no user. One transaction, and no compensation
        // path to get wrong -- because ADR-0102 put nothing outside this database.
        assertThat(tenants.find("northwind")).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM app_role WHERE tenant_id = 'northwind'", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM app_user WHERE tenant_id = 'northwind'", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM role_assignment WHERE tenant_id = 'northwind'", Long.class)).isZero();
    }

    @Test
    void theInvitationIsClaimedOnFirstSignInAndOnlyByAVerifiedEmail() {
        provisionAsPlatform("northwind", "Northwind", "chief@northwind.test", "Chief");
        UUID invitedId = jdbc.queryForObject(
            "SELECT id FROM app_user WHERE tenant_id = 'northwind'", UUID.class);

        // An unverified email must not claim an open invitation: that is the one path where a
        // claim nobody proved would hand over an account.
        assertThatThrownBy(() -> TenantContext.callWithUnchecked("northwind",
            () -> userProvisioning.provision(token("someone-else", "chief@northwind.test",
                "TENANT", false))))
            .isInstanceOf(RuntimeException.class);

        AppUser accepted = TenantContext.callWithUnchecked("northwind",
            () -> userProvisioning.provision(token("chief", "chief@northwind.test", "TENANT", true)));

        // Same row -- so the grant made at provisioning is theirs, not a new account's.
        assertThat(accepted.getId()).isEqualTo(invitedId);
        assertThat(accepted.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(accepted.getIdpSub()).isEqualTo("sub-chief");
    }

    @Test
    void theReservedPlatformIdAndMalformedIdsAreRefused() {
        assertThatThrownBy(() -> provisionAsPlatform(TenantFilter.PLATFORM_TENANT, "Sneaky", "a@b.test", "A")).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("reserved");
        assertThatThrownBy(() -> provisionAsPlatform("Northwind Inc", "Northwind",
            "a@b.test", "A")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> provisionAsPlatform("northwind", "Northwind",
            "not-an-email", "A")).isInstanceOf(ResponseStatusException.class);
        assertThat(tenants.customers()).isEmpty();
    }

    @Test
    void thesameCompanyTwiceIsRefusedRatherThanDuplicated() {
        provisionAsPlatform("northwind", "Northwind", "chief@northwind.test", "Chief");
        assertThatThrownBy(() -> provisionAsPlatform("northwind", "Northwind Again",
            "other@northwind.test", "Other"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void theBootstrapInvitesTheOperatorsAdministratorAndGivesThemTheRoleThatStartsEverything() {
        // Re-running changes nothing, which matters because it runs on every startup.
        platformBootstrap.run(null);

        assertThat(jdbc.queryForList(
            "SELECT name FROM app_role WHERE tenant_id = ? AND system", String.class,
            TenantFilter.PLATFORM_TENANT))
            .containsExactlyInAnyOrder("Support", "System administrator");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM role_assignment WHERE tenant_id = ? AND scope_type = 'PLATFORM'
            """, Long.class, TenantFilter.PLATFORM_TENANT)).isEqualTo(1);

        // And it is that grant, and nothing else, that lets a platform administrator create a
        // company at all.
        actAs("platform-admin", "platform-admin@xenopslearn.test", "PLATFORM");
        TenantContext.callWithUnchecked(TenantFilter.PLATFORM_TENANT, () -> {
            assertThat(scopes.reachFor(Permission.TENANT_PROVISION).wholeTenant()).isTrue();
            return null;
        });
    }

    @Test
    void theProvisioningIsAuditedInThePlatformTenant() {
        provisionAsPlatform("northwind", "Northwind", "chief@northwind.test", "Chief");

        Long audited = jdbc.queryForObject("""
            SELECT count(*) FROM audit_log
             WHERE tenant_id = ? AND action = 'tenant.provision'
               AND payload @> ('{"tenantId": "northwind"}')::jsonb
               AND actor_user_id IS NOT NULL
            """, Long.class, TenantFilter.PLATFORM_TENANT);
        assertThat(audited).isEqualTo(1);
    }

    /**
     * Provisioning the way a request reaches it: bound to the platform tenant, which is what
     * TenantFilter does for a platform-side token before the handler runs.
     */
    private TenantProvisioningService.ProvisionedTenant provisionAsPlatform(String tenantId,
            String name, String adminEmail, String adminName) {
        return TenantContext.callWithUnchecked(TenantFilter.PLATFORM_TENANT,
            () -> provisioning.provision(tenantId, name, adminEmail, adminName));
    }

    private void clearEverything() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM tenant WHERE tenant_id <> ?", TenantFilter.PLATFORM_TENANT);
    }

    private void actAs(String username, String email, String side) {
        SecurityContextHolder.clearContext();
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(token(username, email, side, true)));
    }

    private Jwt callerToken(String username, String email, String side) {
        return token(username, email, side, true);
    }

    private static Jwt token(String username, String email, String side, boolean emailVerified) {
        Jwt.Builder jwt = Jwt.withTokenValue("test").header("alg", "none")
            .subject("sub-" + username)
            .claim("email", email)
            .claim("email_verified", emailVerified)
            .claim("name", username)
            .claim("side", side)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if ("TENANT".equals(side)) {
            jwt.claim("tenant_id", "northwind");
        }
        return jwt.build();
    }
}
