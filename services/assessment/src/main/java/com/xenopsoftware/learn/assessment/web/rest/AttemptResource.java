package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.attempt.Attempt;
import com.xenopsoftware.learn.assessment.attempt.AttemptResponses;
import com.xenopsoftware.learn.assessment.attempt.AttemptService;
import com.xenopsoftware.learn.assessment.form.FormItem;
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
    private final LearnerIdentity learners;

    public AttemptResource(AttemptService attempts, AttemptResponses responses,
            LearnerIdentity learners) {
        this.attempts = attempts;
        this.responses = responses;
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
                              Map<UUID, JsonNode> answers) {}

    /**
     * @param optionOrder the options in the order this learner was shown them (T-6.5). A review
     *                    screen and a resumed attempt both render from it rather than re-deriving
     */
    public record ItemView(UUID formItemId, int position, UUID sectionId, UUID questionVersionId,
                           Map<String, List<String>> optionOrder) {}

    public record AttemptView(UUID id, int attemptNumber, String state, Instant startedAt,
                              Instant expiresAt, Instant submittedAt) {}

    public record AnswerForm(JsonNode response) {}

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
            sitting.form().items().stream().map(AttemptResource::view).toList(),
            responses.of(attempt.getId()));
    }

    private static ItemView view(FormItem item) {
        return new ItemView(item.id(), item.position(), item.sectionId(),
            item.questionVersionId(), item.optionOrder());
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
