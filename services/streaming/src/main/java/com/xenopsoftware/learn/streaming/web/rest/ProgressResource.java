package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import com.xenopsoftware.learn.streaming.progress.LearnerProgress;
import com.xenopsoftware.learn.streaming.progress.ProgressBatch;
import com.xenopsoftware.learn.streaming.progress.ProgressService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What was watched, and what the server derived from it (T-3.7).
 *
 * <p>Under {@code /me/} for the reason the playback token is: the answer is only ever about the
 * caller. There is no version of this that records progress for somebody else, and an endpoint
 * that took a learner id would be one refactor away from being one — which is the endpoint
 * ADR-0107 exists to never build, because "record this completion for that person" is the same
 * hole as "record this completion for me" with a wider blast radius.
 *
 * <h2>Two verbs, two different jobs</h2>
 *
 * The POST is the merge: it is called every ten seconds by a playing player and its response is
 * what the player renders. The GET is called once, on load, and answers the questions a player
 * cannot answer for itself — where to resume, whether this item allows skipping ahead, and
 * whether something inside it is waiting to be answered (T-5.4).
 *
 * <p>Both return the same view. A player that has just posted and a player that has just loaded
 * are looking at the same thing, and two shapes for it would be two renderings to keep in step.
 */
@RestController
@RequestMapping("/api/v1")
public class ProgressResource {

    private final ProgressService progress;

    public ProgressResource(ProgressService progress) {
        this.progress = progress;
    }

    /**
     * Merge a batch of watched intervals.
     *
     * <p>Idempotent in the way that matters: re-posting a batch changes nothing, because coverage
     * is a union and the rate check counts what was credited rather than what was claimed. So a
     * client may retry a post it is unsure about without inflating anybody's progress — which is
     * what lets the player retry at all (T-3.6).
     */
    @PostMapping("/me/nodes/{id}/progress")
    // DECLARED, because declaring any response replaces the one springdoc infers from the
    // return type. Left out, this operation documents four refusals and no success.
    @ApiResponse(responseCode = "200", description = "The merged coverage after this batch, "
        + "which is what the player renders.")
    @ApiResponse(responseCode = "400",
        description = "Nothing was credited and resending will not change that: "
            + "`EMPTY_BATCH`, `MALFORMED_INTERVAL`, `IMPLAUSIBLE_RATE` or "
            + "`MISSING_ATTRIBUTION`.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "409",
        description = "`SEEK_NOT_ALLOWED` or `INTERSTITIAL_UNANSWERED`. Both mean the "
            + "batch reports playback past a boundary the player had already been told "
            + "about: the end of what has been watched on an item that forbids skipping "
            + "ahead, or an unanswered blocking interstitial (T-5.4). Show the learner "
            + "what is in the way; resending will not help.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "413",
        description = "`BATCH_TOO_LARGE`. Split it and post the halves.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "503",
        description = "`LEARNER_UNRESOLVED`. Nothing is wrong with the request and nothing "
            + "has been lost: identity could not name the caller, so keep the samples and "
            + "post them again. A 5xx rather than a 4xx precisely so a client retries.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public LearnerProgress record(@PathVariable UUID id, @RequestBody ProgressBatch batch) {
        return progress.record(id, batch);
    }

    /** Where this learner is, before anything has been posted for this session. */
    @GetMapping("/me/nodes/{id}/progress")
    @ApiResponse(responseCode = "200", description = "Where this learner is, and whether "
        + "this item allows skipping ahead.")
    @ApiResponse(responseCode = "503",
        description = "`LEARNER_UNRESOLVED`. Identity could not name the caller; try "
            + "again.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public LearnerProgress current(@PathVariable UUID id) {
        return progress.current(id);
    }
}
