package com.xenopsoftware.learn.assessment.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.attempt.AttemptService;
import com.xenopsoftware.learn.assessment.attempt.MutableClock;
import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.exam.SectionService;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.form.FormItem;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * Marking, and the state between passed and failed (T-6.7).
 *
 * <p>The assertion this whole class exists for is one line: an attempt waiting on an essay carries
 * {@code passed = null}, and null is not false. Everything else is what has to be true around it for
 * that to stay true.
 */
@SpringBootTest
@Import(MutableClock.Wiring.class)
class GradingTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID GRADER = UUID.randomUUID();

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
    private GradingService grading;
    @Autowired
    private MarkingQueue queue;
    @Autowired
    private Rubrics rubrics;
    @Autowired
    private GradeEvents events;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID bank;
    private UUID test;
    private UUID section;

    @BeforeEach
    void aBankAndATest() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        clock.reset();
        inTenant(() -> {
            bank = banks.create("Fire safety", null).getId();
            test = tests.create("Annual refresher", null, 50).getId();
            section = sections.addFixed(test, "Everything").getId();
            return null;
        });
    }

    // ---------------------------------------------------------------- machine marking

    @Nested
    class OnSubmit {

        @Test
        void marksEverythingAMachineCanAndSettlesTheAttempt() {
            questionsAre(choice("one"), choice("two"));
            Attempt attempt = sitAnswering("a", "b");

            assertThat(attempt.getGrading()).isEqualTo(Grading.GRADED);
            assertThat(attempt.isSettled()).isTrue();
            assertThat(attempt.getScorePercent())
                .as("one of two right, weighted as one section")
                .isEqualTo(50);
            assertThat(attempt.getPassed())
                .as("fifty per cent against a pass mark of fifty")
                .isTrue();
            assertThat(attempt.getScoreRaw()).isEqualByComparingTo("1");
            assertThat(attempt.getScoreScaled()).isEqualByComparingTo("0.5");
        }

        @Test
        void storesBothNumbersAndTheVerdictOnTheRow() {
            questionsAre(choice("one"), choice("two"));
            Attempt attempt = sitAnswering("a", "a");

            Map<String, Object> row = jdbc.queryForMap(
                "SELECT score_raw, score_scaled, score_percent, passed, grading, graded_at "
                + "FROM attempt WHERE id = ?", attempt.getId());

            assertThat(row).containsEntry("grading", "GRADED").containsEntry("passed", true);
            assertThat((BigDecimal) row.get("score_raw")).isEqualByComparingTo("2");
            assertThat((BigDecimal) row.get("score_scaled")).isEqualByComparingTo("1");
            assertThat(((Number) row.get("score_percent")).intValue()).isEqualTo(100);
            assertThat(row.get("graded_at")).isNotNull();
        }

        @Test
        void anUnansweredQuestionIsWorthNothingAndIsNotWaitingForAnybody() {
            questionsAre(choice("one"), choice("two"));
            Attempt attempt = sitAnswering("a", null);

            assertThat(attempt.getGrading())
                .as("a machine can mark a blank: it is worth nothing, and nobody has to read it")
                .isEqualTo(Grading.GRADED);
            assertThat(attempt.getScorePercent()).isEqualTo(50);
        }

        /** The one this task exists for. */
        @Test
        void anAttemptWithAnEssayWaitsForAPersonAndIsNeitherPassedNorFailed() {
            questionsAre(choice("one"), essay("why"));
            Attempt attempt = sitAnswering("a", "Because the extinguisher is non-conductive.");

            assertThat(attempt.getGrading()).isEqualTo(Grading.AWAITING_GRADING);
            assertThat(attempt.getPassed())
                .as("NULL, AND NULL IS NOT FALSE. A gate reading this as a fail locks a learner "
                    + "out of a course they may well have passed -- which is the sentence this "
                    + "whole task is written around")
                .isNull();
            assertThat(attempt.isSettled()).isFalse();
            assertThat(attempt.getScorePercent())
                .as("scored out of what could be marked, which is one question")
                .isEqualTo(100);
        }

        @Test
        void theDatabaseRefusesAGradedAttemptWithNoVerdict() {
            questionsAre(choice("one"), essay("why"));
            Attempt attempt = sitAnswering("a", "Words.");

            assertThatThrownBy(() -> jdbc.update(
                "UPDATE attempt SET grading = 'GRADED' WHERE id = ?", attempt.getId()))
                .as("\"graded but nobody wrote a score\" is the state a gate would read as a "
                    + "fail, so it is not reachable at all")
                .hasMessageContaining("ck_attempt_graded_has_a_verdict");
        }
    }

    // ---------------------------------------------------------------- the queue

    @Nested
    class TheQueue {

        @Test
        void showsWhatIsWaitingAndHowLongItHasWaited() {
            questionsAre(choice("one"), essay("why"));
            Attempt attempt = sitAnswering("a", "Words.");

            clock.advance(Duration.ofHours(26));
            List<MarkingQueue.Waiting> waiting = inTenant(() -> queue.waiting(null, 50));

            assertThat(waiting).singleElement().satisfies(item -> {
                assertThat(item.attemptId()).isEqualTo(attempt.getId());
                assertThat(item.outstanding())
                    .as("one loose end, not a whole exam of essays")
                    .isEqualTo(1);
                assertThat(item.waiting())
                    .as("measured from when the learner finished, not from when somebody noticed")
                    .isEqualTo(Duration.ofHours(26));
            });
            assertThat(inTenant(queue::depth)).isEqualTo(1);
        }

        @Test
        void isEmptyOnceEverythingIsMarked() {
            questionsAre(choice("one"));
            sitAnswering("a");

            assertThat(inTenant(() -> queue.waiting(null, 50))).isEmpty();
        }

        @Test
        void isOldestFirstSoNothingCanSitForEver() {
            questionsAre(essay("why"));
            Attempt first = sitAnswering("Words.");
            clock.advance(Duration.ofHours(3));
            Attempt second = anotherLearnerSits();

            assertThat(inTenant(() -> queue.waiting(null, 50)))
                .extracting(MarkingQueue.Waiting::attemptId)
                .as("any other order lets one attempt sit for ever with nobody able to see it")
                .containsExactly(first.getId(), second.getId());
        }
    }

    // ---------------------------------------------------------------- marking by hand

    @Nested
    class APersonMarking {

        @Test
        void settlesTheAttemptAndChangesTheScore() {
            questionsAre(choice("one"), essay("why"));
            // 'b' is offered and wrong -- a response naming a choice the question does not have
            // is refused when it is SAVED (T-6.3), long before anything is marked.
            Attempt attempt = sitAnswering("b", "Words.");
            assertThat(attempt.getGrading()).isEqualTo(Grading.AWAITING_GRADING);

            Attempt marked = mark(attempt, essayResponse(attempt), new BigDecimal("1"), null,
                "Marked");

            assertThat(marked.getGrading())
                .as("marking the last outstanding answer settles it -- a separate \"finish "
                    + "grading\" call would be a step somebody forgets")
                .isEqualTo(Grading.GRADED);
            assertThat(marked.getScorePercent())
                .as("nothing for the choice, everything for the essay")
                .isEqualTo(50);
            assertThat(marked.getPassed()).isTrue();
        }

        @Test
        void isRecordedWithTheGraderAndTheirComment() {
            questionsAre(essay("why"));
            Attempt attempt = sitAnswering("Words.");
            mark(attempt, essayResponse(attempt), BigDecimal.ONE, null, "Marked");

            assertThat(inTenant(() -> grading.marksOf(attempt.getId()))).singleElement()
                .satisfies(answer -> {
                    assertThat(answer.gradedBy()).isEqualTo(GRADER);
                    assertThat(answer.comment()).isEqualTo("Good.");
                });
        }

        @Test
        void isNotUndoneByALaterRecompute() {
            questionsAre(choice("one"), essay("why"));
            Attempt attempt = sitAnswering("a", "Words.");
            // A person overrules the machine on the CHOICE question, which it already marked.
            UUID choiceResponse = inTenant(() -> grading.marksOf(attempt.getId()).stream()
                .filter(mark -> mark.gradedBy() == null && mark.credited() != null)
                .findFirst().orElseThrow().responseId());
            mark(attempt, choiceResponse, BigDecimal.ZERO, null, "Answer was ambiguous");

            // Then somebody marks the essay, which recomputes the whole attempt.
            Attempt settled = mark(attempt, essayResponse(attempt), BigDecimal.ONE, null, "Marked");

            assertThat(settled.getScorePercent())
                .as("a recompute triggered by somebody else's essay must not quietly undo a "
                    + "person's mark on another answer")
                .isEqualTo(50);
        }

        @Test
        void cannotAwardMoreThanTheQuestionIsWorth() {
            questionsAre(essay("why"));
            Attempt attempt = sitAnswering("Words.");

            assertThatThrownBy(() ->
                mark(attempt, essayResponse(attempt), new BigDecimal("5"), null, "Generous"))
                .hasMessageContaining("worth 1");
        }

        @Test
        void cannotAwardANegativeMark() {
            questionsAre(essay("why"));
            Attempt attempt = sitAnswering("Words.");

            assertThatThrownBy(() ->
                mark(attempt, essayResponse(attempt), new BigDecimal("-1"), null, "Cross"))
                .hasMessageContaining("not negative");
        }

        @Test
        void cannotMarkAnAttemptStillBeingSat() {
            questionsAre(essay("why"));
            AttemptService.Sitting sitting = inTenant(() -> attempts.startOrResume(test, LEARNER));
            UUID item = sitting.form().items().getFirst().id();
            inTenant(() -> {
                attempts.answer(sitting.attempt().getId(), LEARNER, item,
                    JSON.readTree("{\"text\":\"Words.\"}"));
                return null;
            });

            assertThatThrownBy(() -> inTenant(() -> grading.mark(sitting.attempt().getId(),
                UUID.randomUUID(), BigDecimal.ONE, null, Map.of(), GRADER, "Early")))
                .hasMessageContaining("still being sat");
        }
    }

    // ---------------------------------------------------------------- rubrics

    @Nested
    class ARubric {

        @Test
        void makesTheBreakdownRequired() {
            UUID question = essay("why");
            questionsAre(question);
            UUID structure = criterion(question, "Structure", "1");
            criterion(question, "Evidence", "2");
            Attempt attempt = sitAnswering("Words.");

            assertThatThrownBy(() ->
                mark(attempt, essayResponse(attempt), BigDecimal.ONE, null, "Flat"))
                .as("a single number tells the learner nothing about which part they lost, which "
                    + "is the only thing a rubric is for")
                .hasMessageContaining("rubric of 2 criteria");
            assertThat(structure).isNotNull();
        }

        @Test
        void sumsToTheMarkAndIsStored() {
            UUID question = essay("why");
            questionsAre(question);
            UUID structure = criterion(question, "Structure", "0.4");
            UUID evidence = criterion(question, "Evidence", "0.6");
            Attempt attempt = sitAnswering("Words.");

            Attempt marked = mark(attempt, essayResponse(attempt), null,
                Map.of(structure, new BigDecimal("0.4"), evidence, new BigDecimal("0.3")),
                "Marked to the rubric");

            assertThat(marked.getScoreRaw())
                .as("the sum of the criteria is the mark; the grader never types the total")
                .isEqualByComparingTo("0.7");
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM response_criterion_mark", Integer.class)).isEqualTo(2);
        }

        @Test
        void refusesAMarkOverACriterionsMaximum() {
            UUID question = essay("why");
            questionsAre(question);
            UUID structure = criterion(question, "Structure", "0.4");
            criterion(question, "Evidence", "0.6");
            Attempt attempt = sitAnswering("Words.");

            assertThatThrownBy(() -> mark(attempt, essayResponse(attempt), null,
                Map.of(structure, new BigDecimal("0.9"), criterionsOf(question).get(1),
                    new BigDecimal("0.1")), "Too generous"))
                .hasMessageContaining("'Structure' is worth at most");
        }

        @Test
        void refusesACriterionLeftUnmarked() {
            UUID question = essay("why");
            questionsAre(question);
            UUID structure = criterion(question, "Structure", "0.4");
            criterion(question, "Evidence", "0.6");
            Attempt attempt = sitAnswering("Words.");

            assertThatThrownBy(() -> mark(attempt, essayResponse(attempt), null,
                Map.of(structure, new BigDecimal("0.4")), "Half done"))
                .hasMessageContaining("Nothing was marked for 'Evidence'");
        }

        @Test
        void refusesABreakdownWhereThereIsNoRubric() {
            questionsAre(essay("why"));
            Attempt attempt = sitAnswering("Words.");

            assertThatThrownBy(() -> mark(attempt, essayResponse(attempt), null,
                Map.of(UUID.randomUUID(), BigDecimal.ONE), "Invented"))
                .hasMessageContaining("no rubric");
        }
    }

    // ---------------------------------------------------------------- the audit

    @Nested
    class TheAudit {

        @Test
        void keepsThePreviousMarkWhenSomebodyRegrades() {
            questionsAre(essay("why"));
            Attempt attempt = sitAnswering("Words.");
            mark(attempt, essayResponse(attempt), BigDecimal.ONE, null, "Full marks");
            mark(attempt, essayResponse(attempt), BigDecimal.ZERO, null, "On appeal: off topic");

            List<GradeEvents.Event> history = inTenant(() -> events.of(attempt.getId()));

            assertThat(history)
                .as("submit, mark, regrade -- and the first two are still there")
                .hasSize(3);
            assertThat(history.getFirst().note()).isEqualTo("On appeal: off topic");
            assertThat(history.getFirst().percent()).isZero();
            assertThat(history.get(1).percent())
                .as("the previous verdict, legible, with the person and the moment on it")
                .isEqualTo(100);
            assertThat(history.get(1).gradedBy()).isEqualTo(GRADER);
            assertThat(history.getLast().gradedBy())
                .as("null is the machine, on submit -- not \"unknown\"")
                .isNull();
        }

        @Test
        void cannotBeEditedOrDeleted() {
            questionsAre(choice("one"));
            Attempt attempt = sitAnswering("a");
            UUID event = inTenant(() -> events.of(attempt.getId()).getFirst().id());

            assertThatThrownBy(() ->
                jdbc.update("UPDATE grade_event SET note = 'nicer' WHERE id = ?", event))
                .hasMessageContaining("cannot be changed");
            assertThatThrownBy(() ->
                jdbc.update("DELETE FROM grade_event WHERE id = ?", event))
                .as("an audit somebody can edit is not an audit")
                .hasMessageContaining("cannot be changed");
        }

        @Test
        void recordsTheWaitingStateWithNoVerdictOnIt() {
            questionsAre(essay("why"));
            Attempt attempt = sitAnswering("Words.");

            assertThat(inTenant(() -> events.of(attempt.getId()))).singleElement()
                .satisfies(event -> {
                    assertThat(event.grading()).isEqualTo(Grading.AWAITING_GRADING);
                    assertThat(event.passed())
                        .as("the whole point of AWAITING_GRADING is that \"not yet\" is not \"no\"")
                        .isNull();
                });
        }
    }

    // ---------------------------------------------------------------- announcing

    @Nested
    class TellingTheOutsideWorld {

        @Test
        void announcesASettledAttemptOnce() {
            questionsAre(choice("one"));
            sitAnswering("a");

            assertThat(announcements()).hasSize(1);
            assertThat(announcements().getFirst())
                .contains("\"passed\": true")
                .as("nobody was waiting, so nobody needs telling that the wait is over")
                .contains("\"wasAwaitingAPerson\": false");
        }

        @Test
        void saysNothingWhileAnAttemptIsStillWaiting() {
            questionsAre(essay("why"));
            sitAnswering("Words.");

            assertThat(announcements())
                .as("a gate must not open on a provisional score, so there is nothing to say yet")
                .isEmpty();
        }

        @Test
        void announcesWhenTheWaitEndsAndSaysThatSomebodyWasWaiting() {
            questionsAre(essay("why"));
            Attempt attempt = sitAnswering("Words.");
            mark(attempt, essayResponse(attempt), BigDecimal.ONE, null, "Marked");

            assertThat(announcements()).singleElement()
                .as("which is what tells catalog to write to the learner rather than stay quiet")
                .satisfies(payload -> assertThat(payload).contains("\"wasAwaitingAPerson\": true"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private void questionsAre(UUID... questionIds) {
        inTenant(() -> sections.questionsAre(section, List.of(questionIds)));
    }

    /** Sits the test, answering each item in form order, and submits. Nulls are left blank. */
    private Attempt sitAnswering(String... answers) {
        AttemptService.Sitting sitting = inTenant(() -> attempts.startOrResume(test, LEARNER));
        List<FormItem> items = sitting.form().items();
        for (int index = 0; index < answers.length && index < items.size(); index++) {
            String answer = answers[index];
            if (answer == null) {
                continue;
            }
            FormItem item = items.get(index);
            String body = answer.length() == 1
                ? "{\"chosen\":[\"" + answer + "\"]}" : "{\"text\":\"" + answer + "\"}";
            inTenant(() -> {
                attempts.answer(sitting.attempt().getId(), LEARNER, item.id(),
                    JSON.readTree(body));
                return null;
            });
        }
        return inTenant(() -> attempts.submit(sitting.attempt().getId(), LEARNER)).attempt();
    }

    private Attempt anotherLearnerSits() {
        UUID other = UUID.randomUUID();
        AttemptService.Sitting sitting = inTenant(() -> attempts.startOrResume(test, other));
        inTenant(() -> {
            attempts.answer(sitting.attempt().getId(), other,
                sitting.form().items().getFirst().id(), JSON.readTree("{\"text\":\"Words.\"}"));
            return null;
        });
        return inTenant(() -> attempts.submit(sitting.attempt().getId(), other)).attempt();
    }

    private Attempt mark(Attempt attempt, UUID responseId, BigDecimal awarded,
            Map<UUID, BigDecimal> criteria, String note) {
        return inTenant(() -> grading.mark(attempt.getId(), responseId, awarded, "Good.",
            criteria == null ? Map.of() : criteria, GRADER, note));
    }

    /** The answer to the human-marked question in this attempt. */
    private UUID essayResponse(Attempt attempt) {
        return inTenant(() -> grading.marksOf(attempt.getId()).stream()
            .filter(mark -> mark.credited() == null)
            .findFirst().orElseThrow().responseId());
    }

    private UUID criterion(UUID questionId, String name, String maxPoints) {
        return inTenant(() -> rubrics.add(questionId, name, new BigDecimal(maxPoints),
            rubrics.of(questionId).size()).id());
    }

    private List<UUID> criterionsOf(UUID questionId) {
        return inTenant(() -> rubrics.of(questionId).stream().map(Rubrics.Criterion::id).toList());
    }

    private List<String> announcements() {
        return jdbc.queryForList(
            "SELECT payload::text FROM outbox WHERE topic = 'assessment.attempt.graded' "
            + "ORDER BY occurred_at", String.class);
    }

    private UUID choice(String name) {
        return inTenant(() -> questions.create(bank, name, JSON.readTree("""
            {"type":"single-choice","stem":"%s?",
             "options":{"choices":[{"id":"a","text":"A"},{"id":"b","text":"B"}]},
             "answerKey":{"correct":["a"]}}""".formatted(name))).getId());
    }

    private UUID essay(String name) {
        return inTenant(() -> questions.create(bank, name, JSON.readTree("""
            {"type":"essay","stem":"%s?"}""".formatted(name))).getId());
    }

    private <T> T inTenant(Supplier<T> body) {
        return TenantContext.callWithUnchecked(TENANT, body);
    }
}
