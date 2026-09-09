package com.xenopsoftware.learn.assessment.attempt;

import com.xenopsoftware.learn.assessment.exam.TestDefinition;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.form.Form;
import com.xenopsoftware.learn.assessment.form.FormAssembler;
import com.xenopsoftware.learn.assessment.form.FormItem;
import com.xenopsoftware.learn.assessment.grading.GradingService;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Start, answer, submit — with the clock on the server (T-6.6).
 *
 * <h2>The three races, and what settles each of them</h2>
 *
 * <p>None of them is settled by a lock or by looking before writing. Each is a single statement the
 * database arbitrates, because "check, then act" has a window in every one of these cases and the
 * window is exactly where two tabs live.
 *
 * <ul>
 *   <li><b>Two tabs starting the last attempt.</b> A partial unique index allows one
 *       {@code IN_PROGRESS} attempt per learner per test. Both tabs insert; one loses and then
 *       reads the winner's row and resumes it — which is what the person in front of both tabs
 *       wanted.
 *   <li><b>Two saves of one answer.</b> An upsert on {@code (attempt, form item)}. Idempotent by
 *       the shape of the row rather than by a key, which also gets right the case a replayed
 *       response gets wrong: a learner who changes their answer and whose request is retried must
 *       end up with the new answer.
 *   <li><b>A double-clicked submit.</b> One conditional UPDATE off {@code IN_PROGRESS}. The row
 *       count says who won; the loser is answered with the attempt as it stands, not with an error,
 *       because from the learner's side both clicks meant the same thing.
 * </ul>
 *
 * <h2>Why the deadline is enforced in two different ways</h2>
 *
 * <p><b>Saving is refused after it; submitting is not.</b> Nothing may be written after the clock
 * runs out — that is the rule the whole task exists for. But a submit that arrives a minute late is
 * still accepted and the attempt is still marked, because by construction nothing in it was written
 * late. Refusing it would throw away work that was done in time, to punish a slow network for a
 * rule about time to think.
 */
@Service
public class AttemptService {

    private final AttemptRepository attempts;
    private final AttemptResponses responses;
    private final TestService tests;
    private final FormAssembler forms;
    private final QuestionTypes types;
    private final ObjectProvider<GradingService> grading;
    private final Clock clock;

    /**
     * Grading arrives through an {@link ObjectProvider}, which is worth a sentence.
     *
     * <p>{@code GradingService} needs this service's answers and forms, and this service needs
     * grading on submit — a cycle Spring would refuse at startup. The lazy lookup breaks it at the
     * one place the dependency is genuinely one-directional: submitting is what causes marking, and
     * marking never causes a submission.
     */
    public AttemptService(AttemptRepository attempts, AttemptResponses responses,
            TestService tests, FormAssembler forms, QuestionTypes types,
            ObjectProvider<GradingService> grading, Clock clock) {
        this.attempts = attempts;
        this.responses = responses;
        this.tests = tests;
        this.forms = forms;
        this.types = types;
        this.grading = grading;
        this.clock = clock;
    }

    /** An attempt and the form it was assembled with — everything a player needs on load. */
    public record Sitting(Attempt attempt, Form form, Duration remaining) {}

    /**
     * Starts a new attempt, or resumes the one already in progress.
     *
     * <p>One method for both, deliberately: a client that has to ask "am I part-way through this"
     * before deciding which endpoint to call has a race of its own, and the answer it gets can be
     * stale by the time it acts on it. Opening a test is one request whatever happened before.
     *
     * <p><b>Resuming does not move the deadline</b>, which is the rule this task states once and
     * keeps. See {@link Attempt} for why the alternative cannot be enforced.
     */
    @Transactional
    public Sitting startOrResume(UUID testId, UUID learnerId) {
        TestDefinition test = tests.get(testId);
        Instant now = clock.instant();

        Optional<Attempt> live = attempts.findByLearnerIdAndTestIdAndState(
            learnerId, test.getId(), Attempt.State.IN_PROGRESS);
        if (live.isPresent()) {
            Attempt existing = live.get();
            if (!existing.isPastDeadline(now)) {
                return sitting(existing, now);
            }
            // Their time ran out while they were away. Ending it here rather than waiting for the
            // reaper means the learner is told the truth on the screen where they would otherwise
            // see a live attempt with no time left on it.
            end(existing, Attempt.State.EXPIRED, now);
            return sitting(reload(existing.getId()), now);
        }

        int sat = attempts.countByLearnerIdAndTestId(learnerId, test.getId());
        Integer allowed = test.getAttemptsAllowed();
        if (allowed != null && sat >= allowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This test allows " + allowed + " attempt" + (allowed == 1 ? "" : "s")
                + " and you have used " + sat + ".");
        }

