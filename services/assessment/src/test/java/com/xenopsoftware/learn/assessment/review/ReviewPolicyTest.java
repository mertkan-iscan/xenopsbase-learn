package com.xenopsoftware.learn.assessment.review;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * What a learner may see after submitting, and when (T-6.9).
 *
 * <p>The assertion the whole task turns on is short: <b>the answer key is not in the payload</b>
 * unless the policy permits it. Not hidden, not flagged — absent. A key that travels and is hidden
 * is a key in the learner's browser, in their network tab, and in whatever cached it on the way.
 */
@SpringBootTest
@Import(MutableClock.Wiring.class)
class ReviewPolicyTest extends PostgresTestHarness {

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
    private ReviewService review;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DataSource dataSource;

    private UUID test;

    @BeforeEach
    void aTwoQuestionTest() {
        emptyEveryTable(dataSource);
        clock.reset();
        inTenant(() -> {
            UUID bank = banks.create("Fire safety", null).getId();
            test = tests.create("Annual refresher", null, 50).getId();
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(choice(bank, "one"), choice(bank, "two")));
            return null;
        });
    }

    // ---------------------------------------------------------------- the default

    @Nested
    class ByDefault {

        @Test
        void aNewTestShowsTheScoreAndNothingAboutTheQuestions() {
            Attempt attempt = sit("a", "b");

            ReviewService.Review seen = review(attempt);

            assertThat(seen.visibility()).isEqualTo("SCORE_ONLY");
            assertThat(seen.scorePercent()).isEqualTo(50);
            assertThat(seen.items())
                .as("the restrictive option is the default, and opening it up is a deliberate act")
                .isEmpty();
        }

        @Test
        void andTheLearnerStillLearnsWhetherTheyPassed() {
            Attempt attempt = sit("a", "a");

            assertThat(review(attempt).passed())
                .as("the score discloses nothing and is the minimum a person is owed after "
                    + "sitting an exam")
                .isTrue();
        }
    }

    // ---------------------------------------------------------------- the three levels

    @Nested
    class WhatEachLevelShows {

        @Test
        void whichWereWrongWithoutSayingWhatWasRight() {
            policy(ReviewVisibility.SCORE_AND_WHICH_WRONG, ReviewTiming.IMMEDIATELY, null);
            Attempt attempt = sit("a", "b");

            List<ReviewService.ReviewedItem> items = review(attempt).items();

            assertThat(items).hasSize(2);
            assertThat(items).extracting(ReviewService.ReviewedItem::correct)
                .containsExactly(true, false);
            assertThat(items).allSatisfy(item -> {
                assertThat(item.body().has("answerKey"))
                    .as("knowing question two was wrong is worth something to a learner and "
                        + "nearly nothing to somebody assembling a copy of the exam")
                    .isFalse();
                assertThat(item.feedback()).isNull();
            });
        }

        @Test
        void fullShowsTheKeyAndTheAuthorsExplanation() {
            policy(ReviewVisibility.FULL, ReviewTiming.IMMEDIATELY, null);
            Attempt attempt = sit("a", "b");

            List<ReviewService.ReviewedItem> items = review(attempt).items();

            assertThat(items.getFirst().body().get("answerKey").get("correct").get(0).asString())
                .isEqualTo("a");
            assertThat(items.getFirst().feedback())
                .as("authored on the version, so it stays correct across edits")
                .isEqualTo("Water conducts electricity.");
        }

        @Test
        void everyLevelShowsTheLearnerTheirOwnAnswerBack() {
            policy(ReviewVisibility.SCORE_AND_WHICH_WRONG, ReviewTiming.IMMEDIATELY, null);
            Attempt attempt = sit("a", "b");

            assertThat(review(attempt).items().getFirst().response().get("chosen").get(0).asString())
                .as("they already know what they answered; withholding it protects nothing")
                .isEqualTo("a");
        }

        /** Reconstructed from the form (T-6.5), which is this learner's paper and not the test. */
        @Test
        void andTheOrderTheyWereShownTheOptionsIn() {
            inTenant(() -> sections.shuffles(
                sections.of(test).getFirst().getId(), false, true));
            policy(ReviewVisibility.FULL, ReviewTiming.IMMEDIATELY, null);
            Attempt attempt = sit("a", "b");

            assertThat(review(attempt).items().getFirst().optionOrder())
                .as("a review showing the test's order rather than theirs is somebody else's paper")
                .containsKey("choices");
        }
    }

    // ---------------------------------------------------------------- timing

    @Nested
    class WhenItOpens {

        @Test
        void afterADateShowsOnlyTheScoreUntilThen() {
            Instant opens = clock.instant().plus(Duration.ofDays(7));
            policy(ReviewVisibility.FULL, ReviewTiming.AFTER_DATE, opens);
            Attempt attempt = sit("a", "b");

            assertThat(review(attempt).visibility())
                .as("the timing lowers what is permitted rather than refusing the request: the "
                    + "score was never what it was protecting")
                .isEqualTo("SCORE_ONLY");
            assertThat(review(attempt).items()).isEmpty();

            clock.advance(Duration.ofDays(8));

            assertThat(review(attempt).visibility()).isEqualTo("FULL");
            assertThat(review(attempt).items()).hasSize(2);
        }

        @Test
        void afterAllAttemptsWaitsUntilTheyAreUsed() {
            inTenant(() -> tests.satAs(test, 2, null));
            policy(ReviewVisibility.FULL, ReviewTiming.AFTER_ALL_ATTEMPTS, null);
            Attempt first = sit("a", "b");

            assertThat(review(first).visibility())
                .as("this is what stops somebody sitting attempt one to read the answers and "
                    + "attempt two to use them")
                .isEqualTo("SCORE_ONLY");

            sit("a", "b");

            assertThat(review(first).visibility()).isEqualTo("FULL");
        }

        /**
         * The trap this refusal exists for: on a test anybody may sit any number of times, "after
         * all attempts" means never, and the learner who can never see their paper finds out.
         */
        @Test
        void andCannotBeChosenOnATestWithNoAttemptLimit() {
            assertThatThrownBy(() -> policy(ReviewVisibility.FULL,
                ReviewTiming.AFTER_ALL_ATTEMPTS, null))
                .hasMessageContaining("would mean never");
        }

        @Test
        void aDateIsRequiredForAfterDateAndRefusedForTheOthers() {
            assertThatThrownBy(() -> policy(ReviewVisibility.FULL, ReviewTiming.AFTER_DATE, null))
                .hasMessageContaining("needs the date");
            assertThatThrownBy(() -> policy(ReviewVisibility.FULL, ReviewTiming.IMMEDIATELY,
                clock.instant()))
                .hasMessageContaining("Only a review timed to a date has a date");
        }
    }

    // ---------------------------------------------------------------- the key

    @Nested
    class TheAnswerKey {

        /**
         * THE CRITERION, on the path the policy governs: no shape of request reaches a different
         * branch, because the permitted view is <em>built</em> from the policy rather than filtered
         * by it.
         */
        @Test
        void isAbsentFromTheReviewPayloadUnlessThePolicyPermitsIt() {
            policy(ReviewVisibility.SCORE_AND_WHICH_WRONG, ReviewTiming.IMMEDIATELY, null);
            Attempt attempt = sit("a", "b");

            assertThat(review(attempt).items()).allSatisfy(item -> {
                assertThat(item.body().has("answerKey")).isFalse();
                assertThat(item.body().has("feedback"))
                    .as("an explanation of why 'a' is right is an answer key in prose")
                    .isFalse();
                assertThat(item.body().has("stem"))
                    .as("and everything they were actually shown is still there")
                    .isTrue();
            });
        }

        // The other half of this criterion -- that the key is not in the ordinary read of a
        // question either -- is asserted in QuestionTest, over HTTP, where a learner would
        // actually meet it. It is NOT an authorization check: nothing here can tell an author
        // from a learner, because identity owns the grants and does not expose them to another
        // process (T-9.11). What it buys is that the key stops travelling by accident, and that
        // there is now exactly one endpoint for a permission to land on.
    }

    // ---------------------------------------------------------------- whose paper

    @Nested
    class WhoseReviewItIs {

        @Test
        void isNotSomebodyElsesToRead() {
            Attempt attempt = sit("a", "b");

            assertThatThrownBy(() -> inTenant(() -> review.of(attempt.getId(), SOMEBODY_ELSE)))
                .as("the same 404 as a missing one: \"it exists but is not yours\" is a fact "
                    + "about somebody else's exam (T-2.4)")
                .hasMessageContaining("No such attempt");
        }

        @Test
        void showsNothingBeyondTheScoreWhileAnEssayIsStillUnmarked() {
            policy(ReviewVisibility.FULL, ReviewTiming.IMMEDIATELY, null);
            inTenant(() -> {
                UUID bank = banks.create("Written", null).getId();
                UUID section = sections.addFixed(test, "Essay").getId();
                sections.questionsAre(section, List.of(questions.create(bank, "why",
                    JSON.readTree("{\"type\":\"essay\",\"stem\":\"Why?\"}")).getId()));
                return null;
            });
            Attempt attempt = sit("a", "b", "Because.");

            assertThat(review(attempt).visibility())
                .as("a provisional score is not a result (T-6.7), so there is nothing settled to "
                    + "review yet")
                .isEqualTo("SCORE_ONLY");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void policy(ReviewVisibility visibility, ReviewTiming timing, Instant openAt) {
        inTenant(() -> tests.reviewedAs(test, visibility, timing, openAt));
    }

    private ReviewService.Review review(Attempt attempt) {
        return inTenant(() -> review.of(attempt.getId(), LEARNER));
    }

    /** Sits the test, answering in form order, and submits. */
    private Attempt sit(String... answers) {
        AttemptService.Sitting sitting = inTenant(() -> attempts.startOrResume(test, LEARNER));
        List<FormItem> items = sitting.form().items();
        for (int index = 0; index < answers.length && index < items.size(); index++) {
            String answer = answers[index];
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

    private UUID choice(UUID bank, String name) {
        return questions.create(bank, name, JSON.readTree("""
            {"type":"single-choice","stem":"%s?",
             "options":{"choices":[{"id":"a","text":"A"},{"id":"b","text":"B"}]},
             "answerKey":{"correct":["a"]},
             "feedback":"Water conducts electricity."}""".formatted(name))).getId();
    }

    private <T> T inTenant(Supplier<T> body) {
        return TenantContext.callWithUnchecked(TENANT, body);
    }
}
