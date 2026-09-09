package com.xenopsoftware.learn.assessment.question;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.StubTokens;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A question is an identity and a version is what was asked (T-6.2, ADR-0106).
 *
 * <p>The test that matters is {@link #editingAServedQuestionLeavesWhatWasAskedExactlyAsItWas}: it
 * is the ADR's whole property, and it is the one that would fail silently in a model where an edit
 * reached the row an attempt points at.
 *
 * <p><b>Where these stop short, and it is one place.</b> T-6.2's fourth criterion says "a test that
 * ANSWERS a question, edits it, and asserts the recorded attempt still renders exactly what was
 * served". There is no attempt to record: {@code attempt_response} is T-6.6's table. What is
 * exercised instead is everything on this side of that foreign key — the version is served through
 * {@link ServedVersions}, exactly as delivery will serve it, its id is held the way an attempt will
 * hold it, and it is dereferenced afterwards through the endpoint a review screen will use. The
 * missing half is the row that stores the id, not the behaviour being relied on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class QuestionTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final String GLOBEX = "globex-author~globex~TENANT";

    /**
     * A well-formed single-choice question (T-6.3 gave the body a shape).
     *
     * <p>These fixtures were three fields deep and shapeless until T-6.3; they are a real question
     * now, which is what makes the edits below edits to something a learner could have sat.
     */
    private static final String ORIGINAL = """
        {"type":"single-choice","stem":"Which extinguisher suits an electrical fire?",
         "options":{"choices":[{"id":"a","text":"Water"},{"id":"b","text":"CO2"}]},
         "answerKey":{"correct":["b"]}}""";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private ServedVersions served;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void emptyTheTables() {
        emptyEveryTable(dataSource);
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void aQuestionIsCreatedOnItsFirstVersion() throws Exception {
        UUID bank = bank("Fire safety", ACME);

        HttpResponse<String> created = post("/api/v1/banks/" + bank + "/questions", ACME,
            "{\"internalName\":\"Electrical fire\",\"body\":" + ORIGINAL + "}");

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode question = json.readTree(created.body());
        assertThat(question.get("currentVersion").get("version").asInt()).isEqualTo(1);
        assertThat(question.get("currentVersion").get("firstServedAt").isNull())
            .as("nobody has been served it, so it is still a draft")
            .isTrue();
        assertThat(question.get("currentVersion").get("body").get("stem").asString())
            .isEqualTo("Which extinguisher suits an electrical fire?");

        assertThat(get("/api/v1/banks/" + bank + "/questions", ACME).body())
            .contains("Electrical fire");
    }

    /**
     * The typo fixed before anybody sat the test. ADR-0106 is explicit that this must leave nothing
     * behind: versioning on every keystroke produces a history nobody can navigate.
     */
    @Test
    void correctingADraftLeavesNoTrace() throws Exception {
        UUID question = question("Electrical fire", ORIGINAL);

        put("/api/v1/questions/" + question, ACME, """
            {"body":{"type":"single-choice","stem":"Which extinguisher suits an electrical fire",
             "options":{"choices":[{"id":"a","text":"Water"},{"id":"b","text":"CO2"}]},
             "answerKey":{"correct":["b"]}}}""");

        JsonNode current = json.readTree(get("/api/v1/questions/" + question, ACME).body())
            .get("currentVersion");
        assertThat(current.get("version").asInt()).isEqualTo(1);
        assertThat(current.get("body").get("stem").asString()).doesNotEndWith("?");

        assertThat(json.readTree(get("/api/v1/questions/" + question + "/versions", ACME).body()))
            .as("one row, corrected, and no archaeology of a question that never reached anyone")
            .hasSize(1);
    }

    /** ADR-0106's property, and the reason the ADR exists. */
    @Test
    void editingAServedQuestionLeavesWhatWasAskedExactlyAsItWas() throws Exception {
        UUID question = question("Electrical fire", ORIGINAL);
        UUID asked = currentVersionOf(question);

        // Delivery hands the question to a learner. An attempt would record exactly this id.
        assertThat(serve(asked)).isTrue();

        put("/api/v1/questions/" + question, ACME, """
            {"body":{"type":"single-choice","stem":"Which extinguisher suits an electrical fire?",
             "options":{"choices":[{"id":"a","text":"Water"},{"id":"b","text":"CO2"},
                                   {"id":"c","text":"Foam"}]},
             "answerKey":{"correct":["c"]}}}""");

        JsonNode current = json.readTree(get("/api/v1/questions/" + question, ACME).body())
            .get("currentVersion");
        assertThat(current.get("version").asInt()).isEqualTo(2);
        assertThat(current.get("id").asString()).isNotEqualTo(asked.toString());

        // What the learner was actually asked, dereferenced the way a review screen will.
        JsonNode wasAsked = json.readTree(
            get("/api/v1/questions/" + question + "/versions/" + asked, ACME).body());
        assertThat(wasAsked.get("body")).isEqualTo(json.readTree(ORIGINAL));
        assertThat(wasAsked.get("body").get("options").get("choices")).hasSize(2);
        assertThat(wasAsked.get("body").get("answerKey").get("correct").get(0).asString())
            .as("the answer key that marked this learner, not the corrected one")
            .isEqualTo("b");
        assertThat(wasAsked.get("firstServedAt").isNull()).isFalse();
    }

    /**
     * The internal name and bank membership are not what anybody was asked, so neither produces a
     * version even after the question has been served. ADR-0106 says so; this is where it is true.
     */
    @Test
    void renamingAndMovingAreNotEditsToWhatWasAsked() throws Exception {
        UUID question = question("Electrical fire", ORIGINAL);
        UUID asked = currentVersionOf(question);
        serve(asked);
        UUID otherBank = bank("Electrical safety", ACME);

        put("/api/v1/questions/" + question, ACME, "{\"internalName\":\"Electrical fires (2027)\"}");
        put("/api/v1/questions/" + question + "/bank", ACME, "{\"bankId\":\"" + otherBank + "\"}");

        JsonNode question2 = json.readTree(get("/api/v1/questions/" + question, ACME).body());
        assertThat(question2.get("internalName").asString()).isEqualTo("Electrical fires (2027)");
        assertThat(question2.get("bankId").asString()).isEqualTo(otherBank.toString());
        assertThat(question2.get("currentVersion").get("id").asString()).isEqualTo(asked.toString());
        assertThat(json.readTree(get("/api/v1/questions/" + question + "/versions", ACME).body()))
            .hasSize(1);
    }

    /**
     * An authoring screen that round-trips the document must not mint a version for a change
     * nobody made — including when it serialises the same fields in a different order.
     */
    @Test
    void sendingTheSameBodyBackIsNotAnEdit() throws Exception {
        UUID question = question("Electrical fire", ORIGINAL);
        serve(currentVersionOf(question));

        put("/api/v1/questions/" + question, ACME, """
            {"body":{"answerKey":{"correct":["b"]},
             "options":{"choices":[{"id":"a","text":"Water"},{"id":"b","text":"CO2"}]},
             "stem":"Which extinguisher suits an electrical fire?","type":"single-choice"}}""");

        assertThat(json.readTree(get("/api/v1/questions/" + question + "/versions", ACME).body()))
            .as("the same question, with its fields in another order")
            .hasSize(1);
    }

    @Test
    void aServedQuestionIsRetiredRatherThanDeleted() throws Exception {
        UUID bank = bank("Fire safety", ACME);
        UUID question = question(bank, "Electrical fire", ORIGINAL);
        UUID asked = currentVersionOf(question);
        serve(asked);

        HttpResponse<String> deleted = delete("/api/v1/questions/" + question, ACME);

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(json.readTree(deleted.body()).get("retired").asBoolean()).isTrue();

        // Gone from authoring...
        assertThat(get("/api/v1/questions/" + question, ACME).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/banks/" + bank + "/questions", ACME).body())
            .doesNotContain("Electrical fire");

        // ...and still answerable about what it asked, which is the whole difference.
        HttpResponse<String> history =
            get("/api/v1/questions/" + question + "/versions/" + asked, ACME);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(json.readTree(history.body()).get("body")).isEqualTo(json.readTree(ORIGINAL));
    }

    @Test
    void aQuestionNobodyWasEverServedIsDeletedOutright() throws Exception {
        UUID question = question("Electrical fire", ORIGINAL);

        HttpResponse<String> deleted = delete("/api/v1/questions/" + question, ACME);

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(json.readTree(deleted.body()).get("retired").asBoolean()).isFalse();
        assertThat(get("/api/v1/questions/" + question + "/versions", ACME).statusCode())
            .as("nothing referenced it, so there is nothing left of it")
            .isEqualTo(404);
    }

    @Test
    void anotherCompanysQuestionResolvesToNothing() throws Exception {
        UUID question = question("Electrical fire", ORIGINAL);

        // 404 rather than 403, and not because a check refused it: the discriminator filtered the
        // row, so there is nothing here to refuse access to (ADR-0102).
        assertThat(get("/api/v1/questions/" + question, GLOBEX).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/questions/" + question + "/versions", GLOBEX).statusCode())
            .isEqualTo(404);
    }

    @Test
    void aQuestionCannotBeCreatedInAnotherCompanysBank() throws Exception {
        UUID acmeBank = bank("Fire safety", ACME);

        HttpResponse<String> refused = post("/api/v1/banks/" + acmeBank + "/questions", GLOBEX,
            "{\"internalName\":\"Smuggled\",\"body\":" + ORIGINAL + "}");

        assertThat(refused.statusCode()).isEqualTo(404);
    }

    /** Serving, as delivery will do it: the bean, inside the calling tenant's session. */
    private boolean serve(UUID versionId) {
        return TenantContext.callWithUnchecked("acme", () -> served.markServed(versionId));
    }

    private UUID currentVersionOf(UUID question) throws Exception {
        return UUID.fromString(json.readTree(get("/api/v1/questions/" + question, ACME).body())
            .get("currentVersion").get("id").asString());
    }

    private UUID question(String name, String body) throws Exception {
        return question(bank("Fire safety", ACME), name, body);
    }

    private UUID question(UUID bank, String name, String body) throws Exception {
        return UUID.fromString(json.readTree(post("/api/v1/banks/" + bank + "/questions", ACME,
            "{\"internalName\":\"" + name + "\",\"body\":" + body + "}").body()).get("id").asString());
    }

    private UUID bank(String name, String token) throws Exception {
        return UUID.fromString(json.readTree(
            post("/api/v1/banks", token, "{\"name\":\"" + name + "\"}").body()).get("id").asString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> put(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token).DELETE());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
