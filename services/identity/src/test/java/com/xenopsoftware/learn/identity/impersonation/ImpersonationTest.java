package com.xenopsoftware.learn.identity.impersonation;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import com.xenopsoftware.learn.identity.StubTokens;
import com.xenopsoftware.learn.identity.authz.AuthzFixtures;
import com.xenopsoftware.learn.identity.authz.Permission;
import com.xenopsoftware.learn.identity.authz.PermissionSide;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Support impersonation, through the real filter chain (T-2.8).
 *
 * <p>The six criteria this holds honest, in order: its own permission that no default role
 * carries, a time-boxed session with a reason, read-only unless a second permission was held,
 * both identities on every audit entry, the customer's own view of what happened, and no way in
 * to a suspended tenant.
 *
 * <p>Real HTTP rather than a service-level test, because half of this lives in a filter and the
 * order it runs in relative to the tenant binding is the part most likely to be broken by
 * somebody later.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class ImpersonationTest extends PostgresTestHarness {

    private static final String CUSTOMER = "acme";
    private static final String REASON = "ticket 4711, learner cannot open module 3";

    /** Platform-side tokens carry no tenant claim; the empty middle segment is what says so. */
    private static final String SUPPORT = "support~~PLATFORM";
    private static final String SUPPORT_WRITER = "support-writer~~PLATFORM";
    private static final String OTHER_ENGINEER = "other-engineer~~PLATFORM";
    private static final String CUSTOMER_ADMIN = "acme-admin~acme~TENANT";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;
    private UUID learner;

    @BeforeEach
    void aCustomerACompanyAdminAndThreeEngineers() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEverything();

        tenant(CUSTOMER, "ACTIVE");
        tenant(TenantFilter.PLATFORM_TENANT, "ACTIVE");
        AuthzFixtures.bootstrapAdmin(jdbc, CUSTOMER, "acme-admin");
        learner = AuthzFixtures.ensureUser(jdbc, CUSTOMER, "acme-learner");

        platformGrant("support", Permission.SUPPORT_IMPERSONATE);
        platformGrant("other-engineer", Permission.SUPPORT_IMPERSONATE);
        platformGrant("support-writer", Permission.SUPPORT_IMPERSONATE,
            Permission.SUPPORT_IMPERSONATE_WRITE);
    }

    @Test
    void noSeededDefaultRoleHandsOutTheAbilityToImpersonate() {
        // The first criterion, held where it is decided rather than where it is used. sys-admin
        // is granted to every configured administrator at startup (T-1.5), so a template
        // carrying support:impersonate would mean every operator silently held a key into every
        // customer account.
        for (com.xenopsoftware.learn.identity.authz.SystemRole template
                : com.xenopsoftware.learn.identity.authz.SystemRole.values()) {
            boolean dedicated = template.code().startsWith("support");
            assertThat(template.permissions().contains(Permission.SUPPORT_IMPERSONATE))
                .as("%s carries support:impersonate", template.code())
                .isEqualTo(dedicated);
        }
        assertThat(com.xenopsoftware.learn.identity.authz.SystemRole.SUPPORT.permissions())
            .as("read-only support must not carry the write half; that is a separate decision")
            .doesNotContain(Permission.SUPPORT_IMPERSONATE_WRITE);
    }

    @Test
    void aSessionIsTimeBoxedReadOnlyAndCarriesTheReasonItWasStartedFor() throws Exception {
        HttpResponse<String> started = start(SUPPORT, learner, REASON, false);

        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(started.body()).contains("\"writable\":false").contains("X-Impersonate-Session");

        Map<String, Object> row = jdbc.queryForList("SELECT * FROM impersonation_session").getFirst();
        assertThat(row.get("reason")).isEqualTo(REASON);
        assertThat(row.get("tenant_id")).isEqualTo(CUSTOMER);
        assertThat(row.get("writable")).isEqualTo(false);
        Instant expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        assertThat(expiresAt).isBefore(Instant.now().plusSeconds(31 * 60));
        assertThat(expiresAt).isAfter(Instant.now());
    }

    @Test
    void aReasonNobodyCouldReadLaterIsNotAReason() throws Exception {
        assertThat(start(SUPPORT, learner, "x", false).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impersonation_session", Long.class))
            .isZero();
    }

    @Test
    void theEngineerSeesWhatThatPersonSees() throws Exception {
        UUID session = sessionId(start(SUPPORT, learner, REASON, false));

        HttpResponse<String> me = get("/api/v1/me", SUPPORT, session);

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body())
            .as("/me under a session answers with the person being impersonated, not the engineer")
            .contains(learner.toString())
            .contains("acme-learner@acme.test")
            .contains("\"tenant\":\"acme\"");
    }

    @Test
    void noAccountForOurStaffIsCreatedInsideTheCustomersCompany() throws Exception {
        UUID session = sessionId(start(SUPPORT, learner, REASON, false));

        assertThat(get("/api/v1/me", SUPPORT, session).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM app_user WHERE tenant_id = ? AND idp_sub = 'sub-support'
            """, Long.class, CUSTOMER))
            .as("provisioning the caller under a session would give the customer a member they "
                + "never invited, counted in their seats and explained to nobody")
            .isZero();
    }

    @Test
    void aReadOnlySessionCannotWriteAndTheAttemptIsOnTheRecord() throws Exception {
        UUID session = sessionId(start(SUPPORT, learner, REASON, false));

        HttpResponse<String> refused = post("/api/v1/roles", SUPPORT, session,
            "{\"name\":\"Sneaky\",\"description\":\"made under a read-only session\"}");

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("IMPERSONATION_READ_ONLY");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log WHERE action = 'impersonation.write.refused'
             AND tenant_id = ? AND impersonation_session_id = ?
            """, Long.class, CUSTOMER, session)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM app_role WHERE name = 'Sneaky'", Long.class)).isZero();
    }

    @Test
    void writingNeedsASecondPermissionAndThenBothIdentitiesAreOnTheEntry() throws Exception {
        // The read-only engineer asking for a writable session is refused at the door. 404 and
        // not 403 because the disclosure rule (T-2.4) answers 403 only to a caller who holds the
        // denied resource's read permission, and there is no support:read.
        assertThat(start(SUPPORT, learner, REASON, true).statusCode()).isEqualTo(404);

        UUID session = sessionId(start(SUPPORT_WRITER, learner, REASON, true));
        HttpResponse<String> created = post("/api/v1/roles", SUPPORT_WRITER, session,
            "{\"name\":\"Support made this\",\"description\":\"reproducing ticket 4711\"}");

        assertThat(created.statusCode()).isEqualTo(200);
        Map<String, Object> entry = jdbc.queryForList("""
            SELECT actor_user_id, impersonated_user_id, impersonation_session_id
              FROM audit_log WHERE action = 'role.create'
            """).getFirst();
        assertThat(entry.get("actor_user_id"))
            .as("the actor is the engineer -- nothing they did is attributed to the customer")
            .isEqualTo(platformUser("support-writer"));
        assertThat(entry.get("impersonated_user_id")).isEqualTo(learner);
        assertThat(entry.get("impersonation_session_id")).isEqualTo(session);
    }

    @Test
    void theEngineersOwnPlatformPowersDoNotComeAlong() throws Exception {
        UUID session = sessionId(start(SUPPORT_WRITER, learner, REASON, true));

        // Without the session this caller can list companies; wearing a customer's face they
        // cannot. The swap of sides is total, or a support tool is a privilege escalation.
        assertThat(get("/api/v1/platform/tenants", SUPPORT_WRITER, null).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/platform/tenants", SUPPORT_WRITER, session).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/impersonations", SUPPORT_WRITER, session).statusCode())
            .as("nor does the session hand them the customer's own permissions for free")
            .isEqualTo(404);
    }

    @Test
    void theCustomerCanSeeThatItHappenedWhenByWhomAndWhy() throws Exception {
        sessionId(start(SUPPORT, learner, REASON, false));

        HttpResponse<String> visible = get("/api/v1/impersonations", CUSTOMER_ADMIN, null);

        assertThat(visible.statusCode()).isEqualTo(200);
        assertThat(visible.body())
            .contains(REASON)
            .contains("support@__platform.test")
            .contains("\"impersonatedName\":\"acme-learner\"")
            .contains("\"writable\":false");
    }

    @Test
    void anotherCustomerSeesNoneOfIt() throws Exception {
        tenant("globex", "ACTIVE");
        AuthzFixtures.bootstrapAdmin(jdbc, "globex", "globex-admin");
        sessionId(start(SUPPORT, learner, REASON, false));

        assertThat(get("/api/v1/impersonations", "globex-admin~globex~TENANT", null).body())
            .isEqualTo("[]");
    }

    @Test
    void impersonationCannotCrossIntoASuspendedTenant() throws Exception {
        jdbc.update("UPDATE tenant SET status = 'SUSPENDED' WHERE tenant_id = ?", CUSTOMER);

        HttpResponse<String> refused = start(SUPPORT, learner, REASON, false);

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impersonation_session", Long.class))
            .isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log WHERE action = 'impersonation.refused' AND tenant_id = ?
            """, Long.class, CUSTOMER))
            .as("an attempt to enter a suspended account is exactly what the customer should see")
            .isEqualTo(1);
    }

    @Test
    void aTenantSuspendedUnderneathALiveSessionClosesIt() throws Exception {
        UUID session = sessionId(start(SUPPORT, learner, REASON, false));
        assertThat(get("/api/v1/me", SUPPORT, session).statusCode()).isEqualTo(200);

        jdbc.update("UPDATE tenant SET status = 'SUSPENDED' WHERE tenant_id = ?", CUSTOMER);

        assertThat(get("/api/v1/me", SUPPORT, session).body()).contains("IMPERSONATION_ACCOUNT_UNAVAILABLE");
        assertThat(jdbc.queryForObject(
            "SELECT ended_reason FROM impersonation_session WHERE id = ?", String.class, session))
            .isEqualTo(ImpersonationSessions.ENDED_ACCOUNT_UNAVAILABLE);
    }

    @Test
    void anEndedSessionStopsWorkingAndSoDoesAnExpiredOne() throws Exception {
        UUID ended = sessionId(start(SUPPORT, learner, REASON, false));
        assertThat(delete("/api/v1/platform/impersonations/" + ended, SUPPORT).statusCode())
            .isEqualTo(200);
        assertThat(get("/api/v1/me", SUPPORT, ended).body()).contains("IMPERSONATION_INVALID");

        UUID expired = sessionId(start(SUPPORT, learner, REASON, false));
        // started_at moves too: the window check refuses an expiry before its own start, which is
        // the constraint doing its job rather than a test being awkward.
        jdbc.update("""
            UPDATE impersonation_session
               SET started_at = now() - interval '2 hours', expires_at = now() - interval '1 minute'
             WHERE id = ?
            """, expired);

        assertThat(get("/api/v1/me", SUPPORT, expired).body()).contains("IMPERSONATION_INVALID");
        assertThat(jdbc.queryForObject(
            "SELECT ended_reason FROM impersonation_session WHERE id = ?", String.class, expired))
            .as("closed by the request that noticed, so the row says when it stopped being usable")
            .isEqualTo(ImpersonationSessions.ENDED_EXPIRED);
    }

    @Test
    void aSessionIdIsNotABearerTokenForWhoeverHoldsIt() throws Exception {
        UUID session = sessionId(start(SUPPORT, learner, REASON, false));

        assertThat(get("/api/v1/me", OTHER_ENGINEER, session).body())
            .as("a leaked id must not let another engineer act under somebody else's reason")
            .contains("IMPERSONATION_INVALID");
        assertThat(get("/api/v1/me", CUSTOMER_ADMIN, session).body())
            .as("nor a tenant-side caller who found one")
            .contains("IMPERSONATION_INVALID");
        assertThat(delete("/api/v1/platform/impersonations/" + session, OTHER_ENGINEER).statusCode())
            .isEqualTo(404);
    }

    @Test
    void everyEntryUnderASessionNamesBothPeople() throws Exception {
        UUID session = sessionId(start(SUPPORT, learner, REASON, false));

        List<Map<String, Object>> entries = jdbc.queryForList("""
            SELECT action, actor_user_id, impersonated_user_id, impersonation_session_id
              FROM audit_log WHERE impersonation_session_id IS NOT NULL
            """);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst()).containsEntry("action", "impersonation.start")
            .containsEntry("actor_user_id", platformUser("support"))
            .containsEntry("impersonated_user_id", learner)
            .containsEntry("impersonation_session_id", session);
    }

    /**
     * And again afterwards. The container is shared by every class in this module, and the new
     * foreign keys make leftovers somebody else's failure: a session row pins the {@code app_user}
     * rows it names, so the next class's {@code DELETE FROM app_user} fails in a test that has
     * never heard of impersonation.
     */
    @org.junit.jupiter.api.AfterEach
    void leaveNothingForTheNextClass() {
        emptyEverything();
    }

    /** Foreign-key order, not taste: audit entries point at sessions, sessions at users. */
    private void emptyEverything() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM impersonation_session");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM tenant");
    }

    private void tenant(String tenantId, String status) {
        jdbc.update("""
            INSERT INTO tenant (tenant_id, name, status, created_at, updated_at)
            VALUES (?, ?, ?, now(), now()) ON CONFLICT (tenant_id) DO UPDATE SET status = excluded.status
            """, tenantId, tenantId, status);
    }

    /** A platform-side role holding exactly these permissions, assigned to one engineer. */
    private void platformGrant(String username, Permission... permissions) {
        UUID userId = AuthzFixtures.ensureUser(jdbc, TenantFilter.PLATFORM_TENANT, username);
        UUID roleId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_role (id, tenant_id, name, description, side, system, created_at, updated_at)
            VALUES (?, ?, ?, 'Test grant', 'PLATFORM', false, now(), now())
            """, roleId, TenantFilter.PLATFORM_TENANT, "Grant " + username);
        for (Permission permission : permissions) {
            assertThat(permission.side()).isEqualTo(PermissionSide.PLATFORM);
            jdbc.update("""
                INSERT INTO role_permission (id, tenant_id, role_id, permission_code, created_at)
                VALUES (?, ?, ?, ?, now())
                """, UUID.randomUUID(), TenantFilter.PLATFORM_TENANT, roleId, permission.code());
        }
        jdbc.update("""
            INSERT INTO role_assignment (id, tenant_id, role_id, user_id, scope_type, granted_by, created_at)
            VALUES (?, ?, ?, ?, 'TENANT', ?, now())
            """, UUID.randomUUID(), TenantFilter.PLATFORM_TENANT, roleId, userId, userId);
    }

    private UUID platformUser(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE idp_sub = ?", UUID.class,
            "sub-" + username);
    }

    private HttpResponse<String> start(String token, UUID userId, String reason, boolean writable)
            throws Exception {
        return post("/api/v1/platform/impersonations", token, null, """
            {"tenantId":"%s","userId":"%s","reason":"%s","writable":%s}
            """.formatted(CUSTOMER, userId, reason, writable));
    }

    private static UUID sessionId(HttpResponse<String> started) {
        assertThat(started.statusCode()).as("%s", started.body()).isEqualTo(200);
        return UUID.fromString(started.body().replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1"));
    }

    private HttpResponse<String> get(String path, String token, UUID session) throws Exception {
        return send(request(path, token, session).GET());
    }

    private HttpResponse<String> post(String path, String token, UUID session, String body)
            throws Exception {
        return send(request(path, token, session)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return send(request(path, token, null).DELETE());
    }

    private HttpRequest.Builder request(String path, String token, UUID session) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
            .header("Authorization", "Bearer " + token);
        return session == null ? request : request.header(ImpersonationFilter.HEADER, session.toString());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
