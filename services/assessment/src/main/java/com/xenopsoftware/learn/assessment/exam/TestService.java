package com.xenopsoftware.learn.assessment.exam;

import com.xenopsoftware.learn.assessment.scoring.ScoringMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authoring a test's scoring policy (T-6.4).
 *
 * <p>Small on purpose. What a test <em>asks</em> is T-6.5's, what a learner <em>does</em> with it is
 * T-6.6's, and this is only the part that decides what the resulting number means. The arithmetic
 * itself is not here at all — it is {@code Scores}, static and stateless, so that "what did they
 * score" has one answer that does not depend on a service being wired up.
 *
 * <p>The permission story is {@code BankService}'s, unchanged and for the same reason: the
 * evaluator and its grants live in {@code identity} and a separate process cannot ask it anything
 * (T-9.11, ADR-0109). There is deliberately no local shortcut here either.
 */
@Service
@Transactional
public class TestService {

    private final TestRepository tests;

    public TestService(TestRepository tests) {
        this.tests = tests;
    }

    @Transactional(readOnly = true)
    public List<TestDefinition> list() {
        return tests.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public TestDefinition get(UUID id) {
        return tests.findById(id).orElseThrow(TestNotFound::new);
    }

    /**
     * @param passMarkPercent required rather than defaulted. A test that quietly passes everybody
     *                        because nobody chose a pass mark is the failure the column exists to
     *                        make visible, and a default of zero would hide it behind a number that
     *                        looks deliberate
     */
    public TestDefinition create(String title, String description, int passMarkPercent) {
        if (title == null || title.isBlank()) {
            throw refused("A test has a title");
        }
        if (passMarkPercent < 0 || passMarkPercent > 100) {
            throw refused("A pass mark is a whole percent between 0 and 100, not "
                + passMarkPercent);
        }
        return tests.save(TestDefinition.called(title, description, passMarkPercent));
    }

    public TestDefinition rename(UUID id, String title, String description) {
        TestDefinition test = get(id);
        test.rename(title, description);
        return tests.save(test);
    }

    /** Changes what a result means. It does not rescore anything already sat — see the entity. */
    public TestDefinition scoredAs(UUID id, int passMarkPercent, boolean negativeMarking,
            BigDecimal points, ScoringMode mode, BigDecimal penalty) {
        TestDefinition test = get(id);
        try {
            test.scoredAs(passMarkPercent, negativeMarking, points, mode, penalty);
        } catch (IllegalArgumentException notALegalPolicy) {
            // The value types speak to an author -- "a question is worth more than nothing, or it
            // is not in the test" -- and this turns that into a status code without rewording it,
            // the same way QuestionTypes does for a type definition's refusal (T-6.3). Rewording
            // would put a second, worse sentence between the author and the rule.
            throw refused(notALegalPolicy.getMessage());
        }
        return tests.save(test);
    }

    /** How it may be sat: how many attempts, and how long each one lasts (T-6.6). */
    public TestDefinition satAs(UUID id, Integer attemptsAllowed, java.time.Duration timeLimit) {
        TestDefinition test = get(id);
        try {
            test.satAs(attemptsAllowed, timeLimit);
        } catch (IllegalArgumentException notALegalPolicy) {
            throw refused(notALegalPolicy.getMessage());
        }
        return tests.save(test);
    }

    /**
     * What a learner may see back, and when (T-6.9).
     *
     * <p>The attempt limit is read here and passed in, because "after all attempts" on a test with
     * no limit means never — and the entity is the one place that refusal belongs, beside the other
     * things a policy cannot mean.
     */
    public TestDefinition reviewedAs(UUID id,
            com.xenopsoftware.learn.assessment.review.ReviewVisibility visibility,
            com.xenopsoftware.learn.assessment.review.ReviewTiming timing,
            java.time.Instant after) {
        TestDefinition test = get(id);
        try {
            test.reviewedAs(visibility, timing, after, test.getAttemptsAllowed());
        } catch (IllegalArgumentException notALegalPolicy) {
            throw refused(notALegalPolicy.getMessage());
        }
        return tests.save(test);
    }

    public void delete(UUID id) {
        tests.delete(get(id));
    }

    private static ResponseStatusException refused(String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
    }
}
