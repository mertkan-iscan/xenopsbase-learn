package com.xenopsoftware.learn.identity.sso;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.identity.PostgresTestHarness;
import com.xenopsoftware.learn.identity.StubTokens;
import com.xenopsoftware.learn.identity.authz.AuthzFixtures;
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
 * Per-company SSO, through the real filter chain (T-1.8).
 *
 * <p>The criterion this test exists for is the last one — provider A cannot authenticate a user
 * into tenant B — and it is checked twice, because there are two ways to get it wrong and only
 * one of them looks like a bug. The obvious way is a lookup that trusts a claim; the quiet way is
 * an alias that two companies can both own, which makes "which provider signed you in" stop being
 * an answer at all.
 *
 * <p>Domain verification runs in {@code trusting} mode, set by {@code PostgresTestHarness} for
 * every context rather than by this class — {@code acme.test} can never publish a DNS record, and
 * a class that declares its own properties pays for its own Spring context.
 * {@link DnsDomainOwnershipTest} covers the shape of the real check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class TenantSsoTest extends PostgresTestHarness {

    private static final String ACME_ADMIN = "acme-admin~acme~TENANT";
    private static final String GLOBEX_ADMIN = "globex-admin~globex~TENANT";
    private static final String ACME_LEARNER = "acme-learner~acme~TENANT";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private RealmProviders realmProviders;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;

    @BeforeEach
    void twoCompaniesEachWithAnAdministrator() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEverything();
        tenant("acme");
        tenant("globex");
        AuthzFixtures.bootstrapAdmin(jdbc, "acme", "acme-admin");
        AuthzFixtures.bootstrapAdmin(jdbc, "globex", "globex-admin");
        AuthzFixtures.ensureUser(jdbc, "acme", "acme-learner");
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEverything();
    }

    @Test
    void theTenantTravelsToTheRealmFromOurRowAndNotFromTheRequest() throws Exception {
        assertThat(post("/api/v1/sso/providers", ACME_ADMIN, """
            {"alias":"acme-okta","kind":"OIDC","displayName":"Acme Okta",
             "issuer":"https://acme.example/oidc","clientId":"c","clientSecret":"s",
             "tenantId":"globex"}
            """).statusCode()).isEqualTo(200);

        // The request body carried tenantId: globex, which this API has no field for and would
        // not read if it did. What reached the realm is the caller's own company.
        TenantProvider applied = ((RecordingRealmProviders) realmProviders).applied().get("acme-okta");
        assertThat(applied).isNotNull();
        assertThat(applied.tenantId())
            .as("the provider carries the company whose administrator registered it")
            .isEqualTo("acme");
    }

    @Test
    void oneAliasBelongsToOneCompanyForever() throws Exception {
        assertThat(register(ACME_ADMIN, "shared-idp").statusCode()).isEqualTo(200);

        HttpResponse<String> stolen = register(GLOBEX_ADMIN, "shared-idp");

        // THE test the issue asks for, in its structural form: if globex could take this alias,
        // a login through it would land in whichever company wrote the row last, and "which
        // provider authenticated you" would stop being an answer to "which company are you in".
        assertThat(stolen.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
            "SELECT tenant_id FROM tenant_identity_provider WHERE alias = 'shared-idp'",
            String.class)).isEqualTo("acme");
    }

    @Test
    void aCompanyCannotReadOrDeleteAnotherCompanysProvider() throws Exception {
        register(ACME_ADMIN, "acme-okta");

        assertThat(get("/api/v1/sso/providers", GLOBEX_ADMIN).body()).isEqualTo("[]");
        assertThat(delete("/api/v1/sso/providers/acme-okta", GLOBEX_ADMIN).statusCode())
            .isEqualTo(404);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM tenant_identity_provider WHERE alias = 'acme-okta'", Long.class))
            .isEqualTo(1);
    }

    @Test
    void configuringSsoNeedsThePermissionAndALearnerHasNothingOfIt() throws Exception {
        // A learner holds content:view and no more. 404 rather than 403 on the read is the
        // disclosure rule (T-2.4): a denied GET must not confirm that there is something there.
        assertThat(get("/api/v1/sso/providers", ACME_LEARNER).statusCode()).isEqualTo(404);
        assertThat(register(ACME_LEARNER, "learner-idp").statusCode()).isIn(403, 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_identity_provider", Long.class))
            .isZero();
    }

    @Test
    void aClaimedDomainRoutesNobodyUntilItIsVerified() throws Exception {
        register(ACME_ADMIN, "acme-okta");
        UUID domain = domainId(post("/api/v1/sso/domains", ACME_ADMIN, "{\"domain\":\"acme.test\"}"));

        assertThat(discover("someone@acme.test").body())
            .as("claiming is not owning: an unverified claim must route nobody")
            .contains("\"provider\":null");

        assertThat(post("/api/v1/sso/domains/" + domain + "/verify", ACME_ADMIN, "{}").statusCode())
            .isEqualTo(200);

        assertThat(discover("someone@acme.test").body())
            .contains("\"provider\":\"acme-okta\"")
            .contains("Acme SSO");
    }

    @Test
    void aVerifiedDomainHasExactlyOneOwner() throws Exception {
        register(ACME_ADMIN, "acme-okta");
        register(GLOBEX_ADMIN, "globex-okta");
        UUID acme = domainId(post("/api/v1/sso/domains", ACME_ADMIN, "{\"domain\":\"shared.test\"}"));
        UUID globex = domainId(post("/api/v1/sso/domains", GLOBEX_ADMIN, "{\"domain\":\"shared.test\"}"));

        // Both may CLAIM it -- a claim blocks nothing, which is what stops a squatter reserving
        // a competitor's domain before they sign up.
        assertThat(post("/api/v1/sso/domains/" + acme + "/verify", ACME_ADMIN, "{}").statusCode())
            .isEqualTo(200);
        assertThat(post("/api/v1/sso/domains/" + globex + "/verify", GLOBEX_ADMIN, "{}").statusCode())
            .as("only one can prove it, and the index is what says so rather than a check")
            .isEqualTo(409);

        assertThat(discover("someone@shared.test").body()).contains("\"provider\":\"acme-okta\"");
    }

    @Test
    void anotherCompanyCannotVerifyAClaimItDidNotMake() throws Exception {
        register(ACME_ADMIN, "acme-okta");
        UUID acme = domainId(post("/api/v1/sso/domains", ACME_ADMIN, "{\"domain\":\"acme.test\"}"));

        assertThat(post("/api/v1/sso/domains/" + acme + "/verify", GLOBEX_ADMIN, "{}").statusCode())
            .isEqualTo(404);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM tenant_email_domain WHERE verified_at IS NOT NULL", Long.class))
            .isZero();
    }

    @Test
    void discoveryAnswersWithoutATokenAndTellsAStrangerNothingElse() throws Exception {
        register(ACME_ADMIN, "acme-okta");
        verifyDomain(ACME_ADMIN, "acme.test");

        HttpResponse<String> hit = discover("learner@acme.test");
        HttpResponse<String> miss = discover("someone@nobody.test");

        // Same status and the same two fields either way. A 404 for "no provider" would be the
        // same yes/no oracle in a tidier wrapper -- and would make an ordinary sign-in look like
        // an error to the page.
        assertThat(hit.statusCode()).isEqualTo(200);
        assertThat(miss.statusCode()).isEqualTo(200);
        assertThat(miss.body()).isEqualTo("{\"provider\":null,\"displayName\":null}");
        assertThat(hit.body())
            .as("the alias and the label, and never the company the domain belongs to")
            .doesNotContain("acme\"")
            .contains("acme-okta");
    }

    @Test
    void thereIsNoWayToAskWhoAllTheCustomersAre() throws Exception {
        register(ACME_ADMIN, "acme-okta");
        verifyDomain(ACME_ADMIN, "acme.test");

        // No listing, no prefix, no wildcard: the only question this endpoint answers is about
        // one address somebody already knew. Enumeration one domain at a time is inherent to
        // home-realm discovery and is a rate-limiting problem (T-8.7), not a lookup problem.
        assertThat(discover("@acme.test").body()).contains("\"provider\":null");
        assertThat(discover("acme.test").body()).contains("\"provider\":null");
        assertThat(discover("learner@ACME.TEST").body())
            .as("but the domain is a domain, so case must not decide the answer")
            .contains("acme-okta");
        assertThat(get("/api/v1/sso/providers", null).statusCode())
            .as("and nothing lists providers without a token")
            .isEqualTo(401);
    }

    @Test
    void twoProvidersInOneCompanyMakeDiscoveryDeclineRatherThanGuess() throws Exception {
        register(ACME_ADMIN, "acme-okta");
        register(ACME_ADMIN, "acme-entra");
        verifyDomain(ACME_ADMIN, "acme.test");

        assertThat(discover("learner@acme.test").body())
            .as("being sent to the wrong one of your own company's providers is a dead end a "
                + "learner cannot diagnose; the ordinary sign-in page can be")
            .contains("\"provider\":null");
    }

    private void verifyDomain(String token, String domain) throws Exception {
        UUID id = domainId(post("/api/v1/sso/domains", token, "{\"domain\":\"" + domain + "\"}"));
        assertThat(post("/api/v1/sso/domains/" + id + "/verify", token, "{}").statusCode())
            .isEqualTo(200);
    }

    private HttpResponse<String> register(String token, String alias) throws Exception {
        String company = alias.startsWith("globex") ? "Globex" : "Acme";
        return post("/api/v1/sso/providers", token, """
            {"alias":"%s","kind":"OIDC","displayName":"%s SSO","issuer":"https://%s.example/oidc",
             "clientId":"c","clientSecret":"s"}
            """.formatted(alias, company, alias));
    }

    private HttpResponse<String> discover(String email) throws Exception {
        return post("/api/v1/auth/discovery", null, "{\"email\":\"" + email + "\"}");
    }

    private static UUID domainId(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return UUID.fromString(response.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(request(path, token).GET());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return send(request(path, token).DELETE());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send(request(path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + environment.getProperty("local.server.port") + path));
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void tenant(String tenantId) {
        jdbc.update("""
            INSERT INTO tenant (tenant_id, name, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', now(), now()) ON CONFLICT (tenant_id) DO NOTHING
            """, tenantId, tenantId);
    }

    /** Foreign-key order, and the SSO tables first because nothing points at them. */
    private void emptyEverything() {
        for (String table : java.util.List.of("tenant_email_domain", "tenant_identity_provider",
                "audit_log", "impersonation_session", "role_assignment", "role_permission",
                "app_role", "group_membership", "user_group", "app_user", "tenant")) {
            jdbc.update("DELETE FROM " + table);
        }
    }
}
