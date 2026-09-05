package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.streaming.progress.LearnerProgress;
import com.xenopsoftware.learn.streaming.progress.ProgressBatch;
import com.xenopsoftware.learn.streaming.progress.ProgressService;
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
 * what the player renders. The GET is called once, on load, and answers the two questions a player
 * cannot answer for itself — where to resume, and whether this item allows skipping ahead.
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
    public LearnerProgress record(@PathVariable UUID id, @RequestBody ProgressBatch batch) {
        return progress.record(id, batch);
    }

    /** Where this learner is, before anything has been posted for this session. */
    @GetMapping("/me/nodes/{id}/progress")
    public LearnerProgress current(@PathVariable UUID id) {
        return progress.current(id);
    }
}
