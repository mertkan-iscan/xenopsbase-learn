package com.xenopsoftware.learn.catalog.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a learner sees on opening the platform (T-5.8).
 *
 * <p>Over HTTP, because the shape of the answer <em>is</em> the deliverable: a screen is built from
 * it, and the states it distinguishes are the difference between a first-run page that welcomes
 * somebody and a blank one that looks broken.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubTokens.class, HomeTest.StubIdentity.class})
class HomeTest extends PostgresTestHarness {

    private static final String CALLER = "acme-learner~acme~TENANT";
    private static final String ADMIN = "acme-author~acme~TENANT";
    private static final UUID LEARNER =
        UUID.fromString("00000000-0000-4000-8000-000000000001");

    /**
     * A Valkey of this class's own, rather than whatever is on localhost.
     *
     * <p>The cache is keyed on a version counter, and this class truncates the table those
     * counters live in between tests — so two tests would otherwise write different screens under
     * the same key and the second would read the first's. In production nothing truncates that
     * table and the versions only ever climb; in a test they must not be shared at all.
     */
    private static final org.testcontainers.containers.GenericContainer<?> VALKEY =
        new org.testcontainers.containers.GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void valkey(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIdentity {

        @Bean
        @Primary
        StubLearnerIdentity stubLearnerIdentity() {
            return new StubLearnerIdentity(LEARNER);
        }
    }

    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;
    @Autowired
    private StubLearnerIdentity identity;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate cache;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private UUID course;
    private UUID moduleOne;
    private UUID moduleTwo;
    private UUID first;
    private UUID second;
    private UUID third;

    @BeforeEach
    void aCourseWithTwoModules() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        jdbc.update("DELETE FROM node_progress");
        jdbc.update("DELETE FROM home_version");
        // The versions restart at zero when that table is emptied, so a cached screen from the
        // previous test would be addressed by the same key. Nothing truncates it in production.
        cache.getConnectionFactory().getConnection().serverCommands().flushAll();
        identity.resolvesTo(LEARNER);

        course = idOf(post("/api/v1/courses", ADMIN, "{\"title\":\"Fire safety\"}"));
        // `after` matters: a null one means "make it first" (T-5.2), so a fixture that leaves it
        // out builds the course backwards and every assertion about order reads as a bug.
        moduleOne = idOf(post("/api/v1/courses/" + course + "/modules", ADMIN,
            "{\"title\":\"Week one\"}"));
        moduleTwo = idOf(post("/api/v1/courses/" + course + "/modules", ADMIN,
            "{\"title\":\"Week two\",\"afterModuleId\":\"" + moduleOne + "\"}"));
        first = addNode(moduleOne, publishedItem("The drill"), null);
        second = addNode(moduleOne, publishedItem("The extinguisher"), first);
        third = addNode(moduleTwo, publishedItem("The assembly point"), null);
        // Assigning a draft is refused (T-5.7): a learner must not be working through something
        // that changes underneath them.
        publish(course);
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        jdbc.update("DELETE FROM node_progress");
        jdbc.update("DELETE FROM home_version");
        emptyEveryTable(dataSource);
    }

    // ---------------------------------------------------------------- the states

    @Test
    void somebodyWithNothingAssignedIsToldThatRatherThanShownABlankPage() throws Exception {
        JsonNode home = home();

        assertThat(home.get("state").asString())
            .as("a first-run state the server decided, not an empty list a client has to guess "
                + "the meaning of")
            .isEqualTo("NOTHING_ASSIGNED");
        assertThat(home.get("courses")).isEmpty();
        assertThat(home.get("nextUp").isNull()).isTrue();
        assertThat(home.get("summary").get("assigned").asInt()).isZero();
    }

