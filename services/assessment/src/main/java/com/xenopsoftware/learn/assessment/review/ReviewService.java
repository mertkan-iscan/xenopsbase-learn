package com.xenopsoftware.learn.assessment.review;

import com.xenopsoftware.learn.assessment.question.ServedQuestion;
import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.attempt.AttemptNotFound;
import com.xenopsoftware.learn.assessment.attempt.AttemptRepository;
import com.xenopsoftware.learn.assessment.attempt.AttemptResponses;
import com.xenopsoftware.learn.assessment.exam.TestDefinition;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.form.Form;
import com.xenopsoftware.learn.assessment.form.FormAssembler;
import com.xenopsoftware.learn.assessment.form.FormItem;
import com.xenopsoftware.learn.assessment.grading.Grading;
import com.xenopsoftware.learn.assessment.grading.GradingService;
import com.xenopsoftware.learn.assessment.grading.Marks;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a learner may see after submitting, and when (T-6.9).
 *
 * <h2>The redaction happens here, on the way out, and nowhere else</h2>
 *
 * <p>The criterion is "enforced server-side — the API does not return answer keys the policy does
 * not permit, <b>regardless of what the client asks for</b>". So the review view is <em>built</em>
 * from the policy rather than filtered by it: a field the policy does not permit is never put into
 * the response object at all, and there is no parameter, header or shape a client can send that
 * reaches a different branch.
 *
 * <p>That is a deliberate difference from "fetch everything, then remove what is not allowed",
 * which is the version that leaks the day somebody adds a field to the DTO and forgets the filter.
 *
 * <h2>The screen is reconstructed from the form, not from the test</h2>
 *
 * <p>Including the shuffled option order (T-6.5), because a review that showed the questions in the
 * test's order rather than this learner's would be showing them somebody else's paper. The form is
 * the record of what was served, and it is frozen.
 *
 * <h2>Feedback lives in the question version</h2>
 *
 * <p>Not in a table keyed by question: the version is frozen once served (ADR-0106), so an
 * explanation written there stays correct across edits, which is what the criterion asks for. An
 * author who improves the wording writes a new version, and the learner who already read the old
 * one still sees what they were shown.
 */
@Service
public class ReviewService {

    /** Why a review is not available, in a sentence a learner can act on. */
    public record NotYet(String reason) {}

    /**
     * One question, as this learner may see it back.
     *
     * @param body      the question as served, <b>with the answer key removed unless the policy
     *                  permits it</b>. Never the current version: they answered this one
     * @param correct   whether they got it right, or null when the policy does not say
     * @param awarded   what it earned, or null when the policy does not say
     * @param feedback  the author's explanation, or null when the policy does not permit it
     * @param optionOrder the order this learner was shown the options in (T-6.5)
     */
    public record ReviewedItem(int position, UUID formItemId, UUID questionVersionId, JsonNode body,
                               JsonNode response, Boolean correct, BigDecimal awarded,
                               BigDecimal points, String feedback, String graderComment,
                               Map<String, List<String>> optionOrder) {}

    /**
     * @param visibility what this policy permitted, echoed so a screen renders the right shape
     *                   rather than inferring it from which fields happen to be null
     */
    public record Review(UUID attemptId, UUID testId, int attemptNumber, String state,
                         String grading, BigDecimal scoreRaw, BigDecimal scoreScaled,
                         Integer scorePercent, Boolean passed, Instant submittedAt,
                         String visibility, List<ReviewedItem> items) {}

    private final AttemptRepository attempts;
    private final AttemptResponses answers;
    private final TestService tests;
    private final FormAssembler forms;
    private final GradingService grading;
    private final Clock clock;

    public ReviewService(AttemptRepository attempts, AttemptResponses answers, TestService tests,
            FormAssembler forms, GradingService grading, Clock clock) {
        this.attempts = attempts;
        this.answers = answers;
        this.tests = tests;
        this.forms = forms;
        this.grading = grading;
        this.clock = clock;
    }

