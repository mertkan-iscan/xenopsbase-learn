package com.xenopsoftware.learn.assessment.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.attempt.AttemptService;
import com.xenopsoftware.learn.assessment.attempt.MutableClock;
import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.exam.SectionService;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.grading.Grading;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integrity signals: recorded, never used to auto-fail (T-6.8).
 *
 * <p>The criterion that cannot be tested here is the one that matters most — "no automatic failure,
 * score reduction or termination from any signal" — because a test can only show that today's code
 * does not do it. {@code TechnicalStructureTest} carries that one as an ArchUnit rule instead: the
 * grading and scoring packages cannot see this package at all, so a build that wanted to auto-fail
 * on a focus loss would have to delete the rule first.
 *
 * <p>What is tested here is everything around it: that signals are captured, that they stop at the
 * end of an attempt, that they are capped, that a reviewer sees them with their innocent
 * explanation attached, that the disclosure cannot fall behind what is collected, and that they are
 * forgotten on their own clock while the attempt survives.
 */
@SpringBootTest
@Import(MutableClock.Wiring.class)
class IntegritySignalTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSE = UUID.randomUUID();

    @Autowired
    private BankService banks;
    @Autowired
    private QuestionService questions;
    @Autowired
    private TestService tests;
    @Autowired
    private SectionService sections;
    @Autowired
    private AttemptService attempts;
    @Autowired
    private IntegrityService integrity;
    @Autowired
    private AttemptEventReaper reaper;
    @Autowired
    private MonitoringDisclosure disclosure;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID test;

    @BeforeEach
    void aTestToSit() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        clock.reset();
        inTenant(() -> {
            UUID bank = banks.create("Fire safety", null).getId();
            test = tests.create("Annual refresher", null, 50).getId();
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(
                questions.create(bank, "one", JSON.readTree("""
                    {"type":"single-choice","stem":"One?",
                     "options":{"choices":[{"id":"a","text":"A"},{"id":"b","text":"B"}]},
                     "answerKey":{"correct":["a"]}}""")).getId()));
            return null;
        });
    }

    // ---------------------------------------------------------------- capturing

    @Nested
    class Recording {

        @Test
        void capturesASignalWithBothClocksAndItsDetail() {
            UUID attempt = start();
            Instant browserSaid = Instant.parse("2026-09-04T09:00:03Z");

            assertThat(record(attempt, IntegritySignal.FOCUS_LOST, browserSaid,
                "{\"awaySeconds\":12}")).isTrue();

            assertThat(inTenant(() -> integrity.of(attempt))).singleElement()
                .satisfies(event -> {
                    assertThat(event.kind()).isEqualTo(IntegritySignal.FOCUS_LOST);
                    assertThat(event.reportedAt())
                        .as("the browser's clock, kept for the order of a burst and nothing else")
                        .isEqualTo(browserSaid);
                    assertThat(event.recordedAt())
                        .as("ours, which is what everything sorts by and retention is measured "
                            + "from")
                        .isNotNull();
                    assertThat(event.detail().get("awaySeconds").asInt()).isEqualTo(12);
                });
        }

        @Test
        void keepsThemInTheOrderTheyReachedUs() {
            UUID attempt = start();
            record(attempt, IntegritySignal.FOCUS_LOST, null, null);
            record(attempt, IntegritySignal.FOCUS_REGAINED, null, null);
            record(attempt, IntegritySignal.PASTE, null, "{\"characters\":240}");

            assertThat(inTenant(() -> integrity.of(attempt)))
                .extracting(AttemptEvents.Event::kind)
                .containsExactly(IntegritySignal.FOCUS_LOST, IntegritySignal.FOCUS_REGAINED,
                    IntegritySignal.PASTE);
        }

        @Test
        void recordsNothingOnceTheAttemptIsOver() {
            UUID attempt = start();
            inTenant(() -> attempts.submit(attempt, LEARNER));

            assertThat(record(attempt, IntegritySignal.FOCUS_LOST, null, null))
                .as("everything after the attempt is a person using the product, not sitting an "
                    + "exam, and collecting it would be collecting behaviour for no reason at all")
                .isFalse();
            assertThat(inTenant(() -> integrity.of(attempt))).isEmpty();
        }

        @Test
        void isCappedSoAWriteEndpointTheLearnerControlsCannotBeALever() {
            UUID attempt = start();
            for (int index = 0; index < 520; index++) {
                record(attempt, IntegritySignal.FOCUS_LOST, null, null);
            }

            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_event", Integer.class))
                .as("far more than an honest three-hour exam produces, far less than a loop can")
                .isEqualTo(500);
        }

        @Test
        void anotherLearnersAttemptIsNotThisOnesToWriteTo() {
            UUID attempt = start();

            assertThatThrownBy(() -> inTenant(() -> integrity.record(attempt, SOMEBODY_ELSE,
                IntegritySignal.FOCUS_LOST, null, null)))
                .as("the same 404 as a missing one (T-2.4)")
                .hasMessageContaining("No such attempt");
        }
    }

    // ---------------------------------------------------------------- not a verdict

    @Nested
    class NothingActsOnThem {

        /**
         * The behavioural half of what the ArchUnit rule enforces structurally.
         *
         * <p>An attempt covered in signals is marked exactly as one with none. This cannot prove
         * the rule — only that today's code obeys it — which is why the rule exists as well.
         */
        @Test
        void anAttemptCoveredInSignalsIsMarkedExactlyLikeAQuietOne() {
            UUID noisy = start();
            for (int index = 0; index < 40; index++) {
                record(noisy, IntegritySignal.FOCUS_LOST, null, null);
                record(noisy, IntegritySignal.TAB_HIDDEN, null, null);
            }
            answerCorrectly(noisy);
            Attempt marked = inTenant(() -> attempts.submit(noisy, LEARNER)).attempt();

            assertThat(marked.getGrading()).isEqualTo(Grading.GRADED);
            assertThat(marked.getPassed())
                .as("no signal can fail a learner, reduce their mark or end their attempt")
                .isTrue();
            assertThat(marked.getScorePercent()).isEqualTo(100);
        }
    }

    // ---------------------------------------------------------------- disclosure

    @Nested
    class WhatTheLearnerIsTold {

        @Test
        void cannotFallBehindWhatIsCollected() {
            List<String> collects = disclosure.forLearner().collects();

            assertThat(collects)
                .as("generated from the same values the recorder accepts, so a signal cannot be "
                    + "collected without appearing here")
                .hasSize(IntegritySignal.values().length);
            assertThat(collects).allSatisfy(line -> assertThat(line).isNotBlank());
        }

        @Test
        void saysPlainlyThatNothingDecidesAScoreFromThem() {
            assertThat(disclosure.forLearner().neverUsedFor())
                .contains("No signal here can fail you");
        }

        @Test
        void saysHowLongTheyAreKeptSeparatelyFromTheResult() {
            assertThat(disclosure.forLearner().keptForDays()).isEqualTo(90);
        }

        @Test
        void neverPromisesThatAnybodyIsWatching() {
            // These are self-reported by the learner's own browser. "We monitor your screen" would
            // be false, and it is the exact sentence somebody would put on a sales page.
            assertThat(disclosure.forLearner().usedFor().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("monitor")
                .doesNotContain("proctor")
                .doesNotContain("detect");
        }

        @Test
        void aKindNobodyDisclosedCannotBeRecorded() {
            assertThatThrownBy(() -> IntegritySignal.valueOf("SCREENSHOT"))
                .as("a free-text kind is a way to start collecting something nobody agreed to")
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------- retention

    @Nested
    class TheirOwnClock {

        /**
         * The criterion asks for retention "stated separately", and separately is the operative
         * word: <b>the signals go and the attempt stays.</b>
         */
        @Test
        void signalsAreForgottenWhileTheAttemptSurvives() {
            UUID attempt = start();
            record(attempt, IntegritySignal.FOCUS_LOST, null, null);
            answerCorrectly(attempt);
            inTenant(() -> attempts.submit(attempt, LEARNER));

            clock.advance(Duration.ofDays(91));
            assertThat(reaper.sweep()).isEqualTo(1);

            assertThat(inTenant(() -> integrity.of(attempt))).isEmpty();
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM attempt WHERE id = ?", Integer.class, attempt))
                .as("a record of somebody's training is kept as long as the training matters; a "
                    + "log of when their attention wandered is not")
                .isEqualTo(1);
        }

        @Test
        void andAreKeptUntilThen() {
            UUID attempt = start();
            record(attempt, IntegritySignal.FOCUS_LOST, null, null);

            clock.advance(Duration.ofDays(89));

            assertThat(reaper.sweep())
                .as("long enough that a dispute about a result can be raised and reviewed")
                .isZero();
            assertThat(inTenant(() -> integrity.of(attempt))).hasSize(1);
        }

        /**
         * Run the way the scheduler runs it: with no tenant bound.
         *
         * <p>T-6.6's reaper shipped broken in exactly this way — every scheduled run threw
         * {@code no tenant identifier specified} while every test passed, because a test called the
         * sweep inside a tenant. This one is written from that lesson rather than into it.
         */
        @Test
        void theSweepWorksFromTheSchedulerWithNoTenantBound() {
            UUID attempt = start();
            record(attempt, IntegritySignal.FOCUS_LOST, null, null);
            clock.advance(Duration.ofDays(91));

            reaper.scheduledSweep();

            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_event", Integer.class))
                .isZero();
        }
    }

    // ---------------------------------------------------------------- helpers

    private UUID start() {
        return inTenant(() -> attempts.startOrResume(test, LEARNER)).attempt().getId();
    }

    private void answerCorrectly(UUID attemptId) {
        inTenant(() -> {
            UUID item = attempts.of(attemptId, LEARNER).form().items().getFirst().id();
            attempts.answer(attemptId, LEARNER, item, JSON.readTree("{\"chosen\":[\"a\"]}"));
            return null;
        });
    }

    private boolean record(UUID attemptId, IntegritySignal kind, Instant reportedAt,
            String detail) {
        return inTenant(() -> integrity.record(attemptId, LEARNER, kind, reportedAt,
            detail == null ? null : JSON.readTree(detail)));
    }

    private <T> T inTenant(Supplier<T> body) {
        return TenantContext.callWithUnchecked(TENANT, body);
    }
}
