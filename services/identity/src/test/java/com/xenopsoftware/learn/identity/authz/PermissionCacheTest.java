package com.xenopsoftware.learn.identity.authz;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The cache that must not serve a revoked permission (T-2.5), against a real Valkey.
 *
 * <p>This class does not extend {@link PostgresTestHarness} on purpose: that harness turns the
 * cache off for every test that inherits it, because those tests edit grants in raw SQL and raw
 * SQL does not bump {@code authz_version}. Here the cache is on, and every test drives it through
 * the same {@code RequestPermissions} a request would.
 */
@SpringBootTest
class PermissionCacheTest {

    // Same pin as docker-compose.yml, same disposability: nothing in here may be needed to
    // answer a request correctly, which is the property the degradation tests hold.
    private static final GenericContainer<?> VALKEY =
        new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @DynamicPropertySource
    static void aRealPostgresAndARealValkey(DynamicPropertyRegistry registry) {
        PostgresTestHarness.datasource(registry);
        registry.add("identity.authz.cache.enabled", () -> true);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
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
    private StringRedisTemplate valkey;

    @Autowired
    private MeterRegistry meters;

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
        wipeTheCache();

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
        wipeTheCache();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theKeyCarriesTheTenantTheCallerTheSchemaAndTheVersion() throws Exception {
        TenantContext.callWith("acme", () -> {
            assignments.assignToUser(role, engineer, ScopeGrant.tenantWide());
            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ)).isTrue();

            assertThat(entriesFor("sub-engineer"))
                .as("the four things a stale entry has to be unreachable by")
                .containsExactly("authz:v" + ValkeyPermissions.SCHEMA + ":acme:sub-engineer:"
                    + versions.current());
            return null;
        });
    }

    /**
     * The issue's own criterion, and the one failure this whole task exists to prevent: no sleep,
     * no TTL, no eviction — the revocation moved the version, so the warm entry is simply not
     * addressable any more. It is asserted to still be sitting there, unexpired, to make clear
     * that nothing deleted it and nothing had to.
     */
    @Test
    void aRevokedPermissionIsGoneOnTheVeryNextRequest() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID grant = assignments.assignToUser(role, engineer, ScopeGrant.tenantWide()).getId();

            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ)).isTrue();
            String warm = onlyEntryFor("sub-engineer");

            actAs("admin");
            assignments.revoke(grant);

            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ))
                .as("revoked, and the next request must not be served the old set")
                .isFalse();

            assertThat(valkey.opsForValue().get(warm))
                .as("nothing evicted the stale entry -- it is unreachable, not deleted")
                .isNotNull();
            assertThat(valkey.getExpire(warm))
                .as("and its TTL never came into it")
                .isGreaterThan(0);
            return null;
        });
    }

    /**
     * A rolling deploy runs two versions of this service against one Valkey, so the entry a
     * request reads may have been written by code that did not exist when this code was
     * compiled. An added field must be ignored, and a permission code this version no longer has
     * must cost that one grant rather than the whole entry.
     */
    @Test
    void anEntryFromAnotherVersionOfThisServiceIsStillUsable() throws Exception {
        actAs("engineer");
        TenantContext.callWith("acme", () -> {
            // Written by hand, and the database holds no grant for this caller: if the set comes
            // back holding anything, it came from here.
            valkey.opsForValue().set(cacheKeyFor("sub-engineer"), """
                {"schema":1,
                 "grants":[
                   {"permission":"group:read","scope":"TENANT","target":null,"grantedVia":"a field this version has never heard of"},
                   {"permission":"course:retired","scope":"TENANT","target":null}],
                 "writtenBy":"some later version"}
                """);

            GrantedPermissions resolved = permissions();
            assertThat(resolved.holds(Permission.GROUP_READ))
                .as("unknown properties are ignored, not fatal")
                .isTrue();
            assertThat(resolved.grants()).hasSize(1);
            return null;
        });
    }

    @Test
    void aWipedCacheCostsAResolutionAndNothingElse() throws Exception {
        TenantContext.callWith("acme", () -> {
            assignments.assignToUser(role, engineer, ScopeGrant.tenantWide());
            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ)).isTrue();

            wipeTheCache();

            actAs("engineer");
            assertThat(permissions().holds(Permission.GROUP_READ))
                .as("the database is the source; the cache only ever saved a trip to it")
                .isTrue();
            assertThat(entriesFor("sub-engineer")).as("and the entry is warm again").hasSize(1);
            return null;
        });
    }

    /**
     * A resolver that quietly stopped caching is invisible without these — the requests still
     * succeed, and the only symptom is a database working harder than anyone expects.
     */
    @Test
    void hitsMissesAndResolutionLatencyAreExported() throws Exception {
        TenantContext.callWith("acme", () -> {
            assignments.assignToUser(role, engineer, ScopeGrant.tenantWide());
            actAs("engineer");
            permissions();
            actAs("engineer");
            permissions();
            return null;
        });

        assertThat(meters.get("authz.permissions.cache").tag("result", "miss").counter().count())
            .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("authz.permissions.cache").tag("result", "hit").counter().count())
            .as("the second request for an unchanged set must not reach the database")
            .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("authz.permissions.resolution").tag("source", "database")
            .timer().count()).isGreaterThanOrEqualTo(1);
    }

    /**
     * The fourth acceptance criterion, held structurally rather than by care: a failed eviction
     * cannot fail a committed write when there is no eviction to fail. Invalidation is the
     * version in the key, so no writer needs to know this cache exists — and the day one does,
     * this fails.
     */
    @Test
    void noWriterKnowsTheCacheExists() {
        JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.xenopsoftware.learn.identity");

        ArchRule rule = noClasses()
            .that()
            .haveSimpleNameEndingWith("Service")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(CachedPermissions.class)
            .because("invalidation is by version, not by eviction: a write path that touches the "
                + "cache is a write path a cache failure can fail (T-2.5)");

        rule.check(production);
    }

    private GrantedPermissions permissions() {
        return requestPermissions.forCaller(caller);
    }

    private String cacheKeyFor(String subject) {
        return "authz:v" + ValkeyPermissions.SCHEMA + ":acme:" + subject + ":" + versions.current();
    }

    /** Entries for one caller: the admin doing the setting-up warms entries of its own. */
    private Set<String> entriesFor(String subject) {
        return valkey.keys("authz:v" + ValkeyPermissions.SCHEMA + ":acme:" + subject + ":*");
    }

    private String onlyEntryFor(String subject) {
        Set<String> keys = entriesFor(subject);
        assertThat(keys).hasSize(1);
        return keys.iterator().next();
    }

    private void wipeTheCache() {
        Set<String> keys = valkey.keys("authz:*");
        if (!keys.isEmpty()) {
            valkey.delete(keys);
        }
    }

    /** A caller bound the way a request binds one, with a fresh request scope each time. */
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