    /**
     * The caller's own paper back, as far as the policy allows.
     *
     * @throws AttemptNotFound when there is no such attempt for this caller — the same answer as a
     *         missing one, because "it exists but is not yours" is a fact about somebody else's exam
     */
    @Transactional(readOnly = true)
    public Review of(UUID attemptId, UUID learnerId) {
        Attempt attempt = attempts.findById(attemptId).orElseThrow(AttemptNotFound::new);
        if (!attempt.getLearnerId().equals(learnerId)) {
            throw new AttemptNotFound();
        }
        TestDefinition test = tests.get(attempt.getTestId());
        ReviewVisibility permitted = permittedNow(attempt, test);

        List<ReviewedItem> items = permitted.atLeast(ReviewVisibility.SCORE_AND_WHICH_WRONG)
            ? itemsFor(attempt, permitted) : List.of();

        return new Review(attempt.getId(), attempt.getTestId(), attempt.getAttemptNumber(),
            attempt.getState().name(), attempt.getGrading().name(), attempt.getScoreRaw(),
            attempt.getScoreScaled(), attempt.getScorePercent(), attempt.getPassed(),
            attempt.getSubmittedAt(), permitted.name(), items);
    }

    /**
     * What the policy permits <em>at this moment</em>, for this learner.
     *
     * <p>The timing gate lowers the visibility rather than refusing the request: a learner whose
     * review has not opened yet still sees their score, because the score was never what the timing
     * was protecting.
     */
    ReviewVisibility permittedNow(Attempt attempt, TestDefinition test) {
        if (attempt.getGrading() != Grading.GRADED) {
            // Nothing is settled, so there is nothing to review beyond the fact of having sat it.
            // A provisional score is not a result (T-6.7).
            return ReviewVisibility.SCORE_ONLY;
        }
        if (test.getReviewVisibility() == ReviewVisibility.SCORE_ONLY) {
            return ReviewVisibility.SCORE_ONLY;
        }
        return open(attempt, test) ? test.getReviewVisibility() : ReviewVisibility.SCORE_ONLY;
    }

    private boolean open(Attempt attempt, TestDefinition test) {
        return switch (test.getReviewTiming()) {
            case IMMEDIATELY -> true;
            case AFTER_DATE -> test.getReviewAfter() != null
                && !clock.instant().isBefore(test.getReviewAfter());
            case AFTER_ALL_ATTEMPTS -> {
                Integer allowed = test.getAttemptsAllowed();
                // A test with no limit cannot reach this timing -- TestService refuses to set it --
                // so an unlimited test here is one whose limit was removed afterwards. Treating it
                // as closed is the safe reading and is what the author last asked for.
                yield allowed != null && attempts.countByLearnerIdAndTestId(
                    attempt.getLearnerId(), test.getId()) >= allowed;
            }
        };
    }

    private List<ReviewedItem> itemsFor(Attempt attempt, ReviewVisibility permitted) {
        Form form = forms.of(attempt.getId());
        Map<UUID, JsonNode> responses = answers.of(attempt.getId());
        Map<UUID, Marks.Mark> marks = new LinkedHashMap<>();
        grading.marksOf(attempt.getId()).forEach(mark -> marks.put(mark.formItemId(), mark));

        List<ReviewedItem> reviewed = new ArrayList<>();
        for (FormItem item : form.items()) {
            JsonNode served = answers.bodyOf(item.questionVersionId());
            Marks.Mark mark = marks.get(item.id());
            boolean full = permitted.atLeast(ReviewVisibility.FULL);

            reviewed.add(new ReviewedItem(item.position(), item.id(), item.questionVersionId(),
                withoutTheKeyUnless(served, full), responses.get(item.id()),
                mark == null ? null : rightOrWrong(mark, item),
                mark == null ? null : mark.awarded(), item.scoring().points(),
                full ? feedbackIn(served) : null,
                mark == null ? null : mark.comment(),
                item.optionOrder()));
        }
        return reviewed;
    }

    /**
     * Whether they got it right, from the mark rather than from the key.
     *
     * <p>Deliberately: "right" at {@code SCORE_AND_WHICH_WRONG} has to be answerable without the
     * answer key going anywhere near the response, and the mark already knows.
     */
    private static Boolean rightOrWrong(Marks.Mark mark, FormItem item) {
        if (mark.awarded() == null) {
            return null;
        }
        return mark.awarded().compareTo(item.scoring().points()) >= 0;
    }

    /**
     * The question as served, with the answer key stripped unless the policy permits it.
     *
     * <p>A copy with the field removed, not a flag telling a renderer to hide it. A key that
     * travels and is hidden is a key in the learner's browser, in their network tab, and in
     * whatever caches it on the way.
     */
    private static JsonNode withoutTheKeyUnless(JsonNode served, boolean full) {
        return full ? served : ServedQuestion.withoutTheKey(served);
    }

    private static String feedbackIn(JsonNode served) {
        JsonNode feedback = served.get("feedback");
        return feedback == null || !feedback.isTextual() ? null : feedback.asString();
    }
}
