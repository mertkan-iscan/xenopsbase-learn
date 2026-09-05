package com.xenopsoftware.learn.catalog.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Course versions, through the real filter chain (T-5.7).
 *
 * <p>The failure being prevented is the one at the top of the issue: republishing silently changing
 * what "completed" meant for everyone who already finished, so last quarter's compliance report
 * describes a course that no longer exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class CourseVersionTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID LEARNER = UUID.randomUUID();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;

    private UUID course;
    private UUID weekOne;
    private UUID induction;

    @BeforeEach
    void aCourseWithOneStep() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        course = idOf(post("/api/v1/courses", "{\"title\":\"Onboarding\"}"));
        weekOne = idOf(post("/api/v1/courses/" + course + "/modules", "{\"title\":\"Week one\"}"));
        induction = node(weekOne, "the induction video");
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void aPublishedVersionCannotBeChanged() throws Exception {
        UUID versionId = UUID.fromString(publish("first cut").get("id").asString());

        // The application has no code that edits a version, and that is not the same as it being
        // impossible. This is the hand-run UPDATE at midnight.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            jdbc.update("UPDATE course_version SET notes = 'rewritten' WHERE id = ?", versionId)))
            .as("a published version is what somebody was assigned and what a report describes")
            .hasMessageContaining("immutable");
    }

    @Test
    void draftsAreStillEditedFreelyAfterPublishing() throws Exception {
        publish("first cut");

        // Editing the draft must stay ordinary: the version froze, the course did not.
        assertThat(post("/api/v1/courses/" + course + "/modules", "{\"title\":\"Week two\"}")
            .statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/courses/" + course).body()).contains("Week two");
        assertThat(versions()).hasSize(1);
    }

    @Test
    void republishingAnIdenticalCourseIsRefused() throws Exception {
        publish("first cut");

        HttpResponse<String> again = post("/api/v1/courses/" + course + "/versions",
            "{\"notes\":\"no changes\",\"publishedBy\":\"" + AUTHOR + "\"}");

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body())
            .as("a row in every report that tells nobody anything")
            .contains("Nothing has changed");
    }

    @Test
    void aTypoFixIsTextOnlyAndReachesEverybodyWithoutMovingAnybody() throws Exception {
        publish("first cut");
        assign();
        long pinned = pinnedVersion();

        // Only wording changes.
        jdbc.update("UPDATE course_module SET title = 'Week One' WHERE id = ?", weekOne);
        JsonNode second = publish("fixed the capital");

        assertThat(second.get("textOnly").asBoolean())
            .as("decided by comparing snapshots, never by the caller saying so")
            .isTrue();
        assertThat(pinnedVersion())
            .as("nobody was moved: the pin is still where it was")
            .isEqualTo(pinned);
        assertThat(get("/api/v1/courses/" + course + "/versions/diff?from=1&to=2").body())
            .contains("\"textOnly\":true");
    }

    @Test
    void addingAStepIsARealVersionAndLeavesLearnersWhereTheyAre() throws Exception {
        publish("first cut");
        assign();

        node(weekOne, "a new safety module");
        JsonNode second = publish("added safety");

        assertThat(second.get("textOnly").asBoolean())
            .as("a step added is work added, whatever the author called the change")
            .isFalse();
        assertThat(pinnedVersion())
            .as("a learner half-way through finishes the course they started")
            .isEqualTo(1);
    }

    @Test
    void anAuthorWhoBelievesTheyFixedATypoButRemovedAStepIsNotBelieved() throws Exception {
        publish("first cut");
        UUID second = node(weekOne, "a step about to vanish");
        publish("two steps");

        jdbc.update("DELETE FROM course_node WHERE id = ?", second);
        JsonNode third = publish("just a typo, honest");

        assertThat(third.get("textOnly").asBoolean())
            .as("the server compares snapshots; the notes are what somebody types at 5pm")
            .isFalse();
    }

    @Test
    void migrationIsExplicitAndShowsWhatWillBeLostFirst() throws Exception {
        publish("first cut");
        UUID doomed = node(weekOne, "a step about to vanish");
        publish("two steps");
        assign();
        jdbc.update("DELETE FROM course_node WHERE id = ?", doomed);
        publish("removed a step");

        String cost = get("/api/v1/courses/" + course + "/versions/migration-cost?from=2&to=3").body();
        assertThat(cost)
            .as("an administrator sees the consequence before choosing it")
            .contains("no longer count");
        assertThat(pinnedVersion())
            .as("and looking at the cost did not move anybody")
            .isEqualTo(2);

        assertThat(post("/api/v1/courses/" + course + "/versions/migrate",
            "{\"from\":2,\"to\":3}").body()).contains("\"assignmentsMoved\":1");
        assertThat(pinnedVersion()).isEqualTo(3);
    }

    @Test
    void aStepAddedAndRemovedBetweenTwoVersionsCostsNobodyAnything() throws Exception {
        // Found by getting an earlier version of the test above wrong, and worth keeping: the
        // cost of a migration is what the two ENDPOINTS differ by, not the sum of what happened
        // in between. Somebody on version 1 never saw the step that appeared in 2 and was gone
        // by 3, so moving them straight to 3 takes nothing away.
        publish("first cut");
        assign();
        UUID fleeting = node(weekOne, "a step that comes and goes");
        publish("two steps");
        jdbc.update("DELETE FROM course_node WHERE id = ?", fleeting);
        publish("removed it again");

        assertThat(get("/api/v1/courses/" + course + "/versions/migration-cost?from=1&to=3").body())
            .isEqualTo("[]");
    }

    @Test
    void migrationNeverMovesLearnersBackwards() throws Exception {
        publish("first cut");
        node(weekOne, "another step");
        publish("two steps");
        assign();

        HttpResponse<String> backwards = post("/api/v1/courses/" + course + "/versions/migrate",
            "{\"from\":2,\"to\":1}");

        assertThat(backwards.statusCode()).isEqualTo(400);
        assertThat(backwards.body())
            .as("finished work would become unfinished, and no report could explain it")
            .contains("forward");
    }

    @Test
    void migrationDoesNotRewriteWhatSomebodyAlreadyFinished() throws Exception {
        publish("first cut");
        assign();
        UUID versionOne = UUID.fromString(versions().get(0).get("id").asString());
        jdbc.update("""
            INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, recorded_at,
                                         course_version_id)
            VALUES (?, 'acme', ?, ?, 'COMPLETED', now(), ?)
            """, UUID.randomUUID(), LEARNER, induction, versionOne);
        node(weekOne, "another step");
        publish("two steps");

        post("/api/v1/courses/" + course + "/versions/migrate", "{\"from\":1,\"to\":2}");

        assertThat(jdbc.queryForObject("""
            SELECT course_version_id FROM node_completion WHERE learner_id = ?
            """, UUID.class, LEARNER))
            .as("what was finished under version 1 was finished under version 1 -- migration "
                + "changes what they must do NEXT, not what they already did")
            .isEqualTo(versionOne);
    }

    @Test
    void aCourseThatWasNeverPublishedCannotBeAssigned() throws Exception {
        HttpResponse<String> refused = post("/api/v1/assignments",
            "{\"targetType\":\"USER\",\"targetId\":\"" + LEARNER + "\",\"referenceType\":\"COURSE\""
            + ",\"referenceId\":\"" + course + "\",\"assignedBy\":\"" + AUTHOR + "\"}");

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body())
            .as("assigning a draft means a learner working through something that changes "
                + "underneath them")
            .contains("never been published");
    }

    @Test
    void theDiffAnswersWhatChanged() throws Exception {
        publish("first cut");
        UUID second = node(weekOne, "a second step");
        publish("added one");
        jdbc.update("UPDATE course_node SET required = false WHERE id = ?", second);
        publish("made it optional");

        assertThat(get("/api/v1/courses/" + course + "/versions/diff?from=1&to=2").body())
            .contains("\"addedNodes\":[\"step " + second.toString().substring(0, 8) + "\"]");
        assertThat(get("/api/v1/courses/" + course + "/versions/diff?from=2&to=3").body())
            .contains("now optional");
        assertThat(get("/api/v1/courses/" + course + "/versions/diff?from=1&to=3").body())
            .as("a diff across two hops is still one answer")
            .contains("\"addedNodes\":[");
    }

    private JsonNode publish(String notes) throws Exception {
        HttpResponse<String> response = post("/api/v1/courses/" + course + "/versions",
            "{\"notes\":\"" + notes + "\",\"publishedBy\":\"" + AUTHOR + "\"}");
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode versions() throws Exception {
        return json.readTree(get("/api/v1/courses/" + course + "/versions").body());
    }

    private void assign() throws Exception {
        assertThat(post("/api/v1/assignments",
            "{\"targetType\":\"USER\",\"targetId\":\"" + LEARNER + "\",\"referenceType\":\"COURSE\""
            + ",\"referenceId\":\"" + course + "\",\"assignedBy\":\"" + AUTHOR + "\"}")
            .statusCode()).isEqualTo(200);
    }

    private long pinnedVersion() {
        return jdbc.queryForObject("SELECT pinned_version FROM assignment", Long.class);
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
