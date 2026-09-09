package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.grading.GradeEvents;
import com.xenopsoftware.learn.assessment.grading.GradingService;
import com.xenopsoftware.learn.assessment.grading.MarkingQueue;
import com.xenopsoftware.learn.assessment.grading.Marks;
import com.xenopsoftware.learn.assessment.grading.Rubrics;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The marking queue, and marking what is in it (T-6.7).
 *
 * <h2>The permission this is not scoped by</h2>
 *
 * <p>The criterion asks for a queue "scoped by permission". It is scoped by tenant and by nothing
 * else — the same gap every endpoint in this module carries, for the reason {@code BankService}
 * states: the evaluator and its grants live inside {@code identity} and a separate process cannot
 * ask it anything (T-9.11, ADR-0109). A local "is this person a grader" check written here would be
 * exactly the special case ADR-0103 refuses, and it would be the thing an endpoint later trusts.
 *
 * <p>So the shape is right and the check is absent, deliberately and visibly. Every authenticated
 * member of a company can currently read what it owes marking on and mark it.
 *
 * <h2>Who marked it is not optional</h2>
 *
 * <p>The grader comes from the token, never from the body. "Grading is audited with the grader" is
 * worth nothing if a client can name somebody else as the grader, and a field for it would be the
 * one somebody eventually fills in from a dropdown.
 */
@RestController
@RequestMapping("/api/v1/grading")
public class GradingResource {

    private final GradingService grading;
    private final MarkingQueue queue;
    private final Rubrics rubrics;
    private final GradeEvents events;
    private final LearnerIdentity graders;

    public GradingResource(GradingService grading, MarkingQueue queue, Rubrics rubrics,
            GradeEvents events, LearnerIdentity graders) {
        this.grading = grading;
        this.queue = queue;
        this.rubrics = rubrics;
        this.events = events;
        this.graders = graders;
    }

    /**
     * @param waitingSeconds how long since the learner finished. The number the queue exists to
     *                       show — measured from their submission, not from when somebody noticed
     */
    public record WaitingView(UUID attemptId, UUID testId, String testTitle, UUID learnerId,
                              int attemptNumber, Instant submittedAt, long waitingSeconds,
                              int outstanding) {}

    public record MarkView(UUID responseId, UUID formItemId, UUID questionVersionId,
                           BigDecimal awarded, Integer credited, Integer available, boolean graded,
                           UUID gradedBy, String comment, Map<UUID, BigDecimal> criterionMarks) {}

    /**
     * @param criterionMarks required when the question has a rubric, refused when it has none
     * @param note           why, for the audit. A regrade with no reason is one nobody can defend
     */
    public record MarkForm(BigDecimal awarded, String comment, Map<UUID, BigDecimal> criterionMarks,
                           String note) {}

    public record CriterionForm(String name, BigDecimal maxPoints, Integer ordinal) {}

    public record CriterionView(UUID id, String name, BigDecimal maxPoints, int ordinal) {}

    public record EventView(UUID id, UUID gradedBy, String grading, BigDecimal scoreRaw,
                            BigDecimal scoreScaled, Integer scorePercent, Boolean passed,
                            String note, Instant at) {}

    public record AttemptGradingView(UUID attemptId, String state, String grading,
                                     BigDecimal scoreRaw, BigDecimal scoreScaled,
                                     Integer scorePercent, Boolean passed, Instant gradedAt) {}

    /** What this company owes marking on, oldest first. */
    @GetMapping("/queue")
    @ApiResponse(responseCode = "200",
        description = "Attempts waiting for a person, oldest first, with how long each has waited.")
    public List<WaitingView> waiting(@RequestParam(required = false) UUID testId,
            @RequestParam(defaultValue = "50") int limit) {
        return queue.waiting(testId, Math.clamp(limit, 1, 200)).stream()
            .map(waiting -> new WaitingView(waiting.attemptId(), waiting.testId(),
                waiting.testTitle(), waiting.learnerId(), waiting.attemptNumber(),
                waiting.submittedAt(), waiting.waiting().toSeconds(), waiting.outstanding()))
            .toList();
    }

