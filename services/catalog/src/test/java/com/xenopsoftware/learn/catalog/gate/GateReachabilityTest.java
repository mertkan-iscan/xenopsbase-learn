package com.xenopsoftware.learn.catalog.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gates through the real filter chain (T-5.3).
 *
 * <p>Completions are inserted in SQL, because nothing writes {@code node_completion} yet and
 * deliberately so: T-3.7 and T-9.8 fill it by event, and there is no endpoint through which a
 * client could declare itself complete — that API is the hole ADR-0107 exists to close. Inserting
 * rows directly is what a delivered event will do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class GateReachabilityTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final UUID LEARNER = UUID.randomUUID();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;

    private UUID course;
    private UUID weekOne;
    private UUID weekTwo;
    private UUID induction;
    private UUID safetyTest;
    private UUID advanced;

    @BeforeEach
    void aTwoModuleCourse() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        course = idOf(post("/api/v1/courses", "{\"title\":\"Onboarding\"}"));
        weekOne = idOf(post("/api/v1/courses/" + course + "/modules", "{\"title\":\"Week one\"}"));
        weekTwo = idOf(post("/api/v1/courses/" + course + "/modules",
            "{\"title\":\"Week two\",\"afterModuleId\":\"" + weekOne + "\"}"));
        induction = node(weekOne, "the induction video");
        safetyTest = node(weekOne, "the safety test");
        advanced = node(weekTwo, "the advanced module");
    }

    @org.junit.jupiter.api.AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void withNoGatesEverythingIsAvailable() throws Exception {
        String answers = reachability();

        assertThat(answers).contains("\"reachable\":true").doesNotContain("\"reachable\":false");
        assertThat(answers).contains("Available.");
    }

    @Test
    void aLockedNodeSaysWhatToDoAboutIt() throws Exception {
        gate(advanced, "ALL", requirement("MODULE", weekOne, "COMPLETED"));

        assertThat(explanationFor(advanced))
            .isEqualTo("To unlock this, complete Week one.");
    }

    @Test
    void finishingTheRequirementUnlocksIt() throws Exception {
        gate(advanced, "ALL", requirement("NODE", safetyTest, "PASSED"));
        assertThat(reachableOf(advanced)).isFalse();

        complete(safetyTest, "PASSED");

        assertThat(reachableOf(advanced)).isTrue();
        assertThat(explanationFor(advanced)).isEqualTo("Available.");
    }

    @Test
    void aModuleCompletesWhenItsRequiredNodesDoAndOptionalOnesNeverBlockIt() throws Exception {
        // The safety test is optional; the induction video is not.
        assertThat(send(request("/api/v1/courses/nodes/" + safetyTest + "/required")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"required\":false}"))).statusCode())
            .isEqualTo(200);
        gate(advanced, "ALL", requirement("MODULE", weekOne, "COMPLETED"));

        complete(induction, "COMPLETED");

        assertThat(reachableOf(advanced))
            .as("an optional node must never be what a gate is waiting for (T-5.2)")
            .isTrue();
    }

    @Test
    void aGateAddedLaterCannotLockSomethingAlreadyCompleted() throws Exception {
        // The fifth criterion, and the one that strands people if it is wrong: a learner finished
        // the advanced module, and only afterwards did an author decide it needed a prerequisite.
        complete(advanced, "COMPLETED");

        gate(advanced, "ALL", requirement("NODE", safetyTest, "PASSED"));

        assertThat(reachableOf(advanced)).isTrue();
        assertThat(explanationFor(advanced)).isEqualTo("You have already completed this.");
    }

    @Test
    void aNodeInsideALockedModuleIsToldTheModulesReason() throws Exception {
        gate(weekTwo, "ALL", requirement("MODULE", weekOne, "COMPLETED"));

        // The learner cannot act on the node's own rule until the module opens, so telling them
        // about the module is the useful answer.
        assertThat(explanationFor(advanced)).isEqualTo("Week two is not available yet.");
        assertThat(explanationFor(weekTwo)).isEqualTo("To unlock this, complete Week one.");
    }

    @Test
    void aCycleCannotBeSaved() throws Exception {
        gate(advanced, "ALL", requirement("NODE", induction, "COMPLETED"));

        HttpResponse<String> refused = gateResponse(induction, "ALL",
            requirement("NODE", advanced, "COMPLETED"));

        assertThat(refused.statusCode())
            .as("refused at write time, not discovered as a course where nothing unlocks")
            .isEqualTo(409);
        assertThat(refused.body()).contains("loop");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gate", Long.class)).isEqualTo(1);
    }

    @Test
    void aLongerCycleIsAlsoRefused() throws Exception {
        gate(safetyTest, "ALL", requirement("NODE", induction, "COMPLETED"));
        gate(advanced, "ALL", requirement("NODE", safetyTest, "COMPLETED"));

        // induction -> advanced -> safetyTest -> induction. Each edge is innocent on its own.
        assertThat(gateResponse(induction, "ALL", requirement("NODE", advanced, "COMPLETED"))
            .statusCode()).isEqualTo(409);
    }

    @Test
    void aStepCannotRequireTheModuleItIsIn() throws Exception {
        // Containment wearing a gate's clothes: the module is not complete until this node is, so
        // nothing would ever unlock. It would evaluate cleanly and lock forever.
        HttpResponse<String> refused = gateResponse(induction, "ALL",
            requirement("MODULE", weekOne, "COMPLETED"));

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("the module it is in");
    }

    @Test
    void nothingCanRequireItselfOrSomethingInAnotherCourse() throws Exception {
        assertThat(gateResponse(advanced, "ALL", requirement("NODE", advanced, "COMPLETED"))
            .statusCode()).isEqualTo(409);

        UUID elsewhere = idOf(post("/api/v1/courses", "{\"title\":\"Another course\"}"));
        UUID otherModule = idOf(post("/api/v1/courses/" + elsewhere + "/modules",
            "{\"title\":\"M\"}"));
        assertThat(gateResponse(advanced, "ALL", requirement("MODULE", otherModule, "COMPLETED"))
            .statusCode())
            .as("a gate reaching into another course would make reachability unanswerable "
                + "without loading every course the learner might be on")
            .isEqualTo(409);
    }

    @Test
    void editingAGateReEvaluatesImmediatelyForALearnerInProgress() throws Exception {
        gate(advanced, "ALL", requirement("NODE", safetyTest, "PASSED"));
        assertThat(reachableOf(advanced)).isFalse();

        // The author changes their mind: the induction video is enough.
        complete(induction, "COMPLETED");
        gate(advanced, "ALL", requirement("NODE", induction, "COMPLETED"));

        assertThat(reachableOf(advanced))
            .as("the next read reflects the new rule; nothing is cached against the old one")
            .isTrue();
    }

    @Test
    void reachabilityCostsTheSameOnADeepCourseAsOnAShallowOne() throws Exception {
        // T-5.3's last criterion. A per-node evaluation is indistinguishable from correct on the
        // three-node course above and is what makes a learner's first screen slower every time
        // somebody adds a module.
        UUID deep = idOf(post("/api/v1/courses", "{\"title\":\"A big course\"}"));
        UUID item = idOf(post("/api/v1/content-items",
            "{\"type\":\"video\",\"title\":\"Shared\",\"payload\":{\"assetId\":\"a\"}}"));
        send(request("/api/v1/content-items/" + item + "/state")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"PUBLISHED\"}")));
        UUID previousModule = null;
        for (int m = 0; m < 20; m++) {
            String body = "{\"title\":\"Module " + m + "\""
                + (previousModule == null ? "" : ",\"afterModuleId\":\"" + previousModule + "\"") + "}";
            previousModule = idOf(post("/api/v1/courses/" + deep + "/modules", body));
            UUID previousNode = null;
            for (int n = 0; n < 10; n++) {
                String nodeBody = "{\"contentItemId\":\"" + item + "\""
                    + (previousNode == null ? "" : ",\"afterNodeId\":\"" + previousNode + "\"") + "}";
                previousNode = idOf(post("/api/v1/courses/modules/" + previousModule + "/nodes",
                    nodeBody));
            }
        }

        statistics().clear();
        assertThat(get("/api/v1/courses/" + deep + "/reachability?learnerId=" + LEARNER)
            .statusCode()).isEqualTo(200);

        assertThat(statistics().getPrepareStatementCount())
            .as("200 nodes evaluated without a query per node -- a per-node read would be 200+")
            .isLessThanOrEqualTo(12);
    }

    /**
     * Hibernate's own JDBC statement counter, for the reason DeepCourseTest gives:
     * pg_stat_statements needs shared_preload_libraries and is not in postgres:17-alpine, so the
     * query would fail rather than measure.
     */
    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    private String reachability() throws Exception {
        return get("/api/v1/courses/" + course + "/reachability?learnerId=" + LEARNER).body();
    }

    private boolean reachableOf(UUID id) throws Exception {
        return entryFor(id).get("reachable").asBoolean();
    }

    private String explanationFor(UUID id) throws Exception {
        return entryFor(id).get("explanation").asString();
    }

    /**
     * The one entry in the reachability array describing this id.
     *
     * <p>Parsed rather than sliced out of the string. An earlier version walked braces to find the
     * object, which worked until an entry carried a nested `unmet` array -- a test helper whose
     * correctness depends on how many objects the payload happens to contain is a test that fails
     * for reasons unrelated to what it checks.
     */
    private JsonNode entryFor(UUID id) throws Exception {
        String body = reachability();
        for (JsonNode entry : json.readTree(body)) {
            if (id.toString().equals(entry.get("id").asString())) {
                return entry;
            }
        }
        throw new AssertionError("No reachability entry for " + id + " in " + body);
    }

    private void complete(UUID nodeId, String state) {
        jdbc.update("""
            INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, recorded_at)
            VALUES (?, 'acme', ?, ?, ?, now())
            """, UUID.randomUUID(), LEARNER, nodeId, state);
    }

    private String requirement(String part, UUID id, String state) {
        return "{\"part\":\"" + part + "\",\"id\":\"" + id + "\",\"state\":\"" + state + "\"}";
    }

    private void gate(UUID target, String combinator, String... requirements) throws Exception {
        assertThat(gateResponse(target, combinator, requirements).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> gateResponse(UUID target, String combinator,
            String... requirements) throws Exception {
        String part = target.equals(weekOne) || target.equals(weekTwo) ? "MODULE" : "NODE";
        // A module id from another course is still a MODULE; the one case the ternary above gets
        // wrong is handled by the caller passing its own part where it matters.
        String body = "{\"combinator\":\"" + combinator + "\",\"requirements\":["
            + String.join(",", requirements) + "]}";
        return send(request("/api/v1/courses/" + course + "/gates/" + part + "/" + target)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private UUID node(UUID module, String title) throws Exception {
        UUID item = idOf(post("/api/v1/content-items",
            "{\"type\":\"video\",\"title\":\"" + title + "\",\"payload\":{\"assetId\":\"a\"}}"));
        send(request("/api/v1/content-items/" + item + "/state")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"PUBLISHED\"}")));
        return idOf(post("/api/v1/courses/modules/" + module + "/nodes",
            "{\"contentItemId\":\"" + item + "\"}"));
    }

    private static UUID idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return UUID.fromString(response.body().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(
            URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
            .header("Authorization", "Bearer " + ACME);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
