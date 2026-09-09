package com.xenopsoftware.learn.assessment.grading;

import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.attempt.AttemptRepository;
import com.xenopsoftware.learn.assessment.attempt.AttemptResponses;
import com.xenopsoftware.learn.assessment.exam.SectionService;
import com.xenopsoftware.learn.assessment.exam.TestDefinition;
import com.xenopsoftware.learn.assessment.exam.TestSection;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.form.Form;
import com.xenopsoftware.learn.assessment.form.FormAssembler;
import com.xenopsoftware.learn.assessment.form.FormItem;
import com.xenopsoftware.learn.assessment.question.type.Correctness;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypeDefinition;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypes;
import com.xenopsoftware.learn.assessment.scoring.QuestionMark;
import com.xenopsoftware.learn.assessment.scoring.QuestionScoring;
import com.xenopsoftware.learn.assessment.scoring.Responses;
import com.xenopsoftware.learn.assessment.scoring.Scores;
import com.xenopsoftware.learn.assessment.scoring.SectionMark;
import com.xenopsoftware.learn.assessment.scoring.TestScore;
import com.xenopsoftware.learn.common.messaging.Outbox;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Marking: what a machine can do on submit, and what waits for a person (T-6.7).
 *
 * <h2>The third state, and why it is the whole task</h2>
 *
 * <p>An attempt containing one essay is <b>neither passed nor failed</b> until somebody reads it.
 * Without {@link Grading#AWAITING_GRADING} it would carry a null score, and a gate reading that as
 * false locks a learner out of a course they may well have passed.
 *
 * <p>Every arithmetic decision underneath that was already made in T-6.4, and this class does not
 * repeat any of it: an unmarked question is in neither total, so a ten-question attempt with one
 * essay is scored out of nine and comes back {@code provisional}. This class turns
 * {@link TestScore#provisional()} into a stored state and a verdict a gate can read.
 *
 * <h2>Which questions a machine can mark is the type's answer</h2>
 *
 * <p>Not a list kept here. {@code grade} returns empty for an essay and a file upload (T-6.3), and
 * an empty result means "a human has to look". T-6.3 built that seam saying so in as many words,
 * and this is the caller it was built for — so adding an eleventh human-marked type needs no change
 * to this file.
 *
 * <h2>Grading on submit runs inside the submitting transaction</h2>
 *
 * <p>The criterion asks for it, and the reason is the one the outbox already has: a verdict that
 * committed while the submission rolled back would be a mark for an attempt nobody made, and a
 * submission that committed without its verdict would leave an attempt that never gets marked
 * because nothing will ever call this again.
 */
@Service
public class GradingService {

    private static final Logger LOG = LoggerFactory.getLogger(GradingService.class);

    /** What catalog folds into a gate, and what a report reads (T-5.3, E7). */
    static final String GRADED_SUBJECT = "assessment.attempt.graded";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AttemptRepository attempts;
    private final AttemptResponses answers;
    private final Marks marks;
    private final Rubrics rubrics;
    private final GradeEvents events;
    private final TestService tests;
    private final SectionService sections;
    private final FormAssembler forms;
    private final QuestionTypes types;
    private final Outbox outbox;
    private final Clock clock;

    public GradingService(AttemptRepository attempts, AttemptResponses answers, Marks marks,
            Rubrics rubrics, GradeEvents events, TestService tests, SectionService sections,
            FormAssembler forms, QuestionTypes types, ObjectProvider<Outbox> outbox, Clock clock) {
        this.attempts = attempts;
        this.answers = answers;
        this.marks = marks;
        this.rubrics = rubrics;
        this.events = events;
        this.tests = tests;
        this.sections = sections;
        this.forms = forms;
        this.types = types;
        this.outbox = outbox.getIfAvailable();
        this.clock = clock;
        if (this.outbox == null) {
            LOG.warn("No outbox is configured, so a graded attempt is recorded here and announced "
                + "to nobody: catalog will not open the gate behind it (T-5.3) and the learner "
                + "will not be told. Set platform.outbox.enabled=true.");
        }
    }

    /**
     * Marks everything a machine can, on submit, and decides whether anybody has to look.
     *
     * <p>Called from inside {@code AttemptService.submit} and from the reaper's path, so an attempt
     * that ran out of time is marked from what was saved before the deadline — which is all there
     * can be, because the save path refuses anything later (T-6.6).
     */
    @Transactional
    public Attempt gradeOnSubmit(Attempt attempt) {
        return recompute(attempt, null, "Marked on submit", clock.instant());
    }

    /**
     * Records a person's mark for one answer, and re-decides the attempt.
     *
     * <p>Re-deciding here rather than in a second request is what makes the last essay in a queue
     * settle the attempt: a grader marks it, the recompute finds nothing outstanding, and the
     * learner is told. A separate "finish grading" call would be a step somebody forgets, leaving
     * an attempt marked in every part and settled in none.
     *
     * @param criterionMarks per-criterion awards when the question has a rubric. Required then,
     *                       refused when it has none — a breakdown against criteria nobody wrote is
     *                       a number with no meaning attached
     */
    @Transactional
    public Attempt mark(UUID attemptId, UUID responseId, BigDecimal awarded, String comment,
            Map<UUID, BigDecimal> criterionMarks, UUID graderId, String note) {
        Attempt attempt = attempts.findById(attemptId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No such attempt"));
        if (attempt.getState() == Attempt.State.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This attempt is still being sat. Marking it now would mark a moving target.");
        }
        if (graderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A mark is somebody's. Grading is audited with the grader (T-6.7).");
        }

        Marks.Mark answer = marks.of(attemptId).stream()
            .filter(candidate -> candidate.responseId().equals(responseId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This attempt has no such answer."));
        FormItem item = itemOf(forms.of(attemptId), answer.formItemId());

        BigDecimal total = rubrics.check(item.questionVersionId(), awarded, criterionMarks);
        if (total.compareTo(item.scoring().points()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This question is worth " + item.scoring().points() + " and the mark given is "
                + total + ". A grader may not award more than the item carries -- the section's "
                + "weighting is what decides how much it counts for (T-6.4).");
        }
        if (total.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A person's mark is not negative. Negative marking is a deterrent against guessing "
                + "and applies where a wrong answer had odds, which an essay does not (T-6.4).");
        }

        Instant now = clock.instant();
        marks.personMarked(responseId, total, graderId, comment, now);
        if (criterionMarks != null && !criterionMarks.isEmpty()) {
            marks.criterionMarksAre(responseId, criterionMarks);
        }
        return recompute(attempt, graderId, note, now);
    }

    /** What a learner was awarded, answer by answer — for a review screen and for a dispute. */
    @Transactional(readOnly = true)
    public List<Marks.Mark> marksOf(UUID attemptId) {
        return marks.of(attemptId);
    }

    // ---------------------------------------------------------------- the recompute

    /**
     * Marks what can be marked, composes the score, stores the verdict and announces it.
     *
     * <p>Idempotent in the way that matters: running it again over the same answers produces the
     * same verdict. A regrade is this method with a grader attached, and the previous verdict
     * survives in {@code grade_event} rather than being overwritten.
     */
    private Attempt recompute(Attempt attempt, UUID graderId, String note, Instant now) {
        TestDefinition test = tests.get(attempt.getTestId());
        Form form = forms.of(attempt.getId());
        Map<UUID, JsonNode> responses = answers.of(attempt.getId());
        Map<UUID, Marks.Mark> existing = new LinkedHashMap<>();
        marks.of(attempt.getId()).forEach(mark -> existing.put(mark.formItemId(), mark));

        Map<UUID, List<QuestionMark>> bySection = new LinkedHashMap<>();
        boolean anythingOutstanding = false;

        for (FormItem item : form.items()) {
            JsonNode response = responses.get(item.id());
            Marks.Mark mark = existing.get(item.id());
            QuestionScoring scoring =
                item.scoring().penalising(test.defaultScoring().penalty());

            if (response == null) {
                // Never answered. A machine can mark that: it is worth nothing, and it is not
                // waiting for anybody.
                bySection.computeIfAbsent(item.sectionId(), any -> new ArrayList<>())
                    .add(QuestionMark.of(BigDecimal.ZERO, scoring.points()));
                continue;
            }

            JsonNode body = answers.bodyOf(item.questionVersionId());
            Optional<Correctness> correctness = types.grade(body, response);

            if (correctness.isPresent()) {
                QuestionMark machine = Scores.award(correctness, Responses.wasAnswered(response),
                    scoring, guessable(body), test.isNegativeMarking());
                if (mark != null && mark.gradedBy() != null) {
                    // A person has overruled the machine on this one. Their mark stands: a
                    // recompute triggered by somebody else's essay must not quietly undo it.
                    bySection.computeIfAbsent(item.sectionId(), any -> new ArrayList<>())
                        .add(QuestionMark.of(mark.awarded(), scoring.points()));
                    continue;
                }
                // There is a response, so there is a row to mark: `mark` is non-null whenever
                // `response` is, both coming from the same attempt.
                marks.machineMarked(mark.responseId(), machine.awarded(),
                    correctness.get().credited(), correctness.get().available(), now);
                bySection.computeIfAbsent(item.sectionId(), any -> new ArrayList<>()).add(machine);
                continue;
            }

            // No machine can mark this one (T-6.3's grade returned empty).
            if (mark != null && mark.graded()) {
                bySection.computeIfAbsent(item.sectionId(), any -> new ArrayList<>())
                    .add(QuestionMark.of(mark.awarded(), scoring.points()));
            } else {
                anythingOutstanding = true;
                bySection.computeIfAbsent(item.sectionId(), any -> new ArrayList<>())
                    .add(QuestionMark.awaitingAPerson(scoring.points()));
            }
        }

        TestScore score = Scores.compose(sectionMarks(attempt.getTestId(), bySection),
            test.getPassMarkPercent());
        Grading grading = anythingOutstanding || score.provisional()
            ? Grading.AWAITING_GRADING : Grading.GRADED;

        Grading before = attempt.getGrading();
        attempt.graded(grading, score, now);
        Attempt saved = attempts.save(attempt);

        events.record(attempt.getId(), graderId, grading, score, note, now);
        announce(saved, score, before, now);
        return saved;
    }

    /**
     * The section marks, in the test's own section order and carrying each section's weight.
     *
     * <p>A section with nothing in this form contributes nothing and is not a section somebody
     * failed — {@link Scores#compose} excludes it, weight and all.
     */
    private List<SectionMark> sectionMarks(UUID testId, Map<UUID, List<QuestionMark>> bySection) {
        List<SectionMark> composed = new ArrayList<>();
        for (TestSection section : sections.of(testId)) {
            List<QuestionMark> inSection = bySection.get(section.getId());
            if (inSection == null) {
                continue;
            }
            composed.add(SectionMark.of(section.getTitle(), section.getWeight(), inSection));
        }
        return composed;
    }

    private boolean guessable(JsonNode body) {
        return types.find(body.get("type").asString())
            .map(QuestionTypeDefinition::guessable)
            .orElse(false);
    }

    private static FormItem itemOf(Form form, UUID formItemId) {
        return form.items().stream()
            .filter(candidate -> candidate.id().equals(formItemId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This attempt was not asked that question."));
    }

    /**
     * Tells the outside world, once, when there is something new to say.
     *
     * <p><b>Only on becoming settled</b>, and that is what "the learner is notified when a pending
     * attempt is finally graded" means here: an attempt marked and settled in one go announces
     * once, and an attempt that sat in the queue announces when the last essay is marked. A regrade
     * announces too, because a changed verdict is exactly the thing somebody needs to hear about.
     *
     * <p>In the same transaction as the verdict, so a gate cannot open for a mark that rolled back
     * and a mark cannot commit with nobody told (T-9.8).
     */
    private void announce(Attempt attempt, TestScore score, Grading before, Instant now) {
        if (outbox == null || !attempt.getGrading().settled()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", TenantContext.require());
        payload.put("attemptId", attempt.getId().toString());
        payload.put("testId", attempt.getTestId().toString());
        payload.put("learnerId", attempt.getLearnerId().toString());
        payload.put("attemptNumber", attempt.getAttemptNumber());
        payload.put("scoreRaw", score.raw());
        payload.put("scoreScaled", score.scaled());
        payload.put("scorePercent", score.percent());
        payload.put("passed", score.passed());
        // Whether anybody was waiting on this. The consumer decides what to say to the learner,
        // and "your exam has been marked" is only true of the second kind.
        payload.put("wasAwaitingAPerson", before == Grading.AWAITING_GRADING);
        payload.put("gradedAt", now.toString());
        outbox.publish(TenantContext.require(), GRADED_SUBJECT, "AttemptGraded",
            JSON.writeValueAsString(payload));
    }
}
