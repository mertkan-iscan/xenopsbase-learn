package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.question.ServedQuestion;
import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.attempt.AttemptResponses;
import com.xenopsoftware.learn.assessment.attempt.AttemptService;
import com.xenopsoftware.learn.assessment.form.FormItem;
import com.xenopsoftware.learn.assessment.integrity.IntegrityService;
import com.xenopsoftware.learn.assessment.integrity.IntegritySignal;
import com.xenopsoftware.learn.assessment.integrity.MonitoringDisclosure;
import com.xenopsoftware.learn.assessment.review.ReviewService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Sitting a test (T-6.6).
 *
 * <h2>Everything is under {@code /me/} and nothing takes a learner id</h2>
 *
 * <p>The reason the playback token and the home screen give: the answer is only ever about the
 * caller, and an endpoint that took an id would be one refactor away from being one that answers
 * about somebody else. "Submit this attempt for that person" is the same hole as "submit mine" with
 * a much wider blast radius.
 *
 * <h2>One verb opens a test, whether or not one is already open</h2>
 *
 * <p>{@code POST /me/tests/{id}/attempts} starts a new attempt or resumes the one in progress. A
 * client that had to ask which first would have a race of its own, and the answer could be stale by
 * the time it acted on it.
 *
 * <p><b>The clock is reported, never accepted.</b> {@code expiresAt} and {@code secondsRemaining}
 * go out; nothing comes back in. There is no request shape anywhere here that could extend a
 * deadline, which is the point of the task.
 */
@RestController
@RequestMapping("/api/v1/me")
public class AttemptResource {

    private final AttemptService attempts;
    private final AttemptResponses responses;
    private final IntegrityService integrity;
    private final MonitoringDisclosure disclosure;
    private final ReviewService review;
    private final LearnerIdentity learners;

    public AttemptResource(AttemptService attempts, AttemptResponses responses,
            IntegrityService integrity, MonitoringDisclosure disclosure, ReviewService review,
            LearnerIdentity learners) {
        this.attempts = attempts;
        this.responses = responses;
        this.integrity = integrity;
        this.disclosure = disclosure;
        this.review = review;
        this.learners = learners;
    }

    /**
     * @param secondsRemaining what to draw a countdown from, or null when the test is untimed.
     *                         <b>A courtesy, not the rule</b> — the server refuses a late answer
     *                         whatever a client's timer says
     * @param answers          what has been saved so far, by form item, so a resumed attempt
     *                         renders where the learner left off
     */
    public record SittingView(UUID attemptId, UUID testId, int attemptNumber, String state,
                              Instant startedAt, Instant expiresAt, Long secondsRemaining,
                              Instant submittedAt, List<ItemView> items,
                              Map<UUID, JsonNode> answers, MonitoringView monitoring) {}

    /**
     * @param optionOrder the options in the order this learner was shown them (T-6.5). A review
     *                    screen and a resumed attempt both render from it rather than re-deriving
     * @param body        the question as this learner was served it, <b>without the answer key</b>.
     *                    Carried here because it was not obtainable anywhere else: a form records
     *                    the VERSION and never the question (ADR-0106, and {@code FormItem} says
     *                    why), while every endpoint that dereferences a body needs the question id
     *                    as well — so an attempt in progress named a version nothing could resolve
     *                    and a learner could not be shown the question at all. {@code Review}
     *                    already carries the body for the same reason after submission; this is
     *                    the same answer before it
     */
    public record ItemView(UUID formItemId, int position, UUID sectionId, UUID questionVersionId,
                           Map<String, List<String>> optionOrder, JsonNode body) {}

    public record AttemptView(UUID id, int attemptNumber, String state, Instant startedAt,
                              Instant expiresAt, Instant submittedAt) {}

    public record AnswerForm(JsonNode response) {}

    /**
     * One integrity signal, reported by the learner's own browser (T-6.8).
     *
     * @param kind       one of the closed set the disclosure lists. Anything else is refused,
     *                   because a free-text kind is a way to start collecting something nobody
     *                   agreed to
     * @param reportedAt the browser's clock. Kept for the order of a burst and for nothing else —
     *                   it belongs to the learner and can say anything
     * @param detail     what the signal carries: how long focus was away, how much was pasted.
     *                   <b>Never the pasted text.</b> Collecting that would be collecting their
     *                   answer twice, once as an answer and once as surveillance
     */
    public record SignalForm(String kind, Instant reportedAt, JsonNode detail) {}

