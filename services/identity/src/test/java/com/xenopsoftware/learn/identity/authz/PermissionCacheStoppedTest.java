package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A cache that was working and then stopped (T-2.5), which is not the same failure as one that
 * was never there: the connection is established, the pool is warm, and it breaks underneath a
 * request. It gets its own Valkey because the test destroys it, and its own class because a
 * container cannot be stopped halfway through one and started again for the next.
 */
@SpringBootTest
class PermissionCacheStoppedTest {

    private static final org.testcontainers.containers.GenericContainer<?> DOOMED_VALKEY =
        new org.testcontainers.containers.GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        DOOMED_VALKEY.start();
    }

    @DynamicPropertySource
    static void aValkeyThisTestWillStop(DynamicPropertyRegistry registry) {
        PostgresTestHarness.datasource(registry);
        registry.add("identity.authz.cache.enabled", () -> true);
        registry.add("spring.data.redis.host", DOOMED_VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> DOOMED_VALKEY.getMappedPort(6379));
    }

    @Autowired
    private RequestPermissions requestPermissions;

    @Autowired
    private RoleService roles;

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private StringRedisTemplate valkey;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Jwt caller;

    @Test
    void aWarmCacheThatDiesMidFlightCostsALookupAndNothingElse() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        removeEverythingThisClassCreates();

        AuthzFixtures.bootstrapAdmin(jdbc, "acme", "admin");
        UUID engineer = AuthzFixtures.ensureUser(jdbc, "acme", "engineer");
        actAs("admin");
        TenantContext.callWith("acme", () -> {
            UUID role = roles.create("Reader", null, PermissionSide.TENANT).getId();
            roles.setPermissions(role, Set.of(Permission.GROUP_READ));
            assignments.assignToUser(role, engineer, ScopeGrant.tenantWide());

            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ)).isTrue();
            assertThat(valkey.keys("authz:*")).as("warm, over a live connection").isNotEmpty();
            return null;
        });

        DOOMED_VALKEY.stop();

        TenantContext.callWith("acme", () -> {
            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ))
                .as("the cache went away between two requests; the answer did not change")
                .isTrue();
            return null;
        });

        assertThat(meters.get("authz.permissions.cache").tag("result", "unreachable")
            .counter().count())
            .as("and it is recorded rather than absorbed silently")
            .isGreaterThanOrEqualTo(1);
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        removeEverythingThisClassCreates();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private GrantedPermissions permissions() {
        return requestPermissions.forCaller(caller);
    }

    private void actAs(String username) {
        SecurityContextHolder.clearContext();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
            new org.springframework.mock.web.MockHttpServletRequest()));
        caller = Jwt.withTokenValue("test").header("alg", "none").subject("sub-" + username)
            .claim("email", username + "@acme.test").claim("name", username)
            .claim("tenant_id", "acme").claim("side", "TENANT")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(caller));
    }

    private void removeEverythingThisClassCreates() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }
}
