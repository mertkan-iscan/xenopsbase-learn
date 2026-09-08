package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import com.xenopsoftware.learn.streaming.playback.PlaybackTokenService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint that decides who may watch (T-3.4).
 *
 * <p>Under {@code /me/} because the answer is only ever about the caller: there is no version of
 * this that mints a token for somebody else, and an endpoint that took a viewer id would be one
 * refactor away from being one. Support staff who need to see what a learner sees get there
 * through impersonation (T-2.8), which is visible afterwards.
 *
 * <p>A POST, and not because anything is created here. It is a POST because it must not be
 * cached, prefetched, retried by a proxy or logged in a query string — a GET that returns a
 * bearer-equivalent credential ends up in a browser history, a referrer header and a CDN.
 *
 * <h2>Why there is no {@code @PreAuthorize}</h2>
 *
 * The permission check is real and it is inside {@link PlaybackTokenService}, as one link of an
 * ordered chain. Method security would run it before the rate limiter, which inverts the order
 * that makes the rate limiter worth having, and it would refuse without the audit entry that
 * T-3.4 requires of every refusal. Identity keeps its {@code CatalogCoverageTest} honest by
 * walking its own handler mappings; this service is outside that walk, which is a real gap
 * worth closing when a second permission-checking endpoint exists here.
 */
@RestController
@RequestMapping("/api/v1")
public class PlaybackResource {

    private final PlaybackTokenService playbackTokens;

    public PlaybackResource(PlaybackTokenService playbackTokens) {
        this.playbackTokens = playbackTokens;
    }

    /**
     * @param manifestUrl where to play it from — an edge URL that is not this service and does
     *                    not become this service on a bad day (ADR-0101). The player is handed
     *                    it rather than composing it, so the delivery vendor stays swappable
     *                    without a browser release.
     * @param renewAfter when to come back for the next one. The player follows this rather than
     *                   computing its own schedule from {@code expiresAt}, so the renewal
     *                   cadence stays a server decision that can be changed without shipping a
     *                   player (T-3.5).
     */
    public record PlaybackTokenView(UUID nodeId, UUID videoAssetId, String token,
                                    URI manifestUrl, Instant expiresAt, Instant renewAfter) {}

    @PostMapping("/me/nodes/{id}/playback-token")
    // DECLARED, because declaring any response replaces the one springdoc infers from the
    // return type. Left out, this operation documents four refusals and no success.
    @ApiResponse(responseCode = "200", description = "A token, where to play it from, and "
        + "when to come back for the next one.")
    @ApiResponse(responseCode = "403",
        description = "Refused with a reason the caller may know: `ACCOUNT_SUSPENDED`, "
            + "`ACCOUNT_READ_ONLY` or `CONTENT_GATED` — the last carrying the gate's own "
            + "sentence in `detail`, because T-5.3 requires a rule to be readable by the "
            + "learner it stops.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404",
        description = "**No body, deliberately.** No permission, not assigned, and no such "
            + "node are one indistinguishable answer: a caller must not be able to tell "
            + "\"not yours\" from \"does not exist\". Which of the three it was is in the "
            + "audit log, and nowhere a caller can read.",
        // AN EMPTY @Content, and it has to be here. Left off, springdoc falls back to the
        // method return type and documents a 404 carrying a PlaybackTokenView -- a
        // description saying "no body" over a schema promising a token, which is the exact
        // shape of lie this task exists to remove.
        content = @Content)
    @ApiResponse(responseCode = "409",
        description = "`NOT_PLAYABLE`. The asset exists and is not ready to be watched.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "429",
        description = "`PLAYBACK_RATE_LIMITED`. Twenty mints per five minutes per person; "
            + "a player renewing on schedule never reaches it.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public PlaybackTokenView playbackToken(@PathVariable UUID id) {
        PlaybackTokenService.IssuedPlayback issued = playbackTokens.mint(id);
        return new PlaybackTokenView(issued.nodeId(), issued.videoAssetId(), issued.token(),
            issued.manifestUrl(), issued.expiresAt(), issued.renewAfter());
    }
}