    /**
     * What the learner is told before they start (T-6.8).
     *
     * <p>Returned by its own endpoint <b>and</b> included in the response that starts an attempt, so
     * a player has been handed it before it can render a question. A server cannot make a client
     * display anything; it can make the disclosure impossible to miss.
     */
    public record MonitoringView(List<String> collects, String usedFor, String neverUsedFor,
                                 long keptForDays) {}

    /** Starts an attempt, or resumes the one already open. */
    @PostMapping("/tests/{testId}/attempts")
    @ApiResponse(responseCode = "200",
        description = "The attempt and the form assembled for it. A second call while one is open "
            + "resumes it rather than starting another, and does not move the deadline.")
    @ApiResponse(responseCode = "409",
        description = "Every allowed attempt has been used, or this test has no sections to sit, "
            + "or a section's pool cannot fill the form it asks for (T-6.5).",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404", description = "No such test in this company.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public SittingView start(@PathVariable UUID testId) {
        return view(attempts.startOrResume(testId, caller()));
    }

    /** Every attempt this learner has made at this test. */
    @GetMapping("/tests/{testId}/attempts")
    public List<AttemptView> history(@PathVariable UUID testId) {
        return attempts.history(testId, caller()).stream().map(AttemptResource::view).toList();
    }

    /** Where this learner is, without starting anything. */
    @GetMapping("/attempts/{id}")
    @ApiResponse(responseCode = "200", description = "The attempt, its form and what is saved.")
    @ApiResponse(responseCode = "404",
        description = "No such attempt, or it is not this caller's — the same answer either way "
            + "(T-2.4's disclosure rule).",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public SittingView one(@PathVariable UUID id) {
        return view(attempts.of(id, caller()));
    }

    /**
     * Saves one answer.
     *
     * <p>A PUT on the item rather than a POST with an idempotency key, and that is the whole
     * idempotence story: saving the same answer twice writes the same row, and saving a different
     * one writes the new one. A replayed response would hand back the old answer and hide the
     * change — which is the case that actually happens when a learner changes their mind and the
     * request is retried.
     */
    @PutMapping("/attempts/{id}/answers/{formItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Saved. Repeating it is free.")
    @ApiResponse(responseCode = "400",
        description = "The response does not fit the question this learner was served (T-6.3).",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "409",
        description = "Time is up, or the attempt is already over. Everything saved before the "
            + "deadline still counts.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404",
        description = "No such attempt for this caller, or it was never asked that question.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public void answer(@PathVariable UUID id, @PathVariable UUID formItemId,
            @RequestBody AnswerForm form) {
        if (form == null || form.response() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An answer has a response. To clear one, send its type's empty shape -- an empty "
                + "list is an unanswered question, not a malformed request (T-6.3).");
        }
        attempts.answer(id, caller(), formItemId, form.response());
    }

    /**
     * What is collected during an attempt, and what is never done with it (T-6.8).
     *
     * <p>Its own endpoint so a "before you start" screen can show it without starting anything.
     * The same content also rides on the response that starts an attempt.
     */
    @GetMapping("/monitoring")
    @ApiResponse(responseCode = "200",
        description = "The integrity signals this platform records during an attempt, what a "
            + "person may do with them, what nothing does with them, and how long they are kept. "
            + "Generated from the same values the recorder accepts, so a signal cannot be "
            + "collected without appearing here.")
    public MonitoringView monitoring() {
        return view(disclosure.forLearner());
    }

    /**
     * Records one integrity signal.
     *
     * <p>Answers 202 whether or not it was kept: an attempt that is over records nothing further,
     * and one that has produced more than the cap allows drops the rest. Neither is the learner's
     * fault and neither is worth an error — a refusal would make a player retry, which is the
     * opposite of what a flood needs.
     *
     * <p><b>Nothing reads these while marking.</b> No signal here can fail a learner, reduce their
     * mark or end their attempt, and an ArchUnit rule keeps the grading path unable to reach them.
     */
    @PostMapping("/attempts/{id}/signals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "202",
        description = "Taken. Also the answer when it was dropped, which the client does not need "
            + "to distinguish.")
    @ApiResponse(responseCode = "400", description = "Not one of the disclosed kinds.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404", description = "No such attempt for this caller.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public void signal(@PathVariable UUID id, @RequestBody SignalForm form) {
        if (form == null || form.kind() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A signal has a kind.");
        }
        integrity.record(id, caller(), kind(form.kind()), form.reportedAt(), form.detail());
    }

    /**
     * The caller's own paper back, as far as the test's review policy allows (T-6.9).
     *
     * <p><b>Built from the policy, not filtered by it.</b> A field the policy does not permit is
     * never put into the response at all: there is no parameter, header or body shape a client can
     * send that reaches a different branch, which is what "regardless of what the client asks for"
     * has to mean to be worth anything.
     *
     * <p>Reconstructed from the form (T-6.5), including the option order this learner was shown, so
     * a review screen renders their paper rather than somebody else's.
     */
    @GetMapping("/attempts/{id}/review")
    @ApiResponse(responseCode = "200",
        description = "The result, and as much of the paper as the policy permits. `visibility` "
            + "says which — a screen renders the shape it names rather than inferring one from "
            + "which fields happen to be null. Answer keys and feedback are absent from the "
            + "payload unless the policy is FULL and its timing has opened.")
    @ApiResponse(responseCode = "404",
        description = "No such attempt, or it is not this caller's — the same answer either way.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public ReviewService.Review review(@PathVariable UUID id) {
        return review.of(id, caller());
    }

    /**
     * Ends the attempt. Twice is once.
     *
     * <p>A second submit answers with the attempt as it stands rather than an error: from the
     * learner's side both clicks meant the same thing, and telling them the second failed would
     * make them wonder about the first.
     */
    @PostMapping("/attempts/{id}/submit")
    @ApiResponse(responseCode = "200",
        description = "The attempt, ended. Submitting again returns the same thing and changes "
            + "nothing. A submit after the deadline is still accepted and the attempt is marked "
            + "EXPIRED — nothing in it was written late, because the save path refuses that.")
    @ApiResponse(responseCode = "404", description = "No such attempt for this caller.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public SittingView submit(@PathVariable UUID id) {
        return view(attempts.submit(id, caller()));
    }

    // ---------------------------------------------------------------- views

    private SittingView view(AttemptService.Sitting sitting) {
        Attempt attempt = sitting.attempt();
        return new SittingView(attempt.getId(), attempt.getTestId(), attempt.getAttemptNumber(),
            attempt.getState().name(), attempt.getStartedAt(), attempt.getExpiresAt(),
            sitting.remaining() == null ? null : sitting.remaining().toSeconds(),
            attempt.getSubmittedAt(),
            sitting.form().items().stream().map(this::view).toList(),
            responses.of(attempt.getId()), view(disclosure.forLearner()));
    }

    private static MonitoringView view(MonitoringDisclosure.Disclosure disclosure) {
        return new MonitoringView(disclosure.collects(), disclosure.usedFor(),
            disclosure.neverUsedFor(), disclosure.keptForDays());
    }

    private static IntegritySignal kind(String name) {
        try {
            return IntegritySignal.valueOf(name);
        } catch (IllegalArgumentException notDisclosed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "There is no signal '" + name + "'. The kinds this platform records are the ones "
                + "GET /api/v1/me/monitoring discloses, and a kind that is not disclosed is one "
                + "nobody agreed to.", notDisclosed);
        }
    }

    private ItemView view(FormItem item) {
        return new ItemView(item.id(), item.position(), item.sectionId(),
            item.questionVersionId(), item.optionOrder(),
            ServedQuestion.withoutTheKey(responses.bodyOf(item.questionVersionId())));
    }

    private static AttemptView view(Attempt attempt) {
        return new AttemptView(attempt.getId(), attempt.getAttemptNumber(),
            attempt.getState().name(), attempt.getStartedAt(), attempt.getExpiresAt(),
            attempt.getSubmittedAt());
    }

    private UUID caller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // Everything under /api is authenticated by the security chain, so reaching here
            // without a JWT is a wiring mistake rather than a caller error.
            throw new IllegalStateException("An attempt is only ever about a verified caller");
        }
        return learners.current(TenantContext.require(), token.getToken().getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                // Not a 500 and not a 404: the request is fine, one dependency is not, and a
                // learner in front of an exam needs "try again" rather than "you do not exist".
                "This cannot be answered right now."));
    }
}
