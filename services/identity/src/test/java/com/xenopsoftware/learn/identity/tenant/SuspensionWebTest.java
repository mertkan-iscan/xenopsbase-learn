package com.xenopsoftware.learn.identity.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import com.xenopsoftware.learn.identity.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Suspension through the real filter chain (T-1.4): what a customer can still do, what they
 * cannot, and how quickly the change reaches them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class SuspensionWebTest extends PostgresTestHarness {

    @Autowired
    private TenantStatusService status;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;

    @BeforeEach
    void anActiveCompany() {
        jdbc = new JdbcTemplate(dataSource);
        clear();
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES ('acme', 'Acme')");
    }

    @AfterEach
    void clearAfter() {
        clear();
    }

    @Test
    void aSuspensionMidSessionStopsTheVeryNextRequest() throws Exception {
        // The session is already in flight: this caller has been through the chain once.
        assertThat(get("/api/v1/me", "casey~acme~TENANT").statusCode()).isEqualTo(200);

        changeAsPlatform("acme", AccountStatus.SUSPENDED, "unpaid invoice");

        // No waiting for a token to expire, no cache to evict by hand: the next request.
        HttpResponse<String> refused = get("/api/v1/me", "casey~acme~TENANT");
        assertThat(refused.statusCode()).isEqualTo(403);
        // Machine-readable, so a UI can say something true rather than parsing prose.
        assertThat(refused.body()).contains("\"code\":\"ACCOUNT_SUSPENDED\"");
    }

    @Test
    void readOnlyKeepsTheReadsAndRefusesTheWrites() throws Exception {
        changeAsPlatform("acme", AccountStatus.READ_ONLY, "payment dispute");

        // The middle state earns its place here: a customer in a dispute can still get their
        // data out, which is what makes suspension a usable tool rather than a last resort.
        assertThat(get("/api/v1/me", "casey~acme~TENANT").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/groups", "casey~acme~TENANT").statusCode()).isEqualTo(200);

        HttpResponse<String> write = post("/api/v1/groups",
            "{\"name\":\"New department\"}", "casey~acme~TENANT");
        assertThat(write.statusCode()).isEqualTo(403);
        assertThat(write.body()).contains("\"code\":\"ACCOUNT_READ_ONLY\"");
    }

    @Test
    void reinstatingIsEffectiveOnTheNextRequestToo() throws Exception {
        changeAsPlatform("acme", AccountStatus.SUSPENDED, "mistake");
        assertThat(get("/api/v1/me", "casey~acme~TENANT").statusCode()).isEqualTo(403);

        changeAsPlatform("acme", AccountStatus.ACTIVE, "resolved");

        assertThat(get("/api/v1/me", "casey~acme~TENANT").statusCode()).isEqualTo(200);
    }

    @Test
    void oneCompanySuspensionDoesNotTouchAnother() throws Exception {
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES ('globex', 'Globex')");
        changeAsPlatform("acme", AccountStatus.SUSPENDED, "unpaid");

        assertThat(get("/api/v1/me", "casey~acme~TENANT").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/me", "rival~globex~TENANT").statusCode()).isEqualTo(200);
    }

    @Test
    void theWriteIsRefusedEvenIfTheEdgeSaysOtherwise() throws Exception {
        // The boundary, not the fast path (T-1.4's third criterion). The published entry is
        // deliberately left saying ACTIVE while the row says SUSPENDED -- exactly the window a
        // stale cache opens -- and the write is still refused, by the check inside the
        // transaction that does the writing.
        jdbc.update("UPDATE tenant SET status = 'SUSPENDED' WHERE tenant_id = 'acme'");

        HttpResponse<String> write = post("/api/v1/groups",
            "{\"name\":\"Snuck in\"}", "casey~acme~TENANT");

        assertThat(write.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM user_group WHERE tenant_id = 'acme'", Long.class)).isZero();
    }

    @Test
    void statusChangesAreAudited() throws Exception {
        changeAsPlatform("acme", AccountStatus.SUSPENDED, "unpaid invoice");

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log
             WHERE action = 'tenant.status'
               AND payload @> ('{"tenantId": "acme", "after": "SUSPENDED"}')::jsonb
               AND payload @> ('{"reason": "unpaid invoice"}')::jsonb
            """, Long.class)).isEqualTo(1);
    }

    /**
     * A status change the way a request makes one: as platform staff, bound to the platform's
     * own tenant. TenantFilter does this before the handler runs; a test calling the service
     * directly has to do it itself.
     */
    private void changeAsPlatform(String tenantId, AccountStatus wanted, String reason) {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.oauth2.server.resource
                .authentication.JwtAuthenticationToken(
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                    .header("alg", "none").subject("sub-ops")
                    .claim("email", "ops@xenopslearn.test").claim("email_verified", true)
                    .claim("name", "Ops").claim("side", "PLATFORM")
                    .issuedAt(java.time.Instant.now())
                    .expiresAt(java.time.Instant.now().plusSeconds(60)).build()));
        try {
            com.xenopsoftware.learn.common.tenancy.TenantContext.callWithUnchecked(
                com.xenopsoftware.learn.common.tenancy.TenantFilter.PLATFORM_TENANT,
                () -> status.change(tenantId, wanted, reason));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private void clear() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user WHERE tenant_id IN ('acme', 'globex')");
        jdbc.update("DELETE FROM app_user WHERE idp_sub = 'sub-ops'");
        jdbc.update("DELETE FROM tenant WHERE tenant_id IN ('acme', 'globex')");
    }
}
