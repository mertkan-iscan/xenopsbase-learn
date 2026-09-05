package com.xenopsoftware.learn.identity.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The person lifecycle end to end (T-1.9), through the real chain: invite, accept, deactivate,
 * reactivate, re-address, import.
 *
 * <p>Over HTTP rather than against the service, because two of the things this task promises are
 * not service behaviour at all — the refusal a deactivated person meets is a filter, and the
 * check that only somebody holding {@code user:manage} may do any of this is method security.
 * Calling the service directly would test neither.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class UserLifecycleTest extends PostgresTestHarness {

    private static final String ADMIN = "lifecycle-admin~acme~TENANT";

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;

    @BeforeEach
    void anAdministratorWhoMayManagePeople() {
        jdbc = new JdbcTemplate(dataSource);
        removeEverything();
        AuthzFixtures.bootstrapAdmin(jdbc, "acme", "lifecycle-admin");
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        removeEverything();
    }

    @Test
    void anInvitedPersonIsClaimedByTheirOwnFirstSignIn() throws Exception {
        UUID invited = idOf(invite("chris@acme.test", "Chris Invited"));
        assertThat(statusOf(invited)).isEqualTo("INVITED");
        assertThat(subOf(invited)).isNull();

        // No token in sight: the ordinary path is signing in with a verified address that
        // matches the invitation (T-1.5), and it claims the row that is already waiting.
        JsonNode me = json.readTree(expect(200, get("/api/v1/me", "chris~acme~TENANT")).body());

        assertThat(UUID.fromString(me.path("id").asText())).isEqualTo(invited);
        assertThat(statusOf(invited)).isEqualTo("ACTIVE");
        assertThat(countOfPeople()).isEqualTo(2);
    }

    @Test
    void anInvitationTokenWorksOnceAndTheSecondAttemptFindsNothing() throws Exception {
        JsonNode invitation = invite("dana@acme.test", "Dana Invited");
        String token = invitation.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(invitation.path("expiresAt").asText()).isNotBlank();

        JsonNode accepted = json.readTree(expect(200,
            post("/api/v1/users/invitations/accept", "alex~acme~TENANT", body(token))).body());

        // The token is the credential, so the identity that accepts need not be the address that
        // was invited -- and the row it claims is the invited one, not a second account.
        assertThat(UUID.fromString(accepted.path("id").asText())).isEqualTo(idOf(invitation));
        assertThat(accepted.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(subOf(idOf(invitation))).isEqualTo("sub-alex");
        assertThat(hashOf(idOf(invitation))).as("single use: the verifier is gone").isNull();

        assertThat(post("/api/v1/users/invitations/accept", "sam~acme~TENANT", body(token))
            .statusCode()).isEqualTo(404);
    }

    @Test
    void anExpiredInvitationIsRefusedAndReinvitingRotatesTheToken() throws Exception {
        JsonNode first = invite("eve@acme.test", "Eve Invited");
        String expired = first.path("token").asText();
        jdbc.update("UPDATE app_user SET invitation_expires_at = now() - interval '1 day' WHERE id = ?",
            idOf(first));

        assertThat(post("/api/v1/users/invitations/accept", "alex~acme~TENANT", body(expired))
            .statusCode())
            .as("410, not 404: the invitation existed, and saying so is what lets a UI offer a "
                + "new one instead of claiming the address is unknown")
            .isEqualTo(410);

        String reissued = invite("eve@acme.test", "Eve Invited").path("token").asText();
        assertThat(reissued).isNotEqualTo(expired);
        assertThat(post("/api/v1/users/invitations/accept", "alex~acme~TENANT", body(expired))
            .statusCode()).as("re-inviting rotates rather than adds a second way in").isEqualTo(404);
        assertThat(post("/api/v1/users/invitations/accept", "alex~acme~TENANT", body(reissued))
            .statusCode()).isEqualTo(200);
    }

    @Test
    void deactivationStopsTheNextRequestAndKeepsEverythingTheyDid() throws Exception {
        UUID fin = signIn("fin");
        UUID group = idOf(json.readTree(expect(200, post("/api/v1/groups", ADMIN,
            "{\"name\":\"Engineering\"}")).body()));
        expect(200, post("/api/v1/groups/" + group + "/members/" + fin, ADMIN, null));
        UUID role = idOf(json.readTree(expect(200, post("/api/v1/roles", ADMIN,
            "{\"name\":\"Reader\",\"description\":\"Reads\"}")).body()));
        expect(200, put("/api/v1/roles/" + role + "/permissions", ADMIN,
            "{\"permissions\":[\"group:read\"]}"));
        expect(200, post("/api/v1/assignments", ADMIN, "{\"roleId\":\"" + role + "\",\"userId\":\""
            + fin + "\",\"scopeType\":\"TENANT\"}"));

        expect(200, post("/api/v1/users/" + fin + "/deactivate", ADMIN, null));

        HttpResponse<String> refused = get("/api/v1/me", "fin~acme~TENANT");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body())
            .as("a machine-readable reason, so a UI can say something true")
            .contains("USER_DEACTIVATED");

        assertThat(statusOf(fin)).isEqualTo("DEACTIVATED");
        assertThat(jdbc.queryForObject(
            "SELECT deactivated_at IS NOT NULL FROM app_user WHERE id = ?", Boolean.class, fin))
            .isTrue();
        assertThat(count("group_membership", fin)).as("still a member of what they were in").isEqualTo(1);
        assertThat(count("role_assignment", fin)).as("still holds what they were given").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'user.deactivate' AND target_id = ?",
            Integer.class, fin)).isEqualTo(1);
    }

    @Test
    void reactivationRestoresAccessWithoutCreatingASecondIdentity() throws Exception {
        UUID fin = signIn("fin");
        expect(200, post("/api/v1/users/" + fin + "/deactivate", ADMIN, null));
        assertThat(get("/api/v1/me", "fin~acme~TENANT").statusCode()).isEqualTo(403);

        expect(200, post("/api/v1/users/" + fin + "/reactivate", ADMIN, null));

        JsonNode me = json.readTree(expect(200, get("/api/v1/me", "fin~acme~TENANT")).body());
        assertThat(UUID.fromString(me.path("id").asText())).isEqualTo(fin);
        assertThat(subOf(fin)).isEqualTo("sub-fin");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM app_user WHERE idp_sub = 'sub-fin'", Integer.class))
            .as("one person, not a second account that happens to share an address")
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT deactivated_at IS NULL FROM app_user WHERE id = ?", Boolean.class, fin)).isTrue();
    }

    @Test
    void changingAnAddressMovesTheRowAndTheHistoryFollows() throws Exception {
        UUID fin = signIn("fin");
        UUID group = idOf(json.readTree(expect(200, post("/api/v1/groups", ADMIN,
            "{\"name\":\"Engineering\"}")).body()));
        expect(200, post("/api/v1/groups/" + group + "/members/" + fin, ADMIN, null));

        expect(200, put("/api/v1/users/" + fin, ADMIN,
            "{\"email\":\"fin.renamed@acme.test\",\"displayName\":\"Fin Renamed\"}"));

        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, fin))
            .isEqualTo("fin.renamed@acme.test");
        assertThat(countOfPeople()).as("moved, not duplicated").isEqualTo(2);
        assertThat(count("group_membership", fin))
            .as("everything points at app_user.id, so history needs no migration (ADR-0104)")
            .isEqualTo(1);

        // And the address is still one person's: the unique index is the promise, this is the
        // conflict a caller sees instead of a 500.
        UUID other = signIn("robin");
        assertThat(put("/api/v1/users/" + other, ADMIN,
            "{\"email\":\"fin.renamed@acme.test\",\"displayName\":\"Robin\"}").statusCode())
            .isEqualTo(409);
    }

    @Test
    void nobodyDeactivatesThemselves() throws Exception {
        UUID admin = jdbc.queryForObject(
            "SELECT id FROM app_user WHERE idp_sub = 'sub-lifecycle-admin'", UUID.class);

        assertThat(post("/api/v1/users/" + admin + "/deactivate", ADMIN, null).statusCode())
            .as("the last administrator locking themselves out is a support ticket we cannot "
                + "answer without touching their database")
            .isEqualTo(409);
    }

    @Test
    void aDryRunReportsEveryRowAndChangesNothing() throws Exception {
        JsonNode report = json.readTree(expect(200, csv(spreadsheet(), true)).body());

        assertThat(report.path("dryRun").asBoolean()).isTrue();
        assertThat(report.path("invited").asInt()).isEqualTo(2);
        assertThat(report.path("skipped").asInt()).as("the administrator is already here").isEqualTo(1);
        assertThat(report.path("failed").asInt()).as("a duplicate row and a bad address").isEqualTo(2);

        assertThat(actionsIn(report)).containsExactly("INVITE", "INVITE", "SKIP", "ERROR", "ERROR");
        // Line numbers are the file's, header included, so an error points at the line the
        // customer can see in their own editor: the duplicate is line 5, the bad address line 6.
        assertThat(report.toString()).contains("\"line\":5,\"email\":\"gita@acme.test\",\"action\":\"ERROR\"");
        assertThat(report.toString()).contains("\"line\":6,\"email\":\"not-an-address\",\"action\":\"ERROR\"");
        assertThat(countOfPeople()).as("a dry run writes nothing at all").isEqualTo(1);
        assertThat(report.toString()).doesNotContain("\"token\":\"");
    }

    @Test
    void theRealRunDoesExactlyWhatTheDryRunPromised() throws Exception {
        JsonNode dryRun = json.readTree(expect(200, csv(spreadsheet(), true)).body());
        JsonNode real = json.readTree(expect(200, csv(spreadsheet(), false)).body());

        assertThat(actionsIn(real)).isEqualTo(actionsIn(dryRun));
        assertThat(real.path("invited").asInt()).isEqualTo(dryRun.path("invited").asInt());
        assertThat(countOfPeople()).as("two invited, nothing else").isEqualTo(3);

        // A quoted display name carrying a comma survives, which is what makes this a
        // spreadsheet reader rather than a split on commas.
        assertThat(jdbc.queryForObject("SELECT display_name FROM app_user WHERE email = ?",
            String.class, "gita@acme.test")).isEqualTo("Gita Rao, PhD");

        String token = real.path("rows").get(0).path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(post("/api/v1/users/invitations/accept", "alex~acme~TENANT", body(token))
            .statusCode()).as("the tokens an import returns are the real ones").isEqualTo(200);

        // Run it again: the two invitations are still open, so they rotate rather than duplicate.
        JsonNode again = json.readTree(expect(200, csv(spreadsheet(), false)).body());
        assertThat(again.path("reinvited").asInt()).isEqualTo(1);
        assertThat(again.path("skipped").asInt()).as("the one just accepted now has an account")
            .isEqualTo(2);
        assertThat(countOfPeople()).isEqualTo(3);
    }

    @Test
    void managingPeopleNeedsUserManageAndTellsACallerWithoutItNothing() throws Exception {
        signIn("bystander");

        assertThat(post("/api/v1/users/invitations", "bystander~acme~TENANT",
            "{\"email\":\"someone@acme.test\",\"displayName\":\"Someone\"}").statusCode())
            .as("404 rather than 403: holding no user:read, they are not told the surface exists")
            .isEqualTo(404);
        assertThat(countOfPeople()).isEqualTo(2);
    }

    /**
     * A file with everything a real one has: a BOM, CRLF endings, a quoted display name with a
     * comma in it, somebody who already has an account, the same address twice, and something
     * that is not an address at all.
     */
    // ---------------------------------------------------------------- where somebody is (T-5.6)

    @Test
    void aPersonSetsTheirOwnTimezoneAndItIsAnnouncedToWhoeverNeedsIt() throws Exception {
        UUID learner = signIn("kaya");

        HttpResponse<String> set = expect(200, put("/api/v1/users/me/timezone",
            "kaya~acme~TENANT", "{\"timeZone\":\"Europe/Istanbul\"}"));

        assertThat(json.readTree(set.body()).path("timeZone").asText())
            .as("the value is normalised on the way in, so the caller is shown what was actually "
                + "stored rather than left to assume their string survived")
            .isEqualTo("Europe/Istanbul");
        assertThat(jdbc.queryForObject("SELECT time_zone FROM app_user WHERE id = ?", String.class,
            learner))
            .as("a deadline expires when the day ends where the LEARNER is, and this is the only "
                + "place that fact is recorded (T-5.6)")
            .isEqualTo("Europe/Istanbul");
        assertThat(jdbc.queryForList(
            "SELECT payload::text FROM outbox WHERE topic = 'identity.user.profile'", String.class))
            .as("catalog computes the deadline and must not read this table to do it (ADR-0109), "
                + "so the zone travels as an event -- written in the transaction that changed it")
            .isNotEmpty();
        assertThat(jdbc.queryForList(
            "SELECT payload::text FROM outbox WHERE topic = 'identity.user.profile'", String.class)
            .toString())
            .contains("Europe/Istanbul");
    }

    @Test
    void aTimezoneThePlatformCannotResolveIsRefusedRatherThanStored() throws Exception {
        signIn("kaya");

        HttpResponse<String> refused = put("/api/v1/users/me/timezone", "kaya~acme~TENANT",
            "{\"timeZone\":\"Middle/Earth\"}");

        // The failure of an unparseable zone is remote and quiet -- a reminder at the wrong hour,
        // or somebody marked late on a day they were not -- so it is refused where it is set.
        //
        // The status and nothing else: no service in this platform returns an exception message in
        // a response body, so the sentence the refusal carries is for whoever reads the log.
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE time_zone IS NOT NULL",
            Integer.class)).isZero();
    }

    @Test
    void clearingItPutsThemBackInTheHaveNotSaidPopulation() throws Exception {
        UUID learner = signIn("kaya");
        expect(200, put("/api/v1/users/me/timezone", "kaya~acme~TENANT",
            "{\"timeZone\":\"Europe/Istanbul\"}"));

        expect(200, put("/api/v1/users/me/timezone", "kaya~acme~TENANT", "{\"timeZone\":\"\"}"));

        assertThat(jdbc.queryForObject("SELECT time_zone FROM app_user WHERE id = ?", String.class,
            learner))
            .as("null is a state and not a missing value: somebody who has left the country "
                + "should be asked again rather than reckoned in a zone they no longer live in")
            .isNull();
    }

    private static String spreadsheet() {
        return "﻿email,displayName\r\n"
            + "gita@acme.test,\"Gita Rao, PhD\"\r\n"
            + "hana@acme.test,Hana Ito\r\n"
            + "lifecycle-admin@acme.test,Already Here\r\n"
            + "gita@acme.test,Gita Again\r\n"
            + "not-an-address,Nobody\r\n";
    }

    private java.util.List<String> actionsIn(JsonNode report) {
        java.util.List<String> actions = new java.util.ArrayList<>();
        report.path("rows").forEach(row -> actions.add(row.path("action").asText()));
        return actions;
    }

    private JsonNode invite(String email, String displayName) throws Exception {
        return json.readTree(expect(200, post("/api/v1/users/invitations", ADMIN,
            "{\"email\":\"" + email + "\",\"displayName\":\"" + displayName + "\"}")).body());
    }

    /** Somebody signing in for the first time, provisioned by {@code /me} exactly as T-1.2 says. */
    private UUID signIn(String username) throws Exception {
        return UUID.fromString(json.readTree(
            expect(200, get("/api/v1/me", username + "~acme~TENANT")).body()).path("id").asText());
    }

    private static String body(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private UUID idOf(JsonNode node) {
        String id = node.has("userId") ? node.path("userId").asText() : node.path("id").asText();
        return UUID.fromString(id);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, id);
    }

    private String subOf(UUID id) {
        return jdbc.queryForObject("SELECT idp_sub FROM app_user WHERE id = ?", String.class, id);
    }

    private String hashOf(UUID id) {
        return jdbc.queryForObject(
            "SELECT invitation_token_hash FROM app_user WHERE id = ?", String.class, id);
    }

    private Integer countOfPeople() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM app_user WHERE tenant_id = 'acme'", Integer.class);
    }

    private Integer count(String table, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?",
            Integer.class, userId);
    }

    private HttpResponse<String> csv(String content, boolean dryRun) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/users/import?dryRun=" + dryRun))
            .header("Authorization", "Bearer " + ADMIN)
            .header("Content-Type", "text/csv")
            .POST(HttpRequest.BodyPublishers.ofString(content)));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token));
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> put(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static HttpResponse<String> expect(int status, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(status);
        return response;
    }

    private void removeEverything() {
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }
}