        Attempt attempt;
        try {
            attempt = attempts.saveAndFlush(Attempt.starting(test.getId(), learnerId, sat + 1,
                test.getTimeLimit(), now));
        } catch (DataIntegrityViolationException anotherTabWonTheRace) {
            // Either the partial unique index (another tab started one a moment ago) or the
            // attempt-number key. Both mean the same thing to this caller: somebody else's insert
            // is the one that counts, and the person in front of both tabs wants that one.
            return attempts.findByLearnerIdAndTestIdAndState(
                    learnerId, test.getId(), Attempt.State.IN_PROGRESS)
                .map(won -> sitting(won, now))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another attempt at this test was started at the same moment. Try again.",
                    anotherTabWonTheRace));
        }

        // The form is assembled once, here, and is then the record of what this person was asked
        // (T-6.5). In the same transaction as the attempt, so a failed draw -- an insufficient
        // pool -- leaves no attempt behind that could never be sat.
        forms.assemble(test.getId(), attempt.getId());
        return sitting(attempt, now);
    }

    /** Where this learner is, without starting anything. */
    @Transactional(readOnly = true)
    public Sitting of(UUID attemptId, UUID learnerId) {
        return sitting(mine(attemptId, learnerId), clock.instant());
    }

    /** Every attempt this learner has made at this test, oldest first. */
    @Transactional(readOnly = true)
    public List<Attempt> history(UUID testId, UUID learnerId) {
        return attempts.findByLearnerIdAndTestIdOrderByAttemptNumberAsc(
            learnerId, tests.get(testId).getId());
    }

    /**
     * Saves one answer, as it is given.
     *
     * <p>Refused once the clock has run out, which is the half of the deadline that has to hold:
     * nothing may be written after time. Refused too once the attempt is over, because an answer to
     * a submitted attempt is either a confused client or somebody trying their luck.
     *
     * <p>The response is validated against the options this learner was actually served (T-6.3's
     * {@code validateResponse}, which has had no caller until now) rather than against the current
     * question — a version edited since is a different question, and the learner answered this one.
     */
    @Transactional
    public void answer(UUID attemptId, UUID learnerId, UUID formItemId, JsonNode response) {
        Attempt attempt = mine(attemptId, learnerId);
        Instant now = clock.instant();

        if (attempt.getState() != Attempt.State.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This attempt is " + attempt.getState().name().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ') + " and cannot take another answer.");
        }
        if (attempt.isPastDeadline(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Time is up for this attempt. Everything saved before the deadline still counts.");
        }

        Form form = forms.of(attemptId);
        FormItem item = form.items().stream()
            .filter(candidate -> candidate.id().equals(formItemId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This attempt was not asked that question."));

        types.validateResponse(responses.bodyOf(item.questionVersionId()), response);
        responses.save(attemptId, item, response, now);
    }

    /**
     * Ends the attempt. Twice is once.
     *
     * <p>A second submit is answered with the attempt as it stands rather than with an error: from
     * the learner's side both clicks meant the same thing, and telling them the second one failed
     * would make them wonder whether the first one did.
     */
    @Transactional
    public Sitting submit(UUID attemptId, UUID learnerId) {
        Attempt attempt = mine(attemptId, learnerId);
        Instant now = clock.instant();

        if (attempt.getState() == Attempt.State.IN_PROGRESS) {
            // EXPIRED rather than SUBMITTED when the clock ran out, and graded either way: the
            // save path refused anything after the deadline, so nothing in here was written late.
            if (end(attempt, attempt.isPastDeadline(now)
                    ? Attempt.State.EXPIRED : Attempt.State.SUBMITTED, now) == 1) {
                // INSIDE THIS TRANSACTION, which the criterion asks for and the outbox needs
                // (T-6.7): a verdict that committed while the submission rolled back would be a
                // mark for an attempt nobody made, and a submission that committed without its
                // verdict would leave an attempt nothing will ever mark, because only this branch
                // calls grading.
                grading.getObject().gradeOnSubmit(reload(attemptId));
            }
        }
        return sitting(reload(attemptId), now);
    }

    // ---------------------------------------------------------------- internals

    /** Ends an attempt through the conditional update, so concurrent enders cannot both win. */
    int end(Attempt attempt, Attempt.State terminal, Instant now) {
        return attempts.end(attempt.getId(), Attempt.State.IN_PROGRESS, terminal, now);
    }

    private Attempt mine(UUID attemptId, UUID learnerId) {
        Attempt attempt = attempts.findById(attemptId).orElseThrow(AttemptNotFound::new);
        if (!attempt.getLearnerId().equals(learnerId)) {
            // The same 404 as a missing one. "It exists but is not yours" is a fact about somebody
            // else's exam, and the disclosure rule (T-2.4) says a caller does not learn it.
            throw new AttemptNotFound();
        }
        return attempt;
    }

    private Attempt reload(UUID attemptId) {
        return attempts.findById(attemptId).orElseThrow(AttemptNotFound::new);
    }

    private Sitting sitting(Attempt attempt, Instant now) {
        return new Sitting(attempt, forms.of(attempt.getId()), attempt.remaining(now));
    }
}