    /** How many are waiting — the number a dashboard puts on a tile. */
    @GetMapping("/queue/depth")
    public Map<String, Integer> depth() {
        return Map.of("waiting", queue.depth());
    }

    /** What each answer earned, and who said so. */
    @GetMapping("/attempts/{attemptId}/marks")
    public List<MarkView> marks(@PathVariable UUID attemptId) {
        return grading.marksOf(attemptId).stream().map(this::view).toList();
    }

    /**
     * Marks one answer, and re-decides the attempt.
     *
     * <p>Re-deciding here rather than in a second request is what makes the last essay in a queue
     * settle the attempt: mark it, the recompute finds nothing outstanding, and the learner is told.
     * A separate "finish grading" call would be a step somebody forgets, leaving an attempt marked
     * in every part and settled in none.
     */
    @PostMapping("/attempts/{attemptId}/answers/{responseId}")
    @ApiResponse(responseCode = "200",
        description = "The attempt as it now stands. It settles when nothing is left outstanding.")
    @ApiResponse(responseCode = "400",
        description = "A mark that does not fit the rubric, exceeds what the question is worth, or "
            + "is negative.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "409", description = "The attempt is still being sat.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404",
        description = "No such attempt in this company, or it has no such answer.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public AttemptGradingView mark(@PathVariable UUID attemptId, @PathVariable UUID responseId,
            @RequestBody MarkForm form) {
        Attempt attempt = grading.mark(attemptId, responseId,
            form == null ? null : form.awarded(),
            form == null ? null : form.comment(),
            form == null ? Map.of() : form.criterionMarks(),
            grader(),
            form == null ? null : form.note());
        return view(attempt);
    }

    /** Every verdict ever reached about this attempt, newest first. A regrade adds; it never edits. */
    @GetMapping("/attempts/{attemptId}/history")
    public List<EventView> history(@PathVariable UUID attemptId) {
        return events.of(attemptId).stream()
            .map(event -> new EventView(event.id(), event.gradedBy(), event.grading().name(),
                event.raw(), event.scaled(), event.percent(), event.passed(), event.note(),
                event.at()))
            .toList();
    }

    @GetMapping("/questions/{questionId}/rubric")
    public List<CriterionView> rubric(@PathVariable UUID questionId) {
        return rubrics.of(questionId).stream()
            .map(criterion -> new CriterionView(criterion.id(), criterion.name(),
                criterion.maxPoints(), criterion.ordinal()))
            .toList();
    }

    @PostMapping("/questions/{questionId}/rubric")
    @ResponseStatus(HttpStatus.CREATED)
    public CriterionView addCriterion(@PathVariable UUID questionId,
            @RequestBody CriterionForm form) {
        Rubrics.Criterion criterion = rubrics.add(questionId, form.name(), form.maxPoints(),
            form.ordinal() == null ? rubrics.of(questionId).size() : form.ordinal());
        return new CriterionView(criterion.id(), criterion.name(), criterion.maxPoints(),
            criterion.ordinal());
    }

    private MarkView view(Marks.Mark mark) {
        return new MarkView(mark.responseId(), mark.formItemId(), mark.questionVersionId(),
            mark.awarded(), mark.credited(), mark.available(), mark.graded(), mark.gradedBy(),
            mark.comment(), Map.of());
    }

    private static AttemptGradingView view(Attempt attempt) {
        return new AttemptGradingView(attempt.getId(), attempt.getState().name(),
            attempt.getGrading().name(), attempt.getScoreRaw(), attempt.getScoreScaled(),
            attempt.getScorePercent(), attempt.getPassed(), attempt.getGradedAt());
    }

    /** The grader, from the token and never from the body. */
    private UUID grader() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("A mark is only ever made by a verified caller");
        }
        return graders.current(TenantContext.require(), token.getToken().getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "This cannot be answered right now."));
    }
}
