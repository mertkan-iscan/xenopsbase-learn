package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.exam.TestDefinition;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.review.ReviewTiming;
import com.xenopsoftware.learn.assessment.review.ReviewVisibility;
import com.xenopsoftware.learn.assessment.scoring.ScoringMode;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests, and the scoring policy that decides what their results mean (T-6.4).
 *
 * <p><b>The scoring policy is its own resource</b>, at {@code /tests/{id}/scoring}, rather than
 * fields on the test. Two reasons, and the second is the one that matters: it is a different
 * decision from "what is this test called", made by a different person at a different time; and it
 * is the thing a rescore will have to reference, so it needs a place a client can PUT and a diff can
 * point at. Renaming a test and changing its pass mark are not the same request.
 *
 * <p>The permission story is {@code BankResource}'s, unchanged (T-9.11, ADR-0109).
 */
@RestController
@RequestMapping("/api/v1/tests")
public class TestResource {

    private final TestService tests;

    public TestResource(TestService tests) {
        this.tests = tests;
    }

    /**
     * @param passMarkPercent a whole percent. It is the same number a learner is shown as their
     *                        result, which is what stops a screen ever saying "80%" beside "failed"
     */
    public record TestRequest(String title, String description, Integer passMarkPercent) {}

    /**
     * @param negativeMarking whether a wrong answer can cost marks at all. The question type still
     *                        has the last word: a penalty only ever applies where a wrong answer
     *                        could have been a guess
     * @param defaultPoints   what a question in this test is worth unless a section says otherwise
     * @param defaultMode     {@code ALL_OR_NOTHING} or {@code PARTIAL_CREDIT}
     * @param penaltyPoints   what an answered, wholly wrong response costs. Never charged to an
     *                        unanswered question
     */
    public record ScoringRequest(Integer passMarkPercent, Boolean negativeMarking,
                                 BigDecimal defaultPoints, String defaultMode,
                                 BigDecimal penaltyPoints) {}

    /**
     * @param attemptsAllowed null for unlimited, which is what a practice quiz means. A limit of
     *                        999 is a limit somebody eventually hits and cannot explain
     * @param timeLimitSeconds null for untimed, which means an attempt gets no deadline at all
     *                        rather than a very distant one
     */
    public record SittingForm(Integer attemptsAllowed, Integer timeLimitSeconds) {}

    /**
     * @param visibility SCORE_ONLY, SCORE_AND_WHICH_WRONG or FULL. The axis that carries the risk
     * @param timing     IMMEDIATELY, AFTER_ALL_ATTEMPTS or AFTER_DATE
     * @param openAt     required for AFTER_DATE, refused otherwise
     */
    public record ReviewForm(String visibility, String timing, Instant openAt) {}

    public record TestView(UUID id, String title, String description, int passMarkPercent,
                           boolean negativeMarking, BigDecimal defaultPoints, String defaultMode,
                           BigDecimal penaltyPoints, Integer attemptsAllowed,
                           Integer timeLimitSeconds, String reviewVisibility, String reviewTiming,
                           Instant reviewAfter, Instant updatedAt) {

        static TestView of(TestDefinition test) {
            return new TestView(test.getId(), test.getTitle(), test.getDescription(),
                test.getPassMarkPercent(), test.isNegativeMarking(),
                test.defaultScoring().points(), test.defaultScoring().mode().name(),
                test.defaultScoring().penalty(), test.getAttemptsAllowed(),
                test.getTimeLimit() == null ? null : (int) test.getTimeLimit().toSeconds(),
                test.getReviewVisibility().name(), test.getReviewTiming().name(),
                test.getReviewAfter(), test.getUpdatedAt());
        }
    }