    @Test
    void anAssignedCourseArrivesWithItsStructureItsDeadlineAndWhatToDoNext() throws Exception {
        LocalDate due = LocalDate.now().plusDays(3);
        assign(due);

        JsonNode home = home();

        assertThat(home.get("state").asString()).isEqualTo("READY");
        assertThat(home.get("courses")).hasSize(1);
        JsonNode course = home.get("courses").get(0);
        assertThat(course.get("title").asString()).isEqualTo("Fire safety");
        assertThat(course.get("dueOn").asString())
            .as("the deadline is T-5.6's, computed in the learner's own timezone, not a second "
                + "idea of one")
            .isEqualTo(due.toString());
        assertThat(course.get("overdue").asBoolean()).isFalse();
        assertThat(course.get("cycleNumber").asInt()).isEqualTo(1);
        assertThat(course.get("modules")).hasSize(2);
        assertThat(moduleIn(home, moduleOne).get("nodes"))
            .as("the module's own nodes, in the order the course puts them")
            .hasSize(2);
        assertThat(home.get("nextUp").get("nodeId").asString())
            .as("nothing started yet, so the first available step")
            .isEqualTo(first.toString());
        assertThat(home.get("summary").get("dueSoon").asInt()).isEqualTo(1);
    }

    @Test
    void everythingFinishedIsItsOwnStateAndNotAnEmptyOne() throws Exception {
        assign(null);
        completed(first);
        completed(second);
        completed(third);

        JsonNode home = home();

        assertThat(home.get("state").asString())
            .as("\"you have finished everything\" and \"nothing has been assigned to you\" are "
                + "different sentences, and a screen that shows one list for both says neither")
            .isEqualTo("ALL_DONE");
        assertThat(home.get("courses").get(0).get("completed").asBoolean()).isTrue();
        assertThat(home.get("courses").get(0).get("percentComplete").asInt()).isEqualTo(100);
        assertThat(home.get("nextUp").isNull()).isTrue();
        assertThat(home.get("summary").get("completed").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- what it draws

    @Test
    void aLockedItemSaysWhyInTheSentenceTheGateGaveIt() throws Exception {
        assign(null);
        // Week two is not available until Week one is complete (T-5.3).
        gate(moduleTwo, moduleOne);

        JsonNode home = home();
        JsonNode weekTwo = moduleIn(home, moduleTwo);

        assertThat(weekTwo.get("locked").asBoolean()).isTrue();
        assertThat(weekTwo.get("lockedReason").asString())
            .as("the gate's own explanation, carried verbatim -- two clients inventing two "
                + "sentences for one rule is how a learner is told different things by the same "
                + "platform")
            .contains("Week one");
        assertThat(nodeIn(home, third).get("state").asString()).isEqualTo("LOCKED");
        assertThat(nodeIn(home, third).get("lockedReason").asString()).isNotBlank();
        assertThat(home.get("nextUp").get("nodeId").asString())
            .as("and \"next\" is something they can actually open")
            .isEqualTo(first.toString());
    }

    @Test
    void whereToResumeComesFromTheProgressStreamingDerived() throws Exception {
        assign(null);
        // What streaming's throttled progress event leaves behind (T-3.7, T-5.8). Not a second
        // calculation over completions: this is the number reporting consumes.
        progress(first, 40, 372, false);

        JsonNode home = home();
        JsonNode node = nodeIn(home, first);

        assertThat(node.get("state").asString()).isEqualTo("IN_PROGRESS");
        assertThat(node.get("percent").asInt()).isEqualTo(40);
        assertThat(node.get("resumeSecond").asInt()).isEqualTo(372);
        assertThat(home.get("nextUp").get("nodeId").asString())
            .as("continue means continue: the step already started beats the next unstarted one")
            .isEqualTo(first.toString());
        assertThat(home.get("nextUp").get("resumeSecond").asInt()).isEqualTo(372);
        assertThat(home.get("summary").get("inProgress").asInt()).isEqualTo(1);
    }

    @Test
    void anOverdueCourseIsFirstInTheQueueForWhatToDoNext() throws Exception {
        assign(LocalDate.now().plusDays(30));
        UUID urgent = idOf(post("/api/v1/courses", ADMIN, "{\"title\":\"Manual handling\"}"));
        UUID urgentModule = idOf(post("/api/v1/courses/" + urgent + "/modules", ADMIN,
            "{\"title\":\"Only module\"}"));
        UUID urgentNode = addNode(urgentModule, publishedItem("Lifting"), null);
        publish(urgent);
        assignCourse(urgent, LocalDate.now().minusDays(2));

        JsonNode home = home();

        assertThat(home.get("nextUp").get("nodeId").asString())
            .as("what is overdue comes before what is merely due, because that is the order the "
                + "learner is being asked about")
            .isEqualTo(urgentNode.toString());
        assertThat(home.get("summary").get("overdue").asInt()).isEqualTo(1);
    }

    @Test
    void anAssignmentThatIsNotACourseIsStillOnTheScreen() throws Exception {
        UUID item = publishedItem("The policy document");
        post("/api/v1/assignments", ADMIN, "{\"targetType\":\"USER\",\"targetId\":\"" + LEARNER
            + "\",\"referenceType\":\"CONTENT_ITEM\",\"referenceId\":\"" + item
            + "\",\"assignedBy\":\"" + UUID.randomUUID() + "\"}");

        JsonNode home = home();

        assertThat(home.get("courses")).isEmpty();
        assertThat(home.get("items")).hasSize(1);
        assertThat(home.get("items").get(0).get("title").asString())
            .isEqualTo("The policy document");
        assertThat(home.get("items").get(0).get("referenceType").asString())
            .isEqualTo("CONTENT_ITEM");
        assertThat(home.get("state").asString()).isEqualTo("READY");
    }

    // ---------------------------------------------------------------- the cache

    @Test
    void theScreenIsCachedAndAnythingThatChangesItMakesTheCachedCopyUnreachable() throws Exception {
        assign(null);
        assertThat(home().get("courses").get(0).get("percentComplete").asInt()).isZero();

        // A completion arrives by event and bumps this learner's version in the same transaction.
        completed(first);
        bumpFor(LEARNER);

        assertThat(home().get("courses").get(0).get("percentComplete").asInt())
            .as("the cached copy is not deleted -- it stops being addressed, which is the only "
                + "invalidation that cannot be half-done")
            .isEqualTo(33);
    }

    @Test
    void anAuthorEditingTheCourseIsEnoughToInvalidateEverybodysScreen() throws Exception {
        assign(null);
        assertThat(home().get("courses").get(0).get("modules")).hasSize(2);

        // No event, no handler, no line in a service: an author added a module through the API and
        // the filter bumped the company's epoch on the way out (T-5.8).
        idOf(post("/api/v1/courses/" + course + "/modules", ADMIN,
            "{\"title\":\"Week three\",\"afterModuleId\":\"" + moduleTwo + "\"}"));

        assertThat(home().get("courses").get(0).get("modules"))
            .as("closed by default: a screen cached a second ago is unreachable because the "
                + "epoch moved, and nobody had to remember to invalidate it")
            .hasSize(3);
    }

    @Test
    void aScreenThatCannotNameItsLearnerIsRefusedRatherThanGuessed() throws Exception {
        assign(null);
        identity.unreachable();

        HttpResponse<String> refused = get("/api/v1/me/home", CALLER);

        assertThat(refused.statusCode())
            .as("503 and not 500: the request is fine, one dependency is not, and trying again "
                + "later is the right thing for a client to do")
            .isEqualTo(503);
    }

    // ---------------------------------------------------------------- plumbing

    /** One module wherever it sits: the test asserts about a module, not about a position. */
    private JsonNode moduleIn(JsonNode home, UUID moduleId) {
        for (JsonNode course : home.get("courses")) {
            for (JsonNode module : course.get("modules")) {
                if (moduleId.toString().equals(module.get("moduleId").asString())) {
                    return module;
                }
            }
        }
        throw new AssertionError("no module " + moduleId + " in " + home);
    }

    private JsonNode nodeIn(JsonNode home, UUID nodeId) {
        for (JsonNode course : home.get("courses")) {
            for (JsonNode module : course.get("modules")) {
                for (JsonNode node : module.get("nodes")) {
                    if (nodeId.toString().equals(node.get("nodeId").asString())) {
                        return node;
                    }
                }
            }
        }
        throw new AssertionError("no node " + nodeId + " in " + home);
    }

    private JsonNode home() throws Exception {
        HttpResponse<String> response = get("/api/v1/me/home", CALLER);
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void assign(LocalDate due) throws Exception {
        assignCourse(course, due);
    }

    private void assignCourse(UUID courseId, LocalDate due) throws Exception {
        String deadline = due == null ? ""
            : ",\"due\":{\"kind\":\"ABSOLUTE\",\"on\":\"" + due + "\"}";
        assertThat(post("/api/v1/assignments", ADMIN,
            "{\"targetType\":\"USER\",\"targetId\":\"" + LEARNER + "\",\"referenceType\":\"COURSE\""
            + ",\"referenceId\":\"" + courseId + "\",\"assignedBy\":\"" + UUID.randomUUID() + "\""
            + deadline + "}").statusCode()).isEqualTo(200);
    }

    private void gate(UUID target, UUID requires) throws Exception {
        HttpResponse<String> saved = send(request(
            "/api/v1/courses/" + course + "/gates/MODULE/" + target, ADMIN)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(
                "{\"combinator\":\"ALL\",\"requirements\":[{\"part\":\"MODULE\",\"id\":\""
                + requires + "\",\"state\":\"COMPLETED\"}]}")));
        assertThat(saved.statusCode()).as("%s", saved.body()).isEqualTo(200);
    }

    /** A completion, written where T-3.7's event puts it. */
    private void completed(UUID nodeId) {
        jdbc.update("""
            INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, recorded_at)
            VALUES (?, 'acme', ?, ?, 'COMPLETED', now())
            """, UUID.randomUUID(), LEARNER, nodeId);
    }

    /** How far through, written where streaming's throttled progress event puts it. */
    private void progress(UUID nodeId, int percent, int resumeSecond, boolean completed) {
        jdbc.update("""
            INSERT INTO node_progress (tenant_id, learner_id, node_id, percent, resume_second,
                                       completed, updated_at)
            VALUES ('acme', ?, ?, ?, ?, ?, ?)
            """, LEARNER, nodeId, percent, resumeSecond, completed,
            java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
    }

    /** What a handler does in the transaction that applied an event. */
    private void bumpFor(UUID learnerId) {
        jdbc.update("""
            INSERT INTO home_version (tenant_id, learner_id, version, updated_at)
            VALUES ('acme', ?, 1, now())
            ON CONFLICT (tenant_id, learner_id) WHERE learner_id IS NOT NULL DO UPDATE
               SET version = home_version.version + 1, updated_at = now()
            """, learnerId);
    }

    private void publish(UUID courseId) throws Exception {
        assertThat(post("/api/v1/courses/" + courseId + "/versions", ADMIN,
            "{\"notes\":\"ready\",\"publishedBy\":\"" + UUID.randomUUID() + "\"}")
            .statusCode()).isEqualTo(200);
    }

    private UUID addNode(UUID moduleId, UUID item, UUID afterNodeId) throws Exception {
        String after = afterNodeId == null ? "" : ",\"afterNodeId\":\"" + afterNodeId + "\"";
        return idOf(post("/api/v1/courses/modules/" + moduleId + "/nodes", ADMIN,
            "{\"contentItemId\":\"" + item + "\"" + after + "}"));
    }

    private UUID publishedItem(String title) throws Exception {
        UUID id = idOf(post("/api/v1/content-items", ADMIN,
            "{\"type\":\"video\",\"title\":\"" + title + "\",\"payload\":{\"assetId\":\"a\"}}"));
        send(request("/api/v1/content-items/" + id + "/state", ADMIN)
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
