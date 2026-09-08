package com.xenopsoftware.learn.assessment.bank;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Banks through the real filter chain (T-6.1).
 *
 * <p>Three of T-6.1's criteria are tested here: banks are per tenant, platform banks are readable
 * and copyable but never editable, and a copy is independent of what it was copied from. The other
 * two are not, and neither is silently absent — the authoring permission has nothing to enforce it
 * yet (see {@code CatalogCoverageTest}'s BANK_GAP), and "moving a question between banks" needs
 * questions, which arrive with T-6.2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class QuestionBankTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final String GLOBEX = "globex-author~globex~TENANT";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void emptyTheTables() {
        emptyEveryTable(dataSource);
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void aBankIsCreatedAndListed() throws Exception {
        HttpResponse<String> created = post("/api/v1/banks", ACME,
            "{\"name\":\"Fire safety\",\"description\":\"Everything about extinguishers\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("Fire safety");

        assertThat(get("/api/v1/banks", ACME).body()).contains("Fire safety");
    }

    @Test
    void twoBanksMayNotShareANameEvenInDifferentCase() throws Exception {
        post("/api/v1/banks", ACME, "{\"name\":\"Fire safety\"}");

        HttpResponse<String> clash = post("/api/v1/banks", ACME, "{\"name\":\"FIRE SAFETY\"}");

        assertThat(clash.statusCode()).isEqualTo(409);
        // The sentence matters as much as the status: a 409 an author cannot act on is a 409 they
        // will retry verbatim.
        assertThat(clash.body()).contains("already has a bank called");
        assertThat(clash.headers().firstValue("content-type").orElse(""))
            .contains("application/problem+json");
    }

    @Test
    void anotherCompanysBankIsNotVisibleAndItsIdResolvesToNothing() throws Exception {
        String body = post("/api/v1/banks", ACME, "{\"name\":\"Fire safety\"}").body();
        UUID acmeBankId = idOf(body);

        assertThat(get("/api/v1/banks", GLOBEX).body()).doesNotContain("Fire safety");

        // 404 rather than 403, and not because a check refused it: the discriminator filtered the
        // row, so there is nothing here to refuse access to (ADR-0102).
        assertThat(get("/api/v1/banks/" + acmeBankId, GLOBEX).statusCode()).isEqualTo(404);
    }

    @Test
    void aSharedPlatformBankIsReadableByACustomerAndTheirOwnBanksAreNot() throws Exception {
        UUID sharedId = platformBank("Regulatory basics", true);
        platformBank("Internal drafts", false);

        String library = get("/api/v1/shared-banks", ACME).body();

        assertThat(library).contains("Regulatory basics");
        // A platform bank that is not OFFERED is not in the library, and asking for it by id is
        // indistinguishable from asking for one that does not exist.
        assertThat(library).doesNotContain("Internal drafts");
        assertThat(sharedId).isNotNull();

        // And the platform's banks never appear among the customer's own.
        assertThat(get("/api/v1/banks", ACME).body()).doesNotContain("Regulatory basics");
    }

    @Test
    void aCopyIsIndependentOfWhatItWasCopiedFrom() throws Exception {
        UUID sharedId = platformBank("Regulatory basics", true);

        HttpResponse<String> copied =
            post("/api/v1/shared-banks/" + sharedId + "/copies", ACME, "{}");

        assertThat(copied.statusCode()).isEqualTo(201);
        assertThat(copied.body()).contains("Regulatory basics");
        assertThat(copied.body()).contains(sharedId.toString()); // recorded as provenance
        UUID copyId = idOf(copied.body());

        // The upstream is edited AND withdrawn from the library afterwards.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE question_bank SET name = ?, shared = false WHERE id = ?",
            "Regulatory basics (2027 revision)", sharedId);

        String copy = get("/api/v1/banks/" + copyId, ACME).body();

        // Neither change reached the copy. There is no link through which it could, which is the
        // point of copied_from_bank_id being a record rather than a foreign key.
        assertThat(copy).contains("Regulatory basics");
        assertThat(copy).doesNotContain("2027 revision");
    }

    @Test
    void aBankThatIsNotOfferedCannotBeCopied() throws Exception {
        UUID privateId = platformBank("Internal drafts", false);

        HttpResponse<String> refused =
            post("/api/v1/shared-banks/" + privateId + "/copies", ACME, "{}");

        assertThat(refused.statusCode()).isEqualTo(404);
    }

    @Test
    void aCopyMayBeRenamedOnTheWayIn() throws Exception {
        UUID sharedId = platformBank("Regulatory basics", true);

        HttpResponse<String> copied = post("/api/v1/shared-banks/" + sharedId + "/copies", ACME,
            "{\"name\":\"Our regulatory basics\"}");

        assertThat(copied.statusCode()).isEqualTo(201);
        assertThat(copied.body()).contains("Our regulatory basics");
    }

    /**
     * A bank in the platform's own reserved tenant, written with explicit SQL.
     *
     * <p>Deliberately not through the API and not through a repository: {@code @TenantId} binds
     * the tenant when the session opens, so there is no supported way to write another tenant's
     * row through JPA — which is the property being relied on, not a limitation being worked
     * around.
     */
    private UUID platformBank(String name, boolean shared) {
        UUID id = UUID.randomUUID();
        // OffsetDateTime, not Instant: pgjdbc refuses to infer a SQL type for an Instant
        // ("Can't infer the SQL type to use"), and the failure surfaces as a bad-grammar
        // exception naming the whole statement, which sends you looking at the SQL.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        new JdbcTemplate(dataSource).update(
            "INSERT INTO question_bank (id, tenant_id, name, description, shared, created_at, updated_at)"
                + " VALUES (?, '__platform', ?, ?, ?, ?, ?)",
            id, name, "Offered by the platform", shared, now, now);
        return id;
    }

    private static UUID idOf(String json) {
        int at = json.indexOf("\"id\":\"") + 6;
        return UUID.fromString(json.substring(at, at + 36));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
