package com.xenopsoftware.learn.assessment.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.exam.SectionService;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.form.FormItem;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sitting a test, with the clock on the server (T-6.6).
 *
 * <p>Every assertion here is about the relationship between a deadline and a request, so the clock
 * is the one the test moves. A version of this that used the real clock could only assert the parts
 * that do not matter.
 */
@SpringBootTest
@Import(MutableClock.Wiring.class)
class AttemptLifecycleTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSE = UUID.randomUUID();
    private static final Duration AN_HOUR = Duration.ofHours(1);

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
    private AttemptReaper reaper;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID bank;
    private UUID test;

    @BeforeEach
    void aTestWithThreeQuestions() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        clock.reset();
        inTenant(() -> {
            bank = banks.create("Fire safety", null).getId();
            test = tests.create("Annual refresher", null, 80).getId();
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(choice("one"), choice("two"), choice("three")));
            return null;
        });
    }

    // ---------------------------------------------------------------- the clock

    @Nested
    class TheDeadline {

        @Test
        void isComputedAtStartFromTheServersClock() {
            timed(AN_HOUR);

            AttemptService.Sitting sitting = start();

            assertThat(sitting.attempt().getExpiresAt())
                .isEqualTo(clock.instant().plus(AN_HOUR));
            assertThat(sitting.remaining()).isEqualTo(AN_HOUR);
        }

        @Test
        void isAbsentAltogetherOnAnUntimedTest() {
            AttemptService.Sitting sitting = start();

            assertThat(sitting.attempt().getExpiresAt())
                .as("\"no deadline\" and \"a deadline in the year 3000\" are different facts, and "
                    + "only the first one is true")
                .isNull();
            assertThat(sitting.remaining()).isNull();
        }

        /**
         * THE RULE THIS TASK STATES ONCE AND KEEPS: resume is allowed and the clock keeps running.
         *
         * <p>A pausing clock has to move {@code expires_at}, and the only thing that could say when
         * to pause is the browser reporting it went away — which is the client honesty the whole
         * design refuses to depend on.
         */
        @Test
        void doesNotMoveWhenTheLearnerComesBack() {
            timed(AN_HOUR);
            UUID attempt = start().attempt().getId();
            var deadline = read(attempt).getExpiresAt();

            clock.advance(Duration.ofMinutes(20));
            AttemptService.Sitting resumed = start();

            assertThat(resumed.attempt().getId())
                .as("the same attempt, not a second one")
                .isEqualTo(attempt);
            assertThat(resumed.attempt().getExpiresAt()).isEqualTo(deadline);
            assertThat(resumed.remaining())
                .as("twenty minutes of it are gone, and they are gone")
                .isEqualTo(Duration.ofMinutes(40));
        }

        @Test
        void changingTheTestsLimitDoesNotTouchAnAttemptAlreadyUnderWay() {
            timed(AN_HOUR);
            UUID attempt = start().attempt().getId();
            var deadline = read(attempt).getExpiresAt();

            inTenant(() -> tests.satAs(test, null, Duration.ofMinutes(5)));

            assertThat(read(attempt).getExpiresAt())
                .as("shortening a limit cannot take time off somebody mid-exam, and lengthening "
                    + "it cannot give them more")
                .isEqualTo(deadline);
        }
    }

    // ---------------------------------------------------------------- answers

    @Nested
    class Answers {

        @Test
        void areSavedAsTheyAreGiven() {
            AttemptService.Sitting sitting = start();
            FormItem first = sitting.form().items().getFirst();

            answer(sitting.attempt().getId(), first.id(), "a");

            assertThat(saved(sitting.attempt().getId())).isEqualTo(1);
        }

        /**
         * Saving twice is one row, and saving a different answer replaces it.
         *
         * <p>The second half is why this is an upsert rather than an idempotency key: a learner who
         * changes their mind and whose request is then retried must end up with the NEW answer,
         * where a replayed response would hand back the old one and hide the change.
         */
        @Test
        void areIdempotentByTheShapeOfTheRowAndStillReplaceable() {
            AttemptService.Sitting sitting = start();
            UUID attempt = sitting.attempt().getId();
            FormItem first = sitting.form().items().getFirst();

            answer(attempt, first.id(), "a");
            answer(attempt, first.id(), "a");
            assertThat(saved(attempt)).isEqualTo(1);

            answer(attempt, first.id(), "b");

            assertThat(saved(attempt)).isEqualTo(1);
            assertThat(chosen(attempt, first.id())).isEqualTo("b");
        }

        @Test
        void areRefusedOnceTheClockHasRunOut() {
            timed(AN_HOUR);
            AttemptService.Sitting sitting = start();
            FormItem first = sitting.form().items().getFirst();

            clock.advance(Duration.ofMinutes(61));

            assertThatThrownBy(() -> answer(sitting.attempt().getId(), first.id(), "a"))
                .hasMessageContaining("Time is up");
            assertThat(saved(sitting.attempt().getId()))
                .as("nothing may be written after time -- the rule the whole task exists for")
                .isZero();
        }

        @Test
        void areRefusedOnceTheAttemptIsOver() {
            AttemptService.Sitting sitting = start();
            FormItem first = sitting.form().items().getFirst();
            inTenant(() -> attempts.submit(sitting.attempt().getId(), LEARNER));

            assertThatThrownBy(() -> answer(sitting.attempt().getId(), first.id(), "a"))
                .hasMessageContaining("cannot take another answer");
        }

        /**
         * Validated against what this learner was served, not against the question as it is now.
         *
         * <p>T-6.3 wrote {@code validateResponse} with no caller, saying the shape a learner sends
         * is decided by the same file that decides the shape they were shown. This is that caller.
         */
        @Test
        void mustFitTheQuestionThatWasActuallyServed() {
            AttemptService.Sitting sitting = start();
            FormItem first = sitting.form().items().getFirst();

            assertThatThrownBy(() ->
                answer(sitting.attempt().getId(), first.id(), "z"))
                .as("choice 'z' is not on the paper they were given")
                .hasMessageContaining("z");
        }

        @Test
        void cannotBeGivenForAQuestionThisAttemptWasNeverAsked() {
            AttemptService.Sitting sitting = start();

            assertThatThrownBy(() -> inTenant(() -> {
                attempts.answer(sitting.attempt().getId(), LEARNER, UUID.randomUUID(),
                    JSON.readTree("{\"chosen\":[\"a\"]}"));
                return null;
            })).hasMessageContaining("not asked that question");
        }

        @Test
        void surviveTheLearnerComingBack() {
            AttemptService.Sitting sitting = start();
            UUID attempt = sitting.attempt().getId();
            answer(attempt, sitting.form().items().getFirst().id(), "a");

            clock.advance(Duration.ofMinutes(5));
            AttemptService.Sitting resumed = start();

            assertThat(resumed.attempt().getId()).isEqualTo(attempt);
            assertThat(saved(attempt))
                .as("a browser crash forty minutes into an exam is a support conversation nobody "
                    + "wins, which is why answers are not held until submit")
                .isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- concurrency

    @Nested
    class TwoTabs {

        /**
         * Two tabs cannot both begin the last attempt.
         *
         * <p>Not by a lock and not by looking first — a partial unique index allows one live
         * attempt per learner per test, so the second insert loses and the loser then reads the
         * winner's row. Which is what the person in front of both tabs wanted.
         */
        @Test
        void cannotBothBeginTheLastAttempt() {
            inTenant(() -> tests.satAs(test, 1, null));

            UUID first = start().attempt().getId();
            UUID second = start().attempt().getId();

            assertThat(second).isEqualTo(first);
            assertThat(inTenant(() -> attempts.history(test, LEARNER)))
                .as("one attempt, and the learner still has it")
                .hasSize(1);
        }

        @Test
        void andTheLimitIsEnforcedOnceItIsUsed() {
            inTenant(() -> tests.satAs(test, 1, null));
            UUID first = start().attempt().getId();
            inTenant(() -> attempts.submit(first, LEARNER));

            assertThatThrownBy(AttemptLifecycleTest.this::start)
                .hasMessageContaining("allows 1 attempt and you have used 1");
        }

        @Test
        void aSecondAttemptIsNumberedAsOne() {
            inTenant(() -> tests.satAs(test, 3, null));
            inTenant(() -> attempts.submit(start().attempt().getId(), LEARNER));

            assertThat(start().attempt().getAttemptNumber())
                .as("\"your second attempt\" is a fact, not a row count that moves when "
                    + "something is reaped")
                .isEqualTo(2);
        }

        @Test
        void anotherLearnersAttemptIsNotThisOnesToSee() {
            UUID mine = start().attempt().getId();

            assertThatThrownBy(() -> inTenant(() -> attempts.of(mine, SOMEBODY_ELSE)))
                .as("the same 404 as a missing one: \"it exists but is not yours\" is a fact "
                    + "about somebody else's exam (T-2.4)")
                .hasMessageContaining("No such attempt");
        }
    }

    // ---------------------------------------------------------------- submitting

    @Nested
    class Submitting {

        @Test
        void isIdempotentSoADoubleClickProducesOneEnding() {
            UUID attempt = start().attempt().getId();

            AttemptService.Sitting once = inTenant(() -> attempts.submit(attempt, LEARNER));
            clock.advance(Duration.ofMinutes(3));
            AttemptService.Sitting twice = inTenant(() -> attempts.submit(attempt, LEARNER));

            assertThat(once.attempt().getState()).isEqualTo(Attempt.State.SUBMITTED);
            assertThat(twice.attempt().getState()).isEqualTo(Attempt.State.SUBMITTED);
            assertThat(twice.attempt().getSubmittedAt())
                .as("the second click changed nothing, including the time it says it happened")
                .isEqualTo(once.attempt().getSubmittedAt());
        }

        /**
         * A late submit is accepted, and the attempt is marked EXPIRED rather than thrown away.
         *
         * <p>Nothing in it was written late — the save path refuses that — so refusing the submit
         * would discard work done in time to punish a slow network for a rule about time to think.
         */
        @Test
        void afterTheDeadlineIsStillAcceptedAndMarkedExpired() {
            timed(AN_HOUR);
            AttemptService.Sitting sitting = start();
            answer(sitting.attempt().getId(), sitting.form().items().getFirst().id(), "a");

            clock.advance(Duration.ofMinutes(61));
            AttemptService.Sitting ended =
                inTenant(() -> attempts.submit(sitting.attempt().getId(), LEARNER));

            assertThat(ended.attempt().getState()).isEqualTo(Attempt.State.EXPIRED);
            assertThat(ended.attempt().getSubmittedAt()).isNotNull();
            assertThat(saved(sitting.attempt().getId()))
                .as("EXPIRED does not mean the answers were discarded")
                .isEqualTo(1);
        }

        @Test
        void freesTheLearnerToStartTheirNextAttempt() {
            inTenant(() -> tests.satAs(test, 2, null));
            UUID first = start().attempt().getId();
            inTenant(() -> attempts.submit(first, LEARNER));

            assertThat(start().attempt().getId()).isNotEqualTo(first);
        }
    }

    // ---------------------------------------------------------------- the reaper

    @Nested
    class NothingStaysOpen {

        @Test
        void aTimedAttemptNobodyCameBackToIsExpired() {
            timed(AN_HOUR);
            UUID attempt = start().attempt().getId();

            clock.advance(Duration.ofHours(2));
            assertThat(read(attempt).getState())
                .as("still open until something ends it -- which is the point of the sweep")
                .isEqualTo(Attempt.State.IN_PROGRESS);

            assertThat(reaper.sweep()).isEqualTo(1);
            assertThat(read(attempt).getState()).isEqualTo(Attempt.State.EXPIRED);
        }

        /**
         * An untimed attempt is the one nothing else can ever end.
         *
         * <p>There is no deadline to pass, so without this sweep the row is {@code IN_PROGRESS}
         * until the company stops existing — and it holds the learner's one live slot the whole
         * time.
         */
        @Test
        void anUntimedAttemptNobodyCameBackToIsAbandoned() {
            UUID attempt = start().attempt().getId();

            clock.advance(Duration.ofDays(3));
            assertThat(reaper.sweep())
                .as("three days is somebody working through it over a few evenings")
                .isZero();

            clock.advance(Duration.ofDays(5));
            assertThat(reaper.sweep()).isEqualTo(1);
            assertThat(read(attempt).getState())
                .as("ABANDONED, not EXPIRED: one is a fact about the test and the other about the "
                    + "learner, and a report that merged them could not tell \"everybody runs out "
                    + "of time\" from \"half of them never finish\"")
                .isEqualTo(Attempt.State.ABANDONED);
        }

        @Test
        void andTheSlotIsFreeAfterwards() {
            inTenant(() -> tests.satAs(test, 2, null));
            UUID first = start().attempt().getId();

            clock.advance(Duration.ofDays(8));
            reaper.sweep();

            assertThat(start().attempt().getId())
                .as("a reaped attempt must not keep somebody out of the attempts they have left")
                .isNotEqualTo(first);
        }

        /**
         * THE PATH THAT WAS BROKEN, run the way the scheduler runs it: with no tenant bound.
         *
         * <p>The first version of the reaper used the JPA repository, and every scheduled run threw
         * {@code SessionFactory configured for multi-tenancy, but no tenant identifier specified}.
         * Every test passed, because a test calls the sweep inside a tenant the way a request would
         * — so the only path this criterion is about was broken from the first commit and said so
         * once every five minutes into a log nobody was reading.
         *
         * <p>This test calls the scheduled method itself, outside any tenant. It is the one that
         * would have caught it.
         */
        @Test
        void worksFromTheSchedulerWithNoTenantBound() {
            timed(AN_HOUR);
            UUID attempt = start().attempt().getId();
            clock.advance(Duration.ofHours(2));

            reaper.scheduledSweep();

            assertThat(read(attempt).getState()).isEqualTo(Attempt.State.EXPIRED);
        }

        @Test
        void aLearnerAndTheReaperRacingProduceOneEnding() {
            timed(AN_HOUR);
            UUID attempt = start().attempt().getId();
            clock.advance(Duration.ofHours(2));

            inTenant(() -> attempts.submit(attempt, LEARNER));
            int reaped = reaper.sweep();

            assertThat(reaped)
                .as("the conditional update is what makes this zero rather than a second ending")
                .isZero();
            assertThat(read(attempt).getState()).isEqualTo(Attempt.State.EXPIRED);
        }

        @Test
        void openingAnExpiredAttemptEndsItRatherThanShowingALiveOneWithNoTimeLeft() {
            timed(AN_HOUR);
            UUID attempt = start().attempt().getId();

            clock.advance(Duration.ofHours(2));
            AttemptService.Sitting reopened = start();

            assertThat(reopened.attempt().getId()).isEqualTo(attempt);
            assertThat(reopened.attempt().getState()).isEqualTo(Attempt.State.EXPIRED);
            assertThat(reopened.remaining()).isEqualTo(Duration.ZERO);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void timed(Duration limit) {
        inTenant(() -> tests.satAs(test, null, limit));
    }

    private AttemptService.Sitting start() {
        return inTenant(() -> attempts.startOrResume(test, LEARNER));
    }

    private void answer(UUID attemptId, UUID formItemId, String choice) {
        inTenant(() -> {
            attempts.answer(attemptId, LEARNER, formItemId,
                JSON.readTree("{\"chosen\":[\"" + choice + "\"]}"));
            return null;
        });
    }

    private Attempt read(UUID attemptId) {
        return inTenant(() -> attempts.of(attemptId, LEARNER).attempt());
    }

    private int saved(UUID attemptId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM attempt_response WHERE attempt_id = ?", Integer.class, attemptId);
    }

    private String chosen(UUID attemptId, UUID formItemId) {
        JsonNode response = JSON.readTree(jdbc.queryForObject("""
            SELECT response::text FROM attempt_response
             WHERE attempt_id = ? AND form_item_id = ?
            """, String.class, attemptId, formItemId));
        return response.get("chosen").get(0).asString();
    }

    /** A three-choice question. {@code a} is right; the others are on the paper and wrong. */
    private UUID choice(String name) {
        return questions.create(bank, name, JSON.readTree("""
            {"type":"single-choice","stem":"%s?",
             "options":{"choices":[{"id":"a","text":"A"},{"id":"b","text":"B"},
                                   {"id":"c","text":"C"}]},
             "answerKey":{"correct":["a"]}}""".formatted(name))).getId();
    }

    private <T> T inTenant(Supplier<T> body) {
        return TenantContext.callWithUnchecked(TENANT, body);
    }
}
