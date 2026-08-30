package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The cache is configured, enabled, and nothing is listening at the other end (T-2.5).
 *
 * <p>Degradation is an acceptance criterion rather than a feature, and the criterion has a
 * startup half that is easy to miss: a service that serves correctly without a cache but will
 * not <em>boot</em> without one is a service that fails during a rollout — which is when the
 * cache is most likely to be moving. This context booting at all is the first assertion in the
 * class, made by every test in it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissionCacheDegradationTest {

    private static final int NOTHING_IS_LISTENING = aClosedPort();

    @DynamicPropertySource
    static void aCacheThatIsNotThere(DynamicPropertyRegistry registry) {
        PostgresTestHarness.datasource(registry);
        registry.add("identity.authz.cache.enabled", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> NOTHING_IS_LISTENING);
    }

    @Autowired
    private RequestPermissions requestPermissions;

    @Autowired
    private RoleService roles;

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private AuthzVersion versions;

    @Autowired
    private CachedPermissions cache;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Jwt caller;
    private UUID role;
    private UUID engineer;

    @BeforeEach
    void aCompanyWithOneRoleAndOneEngineer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        removeEverythingThisClassCreates();

        AuthzFixtures.bootstrapAdmin(jdbc, "acme", "admin");
        engineer = AuthzFixtures.ensureUser(jdbc, "acme", "engineer");
        actAs("admin");
        TenantContext.callWith("acme", () -> {
            role = roles.create("Reader", null, PermissionSide.TENANT).getId();
            roles.setPermissions(role, Set.of(Permission.GROUP_READ));
            return null;
        });
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        removeEverythingThisClassCreates();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anUnreachableCacheIsAnswerableFromTheDatabase() throws Exception {
        TenantContext.callWith("acme", () -> {
            assignments.assignToUser(role, engineer, ScopeGrant.tenantWide());
            actAs("engineer");

            GrantedPermissions resolved = permissions();
            assertThat(resolved.holds(Permission.GROUP_READ))
                .as("the cache was a shortcut; the database still knows the answer")
                .isTrue();
            assertThat(resolved.holds(Permission.ROLE_MANAGE)).isFalse();
            return null;
        });
    }

    /**
     * The write happened, the version moved, and nothing about the cache had a say in either.
     * There is no eviction to fail here — the writer does not know the cache exists — and this
     * asserts the consequence: a cache outage cannot turn a grant into a half-made one.
     */
    @Test
    void aWriteStillCommitsAndTheNextRequestSeesIt() throws Exception {
        TenantContext.callWith("acme", () -> {
            long before = versions.current();

            UUID grant = assignments.assignToUser(role, engineer, ScopeGrant.tenantWide()).getId();
            assertThat(versions.current()).isGreaterThan(before);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM role_assignment WHERE id = ?", Integer.class, grant))
                .isEqualTo(1);

            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ)).isTrue();

            actAs("admin");
            assignments.revoke(grant);
            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ)).isFalse();
            return null;
        });
    }

    /**
     * Degrading properly is not just answering correctly: a dead cache that is asked on every
     * request costs every request a connection timeout, and a cache outage becomes a latency
     * outage. After a failure the cache is left alone for the cooldown.
     */
    @Test
    void oneTimeoutPerCooldownWindow() throws Exception {
        double unreachableBefore = lookups("unreachable");
        double bypassedBefore = lookups("bypassed");

        TenantContext.callWith("acme", () -> {
            for (int request = 0; request < 3; request++) {
                actAs("engineer");
                permissions();
            }
            return null;
        });

        assertThat(lookups("unreachable") - unreachableBefore)
            .as("at most one attempt per cooldown window, however many requests arrive")
            .isLessThanOrEqualTo(1);
        assertThat(lookups("bypassed") - bypassedBefore)
            .as("the rest went straight to the database without waiting for a timeout")
            .isGreaterThanOrEqualTo(2);
    }

    @Test
    void healthAndTheReadinessProbeStaySayingUp() throws Exception {
        TenantContext.callWith("acme", () -> {
            actAs("engineer");
            permissions();
            return null;
        });

        assertThat(((HealthIndicator) cache).health().getStatus())
            .as("serving from the database is a correct mode, not an unhealthy one")
            .isEqualTo(Status.UP);
        assertThat(((HealthIndicator) cache).health().getDetails())
            .as("and an operator can still see that it is not caching")
            .containsEntry("mode", "database (cache degraded)");

        assertThat(get("/management/health")).contains("\"status\":\"UP\"");
        assertThat(get("/management/health/readiness"))
            .as("a rollout must not stop because Valkey is the thing being rolled")
            .contains("\"status\":\"UP\"");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + path)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private double lookups(String result) {
        return meters.get("authz.permissions.cache").tag("result", result).counter().count();
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

    /** A port the operating system just handed out and nothing is bound to any more. */
    private static int aClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
