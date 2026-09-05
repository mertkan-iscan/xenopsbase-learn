package com.xenopsoftware.learn.catalog.assign;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Assignment through the real filter chain (T-5.5).
 *
 * <p>Group reach is inserted in SQL, because nothing writes {@code learner_group_reach} yet and
 * deliberately so: identity owns the tree and publishes who is reached (T-1.3, T-9.8). Inserting
 * rows directly is what a delivered event will do — and it is the same seam
 * {@code node_completion} uses for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class AssignmentTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final String GLOBEX = "globex-author~globex~TENANT";
    private static final UUID ADMIN = UUID.randomUUID();
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
    private UUID module;
    private UUID node;
    private UUID bareItem;

    @BeforeEach
    void aCourseAndALooseVideo() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        module = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"Week one\"}"));
        node = addNode(module, publishedItem("the induction video"));
        bareItem = publishedItem("a loose video");
        // T-5.7: a course must be published before anything in it can be assigned. Assigning a
        // draft would mean a learner working through something that changes underneath them.
        publishCourse(course);
    }

    /** Freezes the current draft as a version, which is what an assignment pins (T-5.7). */
    private void publishCourse(UUID courseId) throws Exception {
        assertThat(post("/api/v1/courses/" + courseId + "/versions", ACME,
            "{\"notes\":\"ready\",\"publishedBy\":\"" + ADMIN + "\"}").statusCode())
            .isEqualTo(200);
    }

    @org.junit.jupiter.api.AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void oneModelCoversEveryTargetAndEveryReference() throws Exception {
        UUID group = UUID.randomUUID();

        // Four reference kinds x three target kinds, through one endpoint with two fields
        // different each time. If these were four features, three of them would be wrong.
        assertThat(assign("USER", LEARNER, "COURSE", course).statusCode()).isEqualTo(200);
        assertThat(assign("GROUP", group, "MODULE", module).statusCode()).isEqualTo(200);
        assertThat(assign("TENANT", null, "NODE", node).statusCode()).isEqualTo(200);
        assertThat(assign("USER", LEARNER, "CONTENT_ITEM", bareItem).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Long.class)).isEqualTo(4);
    }

    @Test
    void aGroupAssignmentIsOneRowHoweverManyPeopleAreInTheGroup() throws Exception {
        UUID group = UUID.randomUUID();
        // Five thousand people reached by that group.
        List<Object[]> members = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            members.add(new Object[] {UUID.randomUUID(), group});
        }
        jdbc.batchUpdate("""
            INSERT INTO learner_group_reach (tenant_id, learner_id, group_id) VALUES ('acme', ?, ?)
            """, members);

        Instant started = Instant.now();
        assertThat(assign("GROUP", group, "COURSE", course).statusCode()).isEqualTo(200);
        Duration took = Duration.between(started, Instant.now());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Long.class))
            .as("one row, not five thousand -- the audience is resolved when somebody reads")
            .isEqualTo(1);
        assertThat(took)
            .as("which is why bulk assignment cannot time out: there is no bulk to do")
            .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void readingWhatALearnerOwesCostsTheSameOnALargeGroupAsOnASmallOne() throws Exception {
        UUID group = UUID.randomUUID();
        reach(LEARNER, group);
        for (int i = 0; i < 5000; i++) {
            reach(UUID.randomUUID(), group);
        }
        assign("GROUP", group, "COURSE", course);

        statistics().clear();
        assertThat(get("/api/v1/assignments/of/" + LEARNER, ACME).statusCode()).isEqualTo(200);

        // T-5.5's second criterion, measured: which groups reach them, then everything live for
        // those targets. Nothing per-member and nothing per-group.
        assertThat(statistics().getPrepareStatementCount())
            .as("the cost of reading is the size of what was ASSIGNED, never of the audience")
            .isLessThanOrEqualTo(4);
    }

    @Test
    void aPersonReachedTwiceHasOneObligationAndCanSeeWhy() throws Exception {
        UUID engineering = UUID.randomUUID();
        UUID platform = UUID.randomUUID();
        reach(LEARNER, engineering);
        reach(LEARNER, platform);
        assign("GROUP", engineering, "COURSE", course);
        assign("GROUP", platform, "COURSE", course);

        JsonNode obligations = obligations(LEARNER);

        assertThat(obligations).hasSize(1);
        assertThat(obligations.get(0).get("sources"))
            .as("one obligation, both reasons -- so \"why do I have this\" is answerable")
            .hasSize(2);
    }

    @Test
    void beingReachedByAGroupAndByNameIsStillOneObligation() throws Exception {
        UUID group = UUID.randomUUID();
        reach(LEARNER, group);
        assign("GROUP", group, "COURSE", course);
        assign("USER", LEARNER, "COURSE", course);

        assertThat(obligations(LEARNER)).hasSize(1);
    }

    @Test
    void theEarliestAssignmentKeepsTheDateAndThePin() throws Exception {
        UUID group = UUID.randomUUID();
        reach(LEARNER, group);
        assign("USER", LEARNER, "COURSE", course);
        long pinnedFirst = jdbc.queryForObject(
            "SELECT pinned_version FROM assignment", Long.class);

        // The course changes, then somebody assigns it again through a group.
        post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"Week two\"}");
        assign("GROUP", group, "COURSE", course);

        JsonNode obligation = obligations(LEARNER).get(0);
        assertThat(obligation.get("pinnedVersion").asLong())
            .as("a second assignment must not quietly re-point an existing obligation at a newer "
                + "structure")
            .isEqualTo(pinnedFirst);
    }

    @Test
    void removingSomeoneFromAGroupRemovesTheObligationAndKeepsTheHistory() throws Exception {
        UUID group = UUID.randomUUID();
        reach(LEARNER, group);
        assign("GROUP", group, "COURSE", course);
        jdbc.update("""
            INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, recorded_at)
            VALUES (?, 'acme', ?, ?, 'COMPLETED', now())
            """, UUID.randomUUID(), LEARNER, node);
        assertThat(obligations(LEARNER)).hasSize(1);

        // They move department. Nothing about the assignment changes -- reach does.
        jdbc.update("DELETE FROM learner_group_reach WHERE learner_id = ?", LEARNER);

        assertThat(obligations(LEARNER))
            .as("the obligation goes with the membership, because it was never materialised")
            .isEmpty();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM node_completion WHERE learner_id = ?
            """, Long.class, LEARNER))
            .as("but what they finished is theirs -- completion is recorded against the node, "
                + "not against the assignment")
            .isEqualTo(1);
    }

    @Test
    void anAssignmentPinsAPublishedVersionAndNoticesANewerOne() throws Exception {
        UUID id = idOf(assign("USER", LEARNER, "COURSE", course));
        assertThat(assignmentById(id).get("drifted").asBoolean()).isFalse();

        // Editing the DRAFT does not drift anything. T-5.7 changed what the pin means: it is a
        // published version now, not the draft's edit counter, so an author working on the next
        // revision no longer flags every assignment in the tenant while they do it.
        post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"Week two\"}");
        assertThat(assignmentById(id).get("drifted").asBoolean())
            .as("a draft in progress is not a course anybody was assigned")
            .isFalse();

        // Publishing it is what makes the pin stale.
        publishCourse(course);

        assertThat(assignmentById(id).get("drifted").asBoolean())
            .as("\"this course is not what you assigned any more\" is a query, not a guess")
            .isTrue();
    }

    @Test
    void aBareContentItemHasNothingToPin() throws Exception {
        UUID id = idOf(assign("USER", LEARNER, "CONTENT_ITEM", bareItem));

        JsonNode view = assignmentById(id);
        assertThat(view.get("pinnedVersion").isNull())
            .as("no structure, so nothing to drift -- the case that keeps the model honest")
            .isTrue();
        assertThat(view.get("drifted").asBoolean()).isFalse();
    }

    @Test
    void anUnpublishedItemCannotBeAssigned() throws Exception {
        UUID draft = idOf(post("/api/v1/content-items", ACME,
            "{\"type\":\"video\",\"title\":\"Unfinished\",\"payload\":{\"assetId\":\"a\"}}"));

        HttpResponse<String> refused = assign("USER", LEARNER, "CONTENT_ITEM", draft);

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("publish it first");
    }

    @Test
    void assigningTheSameThingTwiceIsARefusalRatherThanASecondRow() throws Exception {
        assertThat(assign("USER", LEARNER, "COURSE", course).statusCode()).isEqualTo(200);

        assertThat(assign("USER", LEARNER, "COURSE", course).statusCode())
            .as("a duplicate is not a stronger obligation, it is two rows on one learner's list")
            .isEqualTo(409);
    }

    @Test
    void revokingWithdrawsTheObligationAndLeavesTheRecordThatItExisted() throws Exception {
        UUID id = idOf(assign("USER", LEARNER, "COURSE", course));

        assertThat(send(request("/api/v1/assignments/" + id, ACME).DELETE()).statusCode())
            .isEqualTo(200);

        assertThat(obligations(LEARNER)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Long.class))
            .as("revoked, not deleted: \"withdrawn\" and \"never happened\" are different facts")
            .isEqualTo(1);
        // And it can be assigned again, which the partial unique index is what allows.
        assertThat(assign("USER", LEARNER, "COURSE", course).statusCode()).isEqualTo(200);
    }

    @Test
    void bulkAssignmentIsOneTransaction() throws Exception {
        UUID second = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Second course\"}"));
        UUID secondModule = idOf(post("/api/v1/courses/" + second + "/modules", ACME,
            "{\"title\":\"M\"}"));
        addNode(secondModule, publishedItem("a step"));
        publishCourse(second);
        String body = "{\"assignments\":["
            + assignBody("USER", LEARNER, "COURSE", course) + ","
            + assignBody("USER", LEARNER, "COURSE", second) + ","
            // The third is a duplicate of the first and must take the whole batch with it.
            + assignBody("USER", LEARNER, "COURSE", course) + "]}";

        assertThat(post("/api/v1/assignments/bulk", ACME, body).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Long.class))
            .as("half-applied bulk assignment is the state nobody can reason about")
            .isZero();
    }

    @Test
    void anotherCompanyCannotSeeOrMakeAssignmentsHere() throws Exception {
        UUID id = idOf(assign("USER", LEARNER, "COURSE", course));

        assertThat(get("/api/v1/assignments", GLOBEX).body()).isEqualTo("[]");
        assertThat(send(request("/api/v1/assignments/" + id, GLOBEX).DELETE()).statusCode())
            .isEqualTo(404);
        assertThat(assignAs(GLOBEX, "USER", LEARNER, "COURSE", course).statusCode())
            .as("the course is not theirs, so there is nothing to assign")
            .isEqualTo(404);
    }

    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    private JsonNode obligations(UUID learner) throws Exception {
        return json.readTree(get("/api/v1/assignments/of/" + learner, ACME).body());
    }

    private JsonNode assignmentById(UUID id) throws Exception {
        for (JsonNode entry : json.readTree(get("/api/v1/assignments", ACME).body())) {
            if (id.toString().equals(entry.get("id").asString())) {
                return entry;
            }
        }
        throw new AssertionError("No assignment " + id);
    }

    private void reach(UUID learner, UUID group) {
        jdbc.update("""
            INSERT INTO learner_group_reach (tenant_id, learner_id, group_id) VALUES ('acme', ?, ?)
            """, learner, group);
    }

    private static String assignBody(String targetType, UUID targetId, String referenceType,
            UUID referenceId) {
        return "{\"targetType\":\"" + targetType + "\""
            + (targetId == null ? "" : ",\"targetId\":\"" + targetId + "\"")
            + ",\"referenceType\":\"" + referenceType + "\""
            + ",\"referenceId\":\"" + referenceId + "\""
            + ",\"assignedBy\":\"" + ADMIN + "\"}";
    }

    private HttpResponse<String> assign(String targetType, UUID targetId, String referenceType,
            UUID referenceId) throws Exception {
        return assignAs(ACME, targetType, targetId, referenceType, referenceId);
    }

    private HttpResponse<String> assignAs(String token, String targetType, UUID targetId,
            String referenceType, UUID referenceId) throws Exception {
        return post("/api/v1/assignments", token,
            assignBody(targetType, targetId, referenceType, referenceId));
    }

    private UUID addNode(UUID moduleId, UUID item) throws Exception {
        return idOf(post("/api/v1/courses/modules/" + moduleId + "/nodes", ACME,
            "{\"contentItemId\":\"" + item + "\"}"));
    }

    private UUID publishedItem(String title) throws Exception {
        UUID id = idOf(post("/api/v1/content-items", ACME,
            "{\"type\":\"video\",\"title\":\"" + title + "\",\"payload\":{\"assetId\":\"a\"}}"));
        send(request("/api/v1/content-items/" + id + "/state", ACME)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"PUBLISHED\"}")));
        return id;
    }

    private static UUID idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return UUID.fromString(response.body().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1"));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(request(path, token).GET());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send(request(path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(
            URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
            .header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
