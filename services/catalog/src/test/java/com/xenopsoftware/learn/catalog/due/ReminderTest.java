package com.xenopsoftware.learn.catalog.due;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import com.xenopsoftware.learn.common.mail.Letter;
import com.xenopsoftware.learn.common.mail.MailNotSent;
import com.xenopsoftware.learn.common.mail.Mailer;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

/**
 * Reminders: sent once, at the learner's own hour, and never at the cost of the obligation
 * (T-5.6's third and fourth criteria).
 *
 * <p>The pass is driven with an explicit clock rather than waited for. Every property worth
 * asserting here is about <em>when</em> — before the hour, after the window, a second time — and a
 * test that waited for the scheduler could assert none of them. The scheduler itself is parked to
 * an hour by the harness so it cannot race these.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubTokens.class, ReminderTest.StubMail.class})
class ReminderTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final UUID ADMIN = UUID.randomUUID();
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    /**
     * A mailer that keeps what it was handed and can be told to refuse an address.
     *
     * <p>{@code @Primary} rather than a property, because what is under test is the call site's
     * behaviour when a provider says no — and the only honest way to produce that is a provider
     * that says no.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubMail {

        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }

    static class RecordingMailer implements Mailer {

        private final List<Letter> sent = new CopyOnWriteArrayList<>();
        private volatile String refuseTo;

        @Override
        public void send(Letter letter) {
            if (letter.to().equals(refuseTo)) {
                throw new MailNotSent("The provider refused " + letter.to(), null);
            }
            sent.add(letter);
        }

        @Override
        public boolean delivers() {
            return true;
        }

        void refuse(String address) {
            refuseTo = address;
        }

        void forget() {
            sent.clear();
            refuseTo = null;
        }
    }

    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;
    @Autowired
    private ReminderService reminders;
    @Autowired
    private Reminders records;
    @Autowired
    private RecordingMailer mailer;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;
    private UUID course;
    private UUID node;

    @BeforeEach
    void aCourseAndSomebodyToRemind() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        mailer.forget();
        course = idOf(post("/api/v1/courses", "{\"title\":\"Fire safety\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules",
            "{\"title\":\"Part one\"}"));
        node = idOf(post("/api/v1/courses/modules/" + module + "/nodes",
            "{\"contentItemId\":\"" + publishedItem() + "\"}"));
        publishCourse();
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
        mailer.forget();
    }

    // ---------------------------------------------------------------- sent once, and only once

    @Test
    void aReminderIsSentOnceAndASecondPassSendsNothing() throws Exception {
        UUID learner = learnerIn(ISTANBUL, "kaya@acme.test");
        LocalDate due = LocalDate.now(ISTANBUL).plusDays(7);
        assign(learner, due, List.of(-7));

        ReminderService.Pass first = pass(atNineThirty(due.minusDays(7), ISTANBUL));

        assertThat(first.sent()).isEqualTo(1);
        assertThat(mailer.sent).hasSize(1);
        assertThat(mailer.sent.getFirst().to()).isEqualTo("kaya@acme.test");
        assertThat(mailer.sent.getFirst().subject())
            .as("a reminder names the training, because one that does not teaches people to "
                + "ignore reminders")
            .contains("Fire safety");

        // The cluster rebuild, the second replica, the pass that runs every fifteen minutes.
        ReminderService.Pass second = pass(atNineThirty(due.minusDays(7), ISTANBUL)
            .plusSeconds(3600));

        assertThat(second.sent()).isZero();
        assertThat(mailer.sent)
            .as("the claim is a primary key, so a second pass finds the row and does nothing -- "
                + "idempotence lives in the database, not in this service being careful")
            .hasSize(1);
        assertThat(outcomes()).containsExactly("SENT");
    }

    @Test
    void nothingIsSentBeforeTheHourArrivesWhereTheLearnerIs() throws Exception {
        // The same due date and the same offset for two people ten hours apart. There is an
        // instant where one of them is owed a reminder and the other is not, and finding it is
        // the whole difference between "per learner" and "per server".
        UUID istanbul = learnerIn(ISTANBUL, "kaya@acme.test");
        UUID california = learnerIn(LOS_ANGELES, "sam@acme.test");
        LocalDate due = LocalDate.now(ISTANBUL).plusDays(7);
        assign(istanbul, due, List.of(-7));
        assign(california, due, List.of(-7));

        Instant istanbulsMorning = atNineThirty(due.minusDays(7), ISTANBUL);
        ReminderService.Pass morning = pass(istanbulsMorning);

        assertThat(morning.sent()).isEqualTo(1);
        assertThat(mailer.sent.getFirst().to())
            .as("nine in the morning in Istanbul is the middle of the night in California, and a "
                + "compliance nudge at 23:30 is how reminders get filtered to a folder nobody "
                + "opens")
            .isEqualTo("kaya@acme.test");

        ReminderService.Pass californiasMorning =
            pass(atNineThirty(due.minusDays(7), LOS_ANGELES));

        assertThat(californiasMorning.sent()).isEqualTo(1);
        assertThat(mailer.sent).hasSize(2);
        assertThat(mailer.sent.getLast().to()).isEqualTo("sam@acme.test");
    }

    // ---------------------------------------------------------------- the week of mail nobody wants

    @Test
    void aWindowMissedByLongerThanTheCatchUpIsRecordedRatherThanSent() throws Exception {
        UUID learner = learnerIn(ISTANBUL, "kaya@acme.test");
        LocalDate due = LocalDate.now(ISTANBUL).plusDays(7);
        assign(learner, due, List.of(-7));

        // The service was down for five days. The default catch-up is two.
        ReminderService.Pass late = pass(atNineThirty(due.minusDays(7), ISTANBUL)
            .plus(java.time.Duration.ofDays(5)));

        assertThat(late.sent()).isZero();
        assertThat(late.missed())
            .as("a service back after a week must not deliver a week of nudges at once -- that is "
                + "the symptom the criterion names")
            .isEqualTo(1);
        assertThat(mailer.sent).isEmpty();
        assertThat(outcomes())
            .as("and it must not pretend the window never existed either: the row says it was "
                + "missed, where somebody can be asked about it")
            .containsExactly("FAILED");
        assertThat(detail())
            .as("with the reason in words, because 'FAILED' alone would read as a bounce")
            .contains("Window missed");
        assertThat(records.unsent("acme"))
            .as("and it is on the list an administrator can actually read")
            .hasSize(1);
    }

    // ---------------------------------------------------------------- mail fails, work does not

    @Test
    void aMailFailureIsRecordedAndChangesNothingAboutTheObligation() throws Exception {
        UUID refused = learnerIn(ISTANBUL, "bounces@acme.test");
        UUID delivered = learnerIn(ISTANBUL, "kaya@acme.test");
        LocalDate due = LocalDate.now(ISTANBUL).plusDays(7);
        UUID assignment = assign(refused, due, List.of(-7));
        assign(delivered, due, List.of(-7));
        mailer.refuse("bounces@acme.test");

        ReminderService.Pass result = pass(atNineThirty(due.minusDays(7), ISTANBUL));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.sent())
            .as("one bad address must not stop the rest of a department's mail")
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM assignment WHERE id = ?",
            java.sql.Timestamp.class, assignment))
            .as("a mail failure never blocks the assignment: they still owe the training, and a "
                + "provider having a bad morning does not change that")
            .isNull();
        assertThat(obligationsOf(refused))
            .as("and it is still on their list")
            .contains(course.toString());
        assertThat(records.unsent("acme")).hasSize(1);

        // At-most-once, stated by a test rather than only in a comment: the claim committed
        // before the send, so the failed reminder is not tried again on the next pass.
        mailer.refuse(null);
        assertThat(pass(atNineThirty(due.minusDays(7), ISTANBUL).plusSeconds(3600)).sent())
            .isZero();
    }

    // ---------------------------------------------------------------- who gets left alone

    @Test
    void nobodyIsRemindedAboutTrainingTheyHaveAlreadyFinished() throws Exception {
        UUID learner = learnerIn(ISTANBUL, "kaya@acme.test");
        LocalDate due = LocalDate.now(ISTANBUL).plusDays(7);
        assign(learner, due, List.of(-7));
        finished(learner, node);

        ReminderService.Pass result = pass(atNineThirty(due.minusDays(7), ISTANBUL));

        assertThat(result.sent()).isZero();
        assertThat(mailer.sent)
            .as("mailing a department to nag the people who did the training last week is how "
                + "reminders get switched off entirely")
            .isEmpty();
        assertThat(outcomes())
            .as("and nothing was claimed, so the day they are genuinely late one still can be")
            .isEmpty();
    }

    @Test
    void anAssignmentWithNoRemindersIsLeftAlone() throws Exception {
        UUID learner = learnerIn(ISTANBUL, "kaya@acme.test");
        assign(learner, LocalDate.now(ISTANBUL).plusDays(7), List.of());

        assertThat(pass(Instant.now().plus(java.time.Duration.ofDays(30))).sent()).isZero();
        assertThat(mailer.sent).isEmpty();
    }

    @Test
    void anOverdueNudgeSaysTheTrainingIsStillThereToDo() throws Exception {
        UUID learner = learnerIn(ISTANBUL, "kaya@acme.test");
        LocalDate due = LocalDate.now(ISTANBUL).minusDays(1);
        assign(learner, due, List.of(1));

        assertThat(pass(atNineThirty(due.plusDays(1), ISTANBUL)).sent()).isEqualTo(1);
        assertThat(mailer.sent.getFirst().subject()).startsWith("Overdue:");
        assertThat(mailer.sent.getFirst().body())
            .as("overdue marks a state and takes nothing away, so the mail that announces it must "
                + "not read like a door closing")
            .contains("You can still complete it");
    }

    // ---------------------------------------------------------------- plumbing

    private ReminderService.Pass pass(Instant now) {
        return TenantContext.callWithUnchecked("acme", () -> reminders.sendFor("acme", now));
    }

    /** Nine thirty in the morning of {@code day}, where the learner is: after the send hour. */
    private static Instant atNineThirty(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).plusHours(9).plusMinutes(30).toInstant();
    }

    private UUID learnerIn(ZoneId zone, String email) {
        UUID learnerId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO learner_profile (tenant_id, learner_id, time_zone, email, display_name,
                                         first_seen_at, updated_at)
            VALUES ('acme', ?, ?, ?, 'A learner', now(), now())
            """, learnerId, zone.getId(), email);
        return learnerId;
    }

    private UUID assign(UUID learner, LocalDate due, List<Integer> offsets) throws Exception {
        String body = "{\"targetType\":\"USER\",\"targetId\":\"" + learner + "\""
            + ",\"referenceType\":\"COURSE\",\"referenceId\":\"" + course + "\""
            + ",\"assignedBy\":\"" + ADMIN + "\""
            + ",\"due\":{\"kind\":\"ABSOLUTE\",\"on\":\"" + due + "\"}"
            + ",\"reminderOffsets\":[" + offsets.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
        return idOf(post("/api/v1/assignments", body));
    }

    /** A completion inside the current cycle, written where the event would put it (T-3.7). */
    private void finished(UUID learner, UUID nodeId) {
        jdbc.update("""
            INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, recorded_at)
            VALUES (?, 'acme', ?, ?, 'COMPLETED', now())
            """, UUID.randomUUID(), learner, nodeId);
    }

    private List<String> outcomes() {
        return jdbc.queryForList("SELECT outcome FROM reminder_sent ORDER BY claimed_at",
            String.class);
    }

    private String detail() {
        return jdbc.queryForObject("SELECT detail FROM reminder_sent", String.class);
    }

    private String obligationsOf(UUID learner) throws Exception {
        return get("/api/v1/assignments/of/" + learner).body();
    }

    private void publishCourse() throws Exception {
        assertThat(post("/api/v1/courses/" + course + "/versions",
            "{\"notes\":\"ready\",\"publishedBy\":\"" + ADMIN + "\"}").statusCode()).isEqualTo(200);
    }

    private UUID publishedItem() throws Exception {
        UUID id = idOf(post("/api/v1/content-items",
            "{\"type\":\"video\",\"title\":\"the drill\",\"payload\":{\"assetId\":\"a\"}}"));
        send(request("/api/v1/content-items/" + id + "/state")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"PUBLISHED\"}")));
        return id;
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
