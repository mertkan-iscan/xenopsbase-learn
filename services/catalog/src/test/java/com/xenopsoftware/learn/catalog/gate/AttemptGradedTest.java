package com.xenopsoftware.learn.catalog.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.assign.LearnerProfiles;
import com.xenopsoftware.learn.catalog.RecordingMailer;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A passed test opens the gate behind it, and tells the learner (T-6.7, T-5.3).
 *
 * <p>{@link RequiredState#PASSED} has been sayable since T-5.3 and unreachable until now — "pass
 * the safety test" was a rule nothing could ever satisfy. This is the other end of it.
 *
 * <p>The assertions worth reading are the two absences: an attempt <b>awaiting a person</b> records
 * nothing, and a <b>failed</b> one records nothing either. That is how "every gate handles
 * AWAITING_GRADING explicitly" needs no code in the evaluator — a gate cannot read a null score as
 * a fail if there is no row for it to read.
 */
@SpringBootTest
@Import(RecordingMailer.Wiring.class)
class AttemptGradedTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final UUID LEARNER = UUID.randomUUID();

    // T-5.6's recording mailer, reused rather than a second one. A @Primary Mailer of its own
    // would have been a THIRD candidate in this context -- the stub, the real bean and mine -- and
    // Spring refuses two primaries, which is how this test discovered that the existing stub is
    // visible from here.
    @Autowired
    private RecordingMailer mailer;

    @Autowired
    private AttemptGradedHandler handler;
    @Autowired
    private LearnerProfiles profiles;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID node;
    private UUID testId;

    @BeforeEach
    void aCourseWithATestInIt() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        mailer.forget();

        testId = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID course = UUID.randomUUID();
        UUID module = UUID.randomUUID();
        node = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO content_item (id, tenant_id, type, title, state, payload, created_at,
                    updated_at)
            VALUES (?, ?, 'test', 'Safety exam', 'PUBLISHED', ?::jsonb, now(), now())
            """, item, TENANT, "{\"testId\":\"" + testId + "\"}");
        jdbc.update("""
            INSERT INTO course (id, tenant_id, title, created_at, updated_at)
            VALUES (?, ?, 'Onboarding', now(), now())
            """, course, TENANT);
        jdbc.update("""
            INSERT INTO course_module (id, tenant_id, course_id, title, ordinal, created_at,
                    updated_at)
            VALUES (?, ?, ?, 'Module 1', 1, now(), now())
            """, module, TENANT, course);
        jdbc.update("""
            INSERT INTO course_node (id, tenant_id, module_id, content_item_id, ordinal, required,
                    created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, true, now(), now())
            """, node, TENANT, module, item);
        profiles.put(TENANT, LEARNER, "Europe/Istanbul", "learner@acme.example", "A Learner",
            Instant.parse("2026-09-01T00:00:00Z"));
    }

    @AfterEach
    void tidy() {
        emptyEveryTable(dataSource);
    }

    @Test
    void aPassOpensTheGateBehindTheTest() {
        Instant gradedAt = Instant.parse("2026-09-05T10:00:00Z");

        handler.handle(graded(true, false, gradedAt));

        assertThat(TenantContext.callWithUnchecked(TENANT, () ->
            new NodeCompletionRepository(dataSource).statesOf(TENANT, LEARNER, List.of(node))))
            .as("PASSED has been sayable since T-5.3 and unreachable until now")
            .containsEntry(node, EnumSet.of(RequiredState.PASSED));
        assertThat(jdbc.queryForObject(
            "SELECT recorded_at FROM node_completion WHERE node_id = ?", Instant.class, node))
            .as("when the verdict was reached, not when the bus got round to telling us")
            .isEqualTo(gradedAt);
    }

    @Test
    void aFailOpensNothing() {
        handler.handle(graded(false, false, Instant.now()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM node_completion", Long.class))
            .as("and it writes no negative either -- a learner may sit it again, and a gate reads "
                + "the absence of a PASSED row")
            .isZero();
    }

    @Test
    void theSamePassTwiceIsOneRow() {
        OutboxMessage message = graded(true, false, Instant.now());

        handler.handle(message);
        handler.handle(message);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM node_completion", Long.class))
            .as("the bus re-sends by design, and the unique key is what survives it")
            .isEqualTo(1);
    }

    @Test
    void aPassAtATestNoNodeReferencesIsDroppedRatherThanRetriedForever() {
        handler.handle(gradedAt(UUID.randomUUID(), true, false, Instant.now()));

        // The course was edited between the learner sitting it and this arriving, which is
        // ordinary. A failing insert would put a poison message at the head of the queue.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM node_completion", Long.class))
            .isZero();
    }

    @Test
    void theLearnerIsToldWhenSomebodyHadBeenWaiting() {
        handler.handle(graded(true, true, Instant.now()));

        assertThat(mailer.sent()).singleElement().satisfies(letter -> {
            assertThat(letter.to()).isEqualTo("learner@acme.example");
            assertThat(letter.body())
                .as("the verdict and the score, and nothing about the questions -- an email is the "
                    + "least private place this platform writes")
                .contains("You passed.")
                .contains("64%");
        });
    }

    @Test
    void andIsNotToldWhenTheMarkWasInstant() {
        handler.handle(graded(true, false, Instant.now()));

        assertThat(mailer.sent())
            .as("an exam marked the moment it was submitted needs no letter: the learner is "
                + "looking at the result")
            .isEmpty();
    }

    @Test
    void aFailedAttemptSomebodyWasWaitingOnIsStillWorthTelling() {
        handler.handle(graded(false, true, Instant.now()));

        assertThat(mailer.sent()).singleElement().satisfies(letter ->
            assertThat(letter.body())
                .as("somebody waited days to hear; \"you did not pass\" is still the answer they "
                    + "were waiting for")
                .contains("You did not pass this time."));
    }

    private OutboxMessage graded(boolean passed, boolean wasWaiting, Instant gradedAt) {
        return gradedAt(testId, passed, wasWaiting, gradedAt);
    }

    private OutboxMessage gradedAt(UUID which, boolean passed, boolean wasWaiting,
            Instant gradedAt) {
        String payload = """
            {"tenantId":"%s","attemptId":"%s","testId":"%s","learnerId":"%s","attemptNumber":1,
             "scoreRaw":16,"scoreScaled":0.64,"scorePercent":64,"passed":%s,
             "wasAwaitingAPerson":%s,"gradedAt":"%s"}
            """.formatted(TENANT, UUID.randomUUID(), which, LEARNER, passed, wasWaiting, gradedAt);
        return new OutboxMessage(UUID.randomUUID(), TENANT, "assessment.attempt.graded",
            "AttemptGraded", payload, null, Instant.now());
    }
}
