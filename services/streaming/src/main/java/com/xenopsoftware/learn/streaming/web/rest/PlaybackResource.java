package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.streaming.playback.PlaybackTokenService;
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
     * @param renewAfter when to come back for the next one. The player follows this rather than
     *                   computing its own schedule from {@code expiresAt}, so the renewal
     *                   cadence stays a server decision that can be changed without shipping a
     *                   player (T-3.5).
     */
    public record PlaybackTokenView(UUID nodeId, UUID videoAssetId, String token,
                                    Instant expiresAt, Instant renewAfter) {}

    @PostMapping("/me/nodes/{id}/playback-token")
    public PlaybackTokenView playbackToken(@PathVariable UUID id) {
        PlaybackTokenService.IssuedPlayback issued = playbackTokens.mint(id);
        return new PlaybackTokenView(issued.nodeId(), issued.videoAssetId(), issued.token(),
            issued.expiresAt(), issued.renewAfter());
    }
}