    @GetMapping
    public List<TestView> all() {
        return tests.list().stream().map(TestView::of).toList();
    }

    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "The test and its scoring policy.")
    @ApiResponse(responseCode = "404", description = "No such test in this company.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public TestView one(@PathVariable UUID id) {
        return TestView.of(tests.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400",
        description = "A test needs a title and a pass mark; the pass mark is a whole percent.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public TestView create(@RequestBody TestRequest request) {
        return TestView.of(tests.create(request.title(), request.description(),
            required(request.passMarkPercent())));
    }

    @PutMapping("/{id}")
    public TestView rename(@PathVariable UUID id, @RequestBody TestRequest request) {
        return TestView.of(tests.rename(id, request.title(), request.description()));
    }

    /**
     * Replaces the whole scoring policy.
     *
     * <p>A PUT of all five values rather than a PATCH of some, because they are one decision: a
     * penalty with negative marking off does nothing, and negative marking with no penalty does
     * nothing either. A partial update lets a caller leave the policy half-changed and then wonder
     * why the scores did not move.
     */
    @PutMapping("/{id}/scoring")
    @ApiResponse(responseCode = "200", description = "The policy as it now stands.")
    @ApiResponse(responseCode = "400",
        description = "A pass mark outside 0-100, points of zero or less, a negative penalty, or "
            + "an unknown scoring mode.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404", description = "No such test in this company.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public TestView scoring(@PathVariable UUID id, @RequestBody ScoringRequest request) {
        return TestView.of(tests.scoredAs(id, required(request.passMarkPercent()),
            request.negativeMarking() != null && request.negativeMarking(),
            request.defaultPoints() == null ? BigDecimal.ONE : request.defaultPoints(),
            mode(request.defaultMode()),
            request.penaltyPoints() == null ? BigDecimal.ZERO : request.penaltyPoints()));
    }

    /**
     * How it may be sat: how many attempts, and how long each one lasts (T-6.6).
     *
     * <p>Its own resource beside {@code /scoring}, because they are different decisions -- what a
     * result means against how the exam is invigilated -- made by different people at different
     * times.
     *
     * <p><b>Changing this does not touch an attempt already under way.</b> A deadline is computed
     * once, at start, from the limit in force then: shortening a test's limit cannot take time off
     * somebody mid-exam, and lengthening it cannot give them more.
     */
    @PutMapping("/{id}/sitting")
    @ApiResponse(responseCode = "200", description = "The policy as it now stands.")
    @ApiResponse(responseCode = "400",
        description = "An attempt limit below one, or a time limit that is not positive.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404", description = "No such test in this company.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public TestView sitting(@PathVariable UUID id, @RequestBody SittingForm form) {
        return TestView.of(tests.satAs(id, form.attemptsAllowed(),
            form.timeLimitSeconds() == null ? null
                : java.time.Duration.ofSeconds(form.timeLimitSeconds())));
    }

    /**
     * What a learner may see back, and when (T-6.9).
     *
     * <p>Its own resource, because it is a different decision from what a result <em>means</em>
     * (scoring) or how the exam is invigilated (sitting) — and because it is the one an author
     * changes when the exam stops being formative.
     *
     * <p><b>The default is the restrictive one and stays that way</b> until somebody sends this:
     * a new test shows a learner their score and nothing else.
     */
    @PutMapping("/{id}/review")
    @ApiResponse(responseCode = "200", description = "The policy as it now stands.")
    @ApiResponse(responseCode = "400",
        description = "A timing of AFTER_DATE with no date, a date on any other timing, or "
            + "AFTER_ALL_ATTEMPTS on a test with no attempt limit — which would mean never.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404", description = "No such test in this company.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public TestView review(@PathVariable UUID id, @RequestBody ReviewForm form) {
        return TestView.of(tests.reviewedAs(id, visibility(form.visibility()),
            timing(form.timing()), form.openAt()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tests.delete(id);
    }

    private static int required(Integer passMarkPercent) {
        if (passMarkPercent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A test says what passing it means. Give a pass mark as a whole percent; zero is "
                + "allowed and means nobody can fail, which is a real thing and a deliberate one.");
        }
        return passMarkPercent;
    }

    private static ReviewVisibility visibility(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A review policy says how much a learner may see: SCORE_ONLY, "
                + "SCORE_AND_WHICH_WRONG or FULL.");
        }
        try {
            return ReviewVisibility.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No review visibility '" + name + "'. There are three: SCORE_ONLY, "
                + "SCORE_AND_WHICH_WRONG, FULL.", unknown);
        }
    }

    private static ReviewTiming timing(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A review policy says when: IMMEDIATELY, AFTER_ALL_ATTEMPTS or AFTER_DATE.");
        }
        try {
            return ReviewTiming.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No review timing '" + name + "'. There are three: IMMEDIATELY, "
                + "AFTER_ALL_ATTEMPTS, AFTER_DATE.", unknown);
        }
    }

    private static ScoringMode mode(String name) {
        if (name == null || name.isBlank()) {
            return ScoringMode.ALL_OR_NOTHING;
        }
        try {
            return ScoringMode.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No scoring mode '" + name + "'. There are two: " + ScoringMode.ALL_OR_NOTHING
                + " and " + ScoringMode.PARTIAL_CREDIT + ".", unknown);
        }
    }
}
