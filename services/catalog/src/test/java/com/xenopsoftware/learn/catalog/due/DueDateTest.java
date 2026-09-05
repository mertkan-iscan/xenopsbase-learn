package com.xenopsoftware.learn.catalog.due;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import com.xenopsoftware.learn.catalog.assign.AssignmentRepository;
import com.xenopsoftware.learn.catalog.assign.AssignmentService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * Deadlines, cycles and what overdue does (T-5.6).
 *
 * <p>The three questions the issue said to answer before writing code have answers, and this is
 * where they are true rather than merely written down: overdue marks and does not block, a late
 * joiner's date depends on which basis somebody chose, and annual training opens a cycle instead
 * of reopening last year's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class DueDateTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID LEARNER = UUID.randomUUID();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private AssignmentService assignments;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private CycleService cycles;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private UUID course;

    @BeforeEach
    void aPublishedCourse() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Fire safety\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME,
            "{\"title\":\"Part one\"}"));
        addNode(module, publishedItem("the drill"));
        publishCourse();
    }

    @org.junit.jupiter.api.AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void anAbsoluteDeadlineShowsOnWhatTheLearnerOwes() throws Exception {
        LocalDate audit = LocalDate.now().plusDays(10);
        assignWithDue(LEARNER, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + audit + "\"}");

        JsonNode obligation = obligations().get(0);

        assertThat(obligation.get("dueOn").asString()).isEqualTo(audit.toString());
        assertThat(obligation.get("overdue").asBoolean()).isFalse();
        assertThat(obligation.get("cycleNumber").asInt())
            .as("every assignment with a deadline is in a cycle, recurring or not -- so reminders "
                + "and history have one thing to hang off")
            .isEqualTo(1);
    }

    @Test
    void overdueIsComputedAndTakesNothingAway() throws Exception {
        LocalDate yesterday = LocalDate.now(ZoneId.of("UTC")).minusDays(1);
        assignWithDue(LEARNER, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + yesterday + "\"}");

        JsonNode obligation = obligations().get(0);

        assertThat(obligation.get("overdue").asBoolean()).isTrue();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
            + "WHERE table_name = 'assignment'", String.class))
            .as("there is no overdue column, and there must not be: a stored flag needs a job to "
                + "flip it, so it would mean \"overdue as of whenever that job last ran\"")
            .doesNotContain("overdue");
        assertThat(jdbc.queryForObject(
            "SELECT revoked_at FROM assignment", java.sql.Timestamp.class))
            .as("being late must not revoke access -- that turns a fixable gap into a permanent "
                + "one, and an auditor finds it rather than us")
            .isNull();
        assertThat(obligations())
            .as("and the obligation is still on their list, which is the whole point")
            .hasSize(1);
    }

    /**
     * The issue's second question, both halves of it: a late joiner counts from their own arrival,
     * and somebody who was already there when the course was assigned counts from the assignment.
     *
     * <p>The assignment is backdated in SQL rather than the arrivals being dated into the future,
     * because "ten days ago somebody assigned onboarding to this department, and two days ago a
     * new hire arrived in it" is the actual shape of the case — and a test that waits ten days is
     * not a test.
     */
    @Test
    void aLateJoinerGetsTheirOwnClockOnlyIfSomebodyChoseThatBasis() throws Exception {
        UUID group = UUID.randomUUID();
        UUID early = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        reach(early, group, Instant.now().minus(365, ChronoUnit.DAYS));
        reach(late, group, Instant.now().minus(2, ChronoUnit.DAYS));

        // Onboarding: thirty days from when the assignment reached each of them.
        UUID assignmentId = assignGroupWithDue(group,
            "{\"kind\":\"RELATIVE\",\"afterDays\":30,\"basis\":\"REACHED\"}");
        assignedDaysAgo(assignmentId, 10);

        LocalDate earlyDue = LocalDate.parse(obligationsOf(early).get(0).get("dueOn").asString());
        LocalDate lateDue = LocalDate.parse(obligationsOf(late).get(0).get("dueOn").asString());

        assertThat(lateDue)
            .as("somebody who arrived two days ago has thirty days from then")
            .isEqualTo(LocalDate.now(ZoneId.of("UTC")).plusDays(28));
        assertThat(earlyDue)
            .as("and somebody already in the group when it was assigned counts from the "
                + "assignment, not from a membership a year old -- otherwise a new course lands "
                + "on the whole department already overdue")
            .isEqualTo(LocalDate.now(ZoneId.of("UTC")).plusDays(20));
    }

    @Test
    void anAbsoluteDeadlineDoesNotMoveForSomebodyWhoJoinsLater() throws Exception {
        UUID group = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        reach(joiner, group, Instant.now());
        LocalDate audit = LocalDate.now().plusDays(3);

        assignGroupWithDue(group, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + audit + "\"}");

        assertThat(obligationsOf(joiner).get(0).get("dueOn").asString())
            .as("the audit does not move because somebody was hired last week")
            .isEqualTo(audit.toString());
    }

    @Test
    void theDeadlineExpiresWhenTheDayEndsWhereTheLearnerIs() throws Exception {
        UUID aucklander = UUID.randomUUID();
        UUID californian = UUID.randomUUID();
        profile(aucklander, "Pacific/Auckland", "kiri@acme.test");
        profile(californian, "America/Los_Angeles", "sam@acme.test");
        // Yesterday in Auckland, still today in Los Angeles.
        LocalDate due = LocalDate.now(ZoneId.of("Pacific/Auckland")).minusDays(1);
        assignWithDue(aucklander, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + due + "\"}");
        assignWithDue(californian, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + due + "\"}");

        Instant justAfterAucklandsMidnight = due.plusDays(1)
            .atStartOfDay(ZoneId.of("Pacific/Auckland")).toInstant().plusSeconds(60);

        assertThat(overdueFor(aucklander, justAfterAucklandsMidnight)).isTrue();
        assertThat(overdueFor(californian, justAfterAucklandsMidnight))
            .as("the same instant and the same date, and one of them is still on time -- which is "
                + "what \"timezone per learner\" has to mean to be worth anything")
            .isFalse();
    }

    @Test
    void annualTrainingOpensANewCycleAndKeepsLastYears() throws Exception {
        LocalDate firstDue = LocalDate.now().plusDays(7);
        UUID assignmentId = assignWithDue(LEARNER, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + firstDue
            + "\",\"recurrenceMonths\":12}");

        // A fortnight after the first deadline: the 2026 cycle is over and the 2027 one is open.
        // NOT a year and a fortnight, which is past the second deadline too -- three cycles would
        // be the right answer to that question and the wrong question to ask here.
        Instant later = firstDue.plusDays(14).atStartOfDay(ZoneId.of("UTC")).toInstant();
        TenantContext.callWithUnchecked("acme", () ->
            cycles.currentCycle(assignmentRepository.findById(assignmentId).orElseThrow(), later));

        JsonNode history = json.readTree(
            get("/api/v1/assignments/" + assignmentId + "/cycles", ACME).body());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("dueOn").asString())
            .as("last year's row is still there and still says what last year was -- which is "
                + "the only reason \"did they do it in 2025\" stays answerable")
            .isEqualTo(firstDue.toString());
        assertThat(history.get(1).get("dueOn").asString())
            .as("and the new cycle is a year on from the FIRST date, not a day added at a time")
            .isEqualTo(firstDue.plusYears(1).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment", Long.class))
            .as("one standing obligation, not one per year -- a new assignment each year would "
                + "collide with the index that stops a course being assigned twice")
            .isEqualTo(1);
    }

    @Test
    void aRecurringAssignmentNeedsADeadlineToRecurAgainst() throws Exception {
        HttpResponse<String> refused = post("/api/v1/assignments", ACME,
            assignBody("USER", LEARNER, "{\"kind\":\"NONE\",\"recurrenceMonths\":12}"));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body())
            .as("\"every year, no due date\" would produce cycles that never end")
            .contains("recur against");
    }

    @Test
    void aRelativeDeadlineWithoutABasisIsRefusedRatherThanDefaulted() throws Exception {
        HttpResponse<String> refused = post("/api/v1/assignments", ACME,
            assignBody("USER", LEARNER, "{\"kind\":\"RELATIVE\",\"afterDays\":30}"));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body())
            .as("both readings are right for different training, so picking one silently is how "
                + "a compliance report ends up meaning something nobody can explain")
            .contains("no default");
    }

    @Test
    void theStricterOfTwoDeadlinesWins() throws Exception {
        UUID group = UUID.randomUUID();
        reach(LEARNER, group, Instant.now());
        LocalDate soon = LocalDate.now().plusDays(3);
        LocalDate later = LocalDate.now().plusDays(90);
        assignWithDue(LEARNER, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + soon + "\"}");
        assignGroupWithDue(group, "{\"kind\":\"ABSOLUTE\",\"on\":\"" + later + "\"}");

        JsonNode obligation = obligations().get(0);

        assertThat(obligation.get("sources")).hasSize(2);
        assertThat(obligation.get("dueOn").asString())
            .as("a second, laxer assignment must not quietly extend a deadline the first one set")
            .isEqualTo(soon.toString());
    }

    @Test
    void anAssignmentWithNoDeadlineIsNeverOverdueAndIsInNoCycle() throws Exception {
        UUID assignmentId = idOf(post("/api/v1/assignments", ACME,
            assignBody("USER", LEARNER, null)));

        JsonNode obligation = obligations().get(0);

        assertThat(obligation.get("dueOn").isNull()).isTrue();
        assertThat(obligation.get("overdue").asBoolean()).isFalse();
        assertThat(obligation.get("cycleNumber").isNull()).isTrue();
        assertThat(json.readTree(
            get("/api/v1/assignments/" + assignmentId + "/cycles", ACME).body()))
            .as("nothing to be in a cycle of")
            .isEmpty();
    }

    private boolean overdueFor(UUID learner, Instant when) {
        List<AssignmentService.Obligation> owed = TenantContext.callWithUnchecked("acme",
            () -> assignments.obligationsOf(learner, when));
        return owed.get(0).overdue();
    }

    // -- plumbing ------------------------------------------------------------------------------

    private JsonNode obligations() throws Exception {
        return obligationsOf(LEARNER);
    }

    private JsonNode obligationsOf(UUID learner) throws Exception {
        return json.readTree(get("/api/v1/assignments/of/" + learner, ACME).body());
    }

    private UUID assignWithDue(UUID learner, String due) throws Exception {
        return idOf(post("/api/v1/assignments", ACME, assignBody("USER", learner, due)));
    }

    private UUID assignGroupWithDue(UUID group, String due) throws Exception {
        return idOf(post("/api/v1/assignments", ACME, assignBody("GROUP", group, due)));
    }

    private String assignBody(String targetType, UUID targetId, String due) {
        return "{\"targetType\":\"" + targetType + "\",\"targetId\":\"" + targetId + "\""
            + ",\"referenceType\":\"COURSE\",\"referenceId\":\"" + course + "\""
            + ",\"assignedBy\":\"" + ADMIN + "\""
            + (due == null ? "" : ",\"due\":" + due) + "}";
    }

    /**
     * Backdates when the assignment was made.
     *
     * <p>In SQL because there is no endpoint that would do it and there should not be: an
     * assignment's date is when somebody made it, and a caller who could choose it could put an
     * obligation in the past and be compliant by arithmetic.
     */
    private void assignedDaysAgo(UUID assignmentId, int days) {
        jdbc.update("UPDATE assignment SET assigned_at = now() - make_interval(days => ?) "
            + "WHERE id = ?", days, assignmentId);
        jdbc.update("UPDATE assignment_cycle SET opens_at = now() - make_interval(days => ?) "
            + "WHERE assignment_id = ?", days, assignmentId);
    }

    private void reach(UUID learner, UUID group, Instant when) {
        jdbc.update("""
            INSERT INTO learner_group_reach (tenant_id, learner_id, group_id, reached_at)
            VALUES ('acme', ?, ?, ?)
            """, learner, group, java.sql.Timestamp.from(when));
    }

    private void profile(UUID learner, String zone, String email) {
        jdbc.update("""
            INSERT INTO learner_profile (tenant_id, learner_id, time_zone, email, display_name,
                                         first_seen_at, updated_at)
            VALUES ('acme', ?, ?, ?, 'A learner', now(), now())
            """, learner, zone, email);
    }

    private void publishCourse() throws Exception {
        assertThat(post("/api/v1/courses/" + course + "/versions", ACME,
            "{\"notes\":\"ready\",\"publishedBy\":\"" + ADMIN + "\"}").statusCode()).isEqualTo(200);
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
