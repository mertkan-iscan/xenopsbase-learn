package com.xenopsoftware.learn.streaming.playback;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.common.tenancy.TenantStatusLookup;
import com.xenopsoftware.learn.streaming.media.MediaProvider;
import com.xenopsoftware.learn.streaming.media.PlaybackGrant;
import com.xenopsoftware.learn.streaming.media.PlaybackToken;
import com.xenopsoftware.learn.streaming.video.VideoAsset;
import com.xenopsoftware.learn.streaming.video.VideoAssetRepository;
import com.xenopsoftware.learn.streaming.video.VideoAssetState;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE ENTITLEMENT DECISION (T-3.4). Everything this platform claims about who may watch what is
 * enforced in {@link #mint}, and nowhere else.
 *
 * <p>That is not a stylistic preference. Once a token is signed the edge serves the bytes and no
 * later decision of ours can intervene — there is no revocation call, no callback, no chance to
 * change our mind (ADR-0101). This method is the last moment the platform controls, so a check
 * that is not here is a check that does not exist, and a check that runs after the signature is
 * decoration.
 *
 * <h2>The order, and why it is this one</h2>
 *
 * <ol>
 *   <li><b>Rate limit.</b> First, because it is the only check that costs nothing and because
 *       the thing it stops is a loop: putting it after the network calls would mean a farming
 *       script gets a hop to identity and a hop to catalog per attempt, and the defence becomes
 *       the load.</li>
 *   <li><b>Account status.</b> A suspended company stops before we spend anything asking about
 *       its people (T-1.4).</li>
 *   <li><b>Permission.</b> Whether this is a person who watches content at all (T-2.1).</li>
 *   <li><b>Assignment.</b> Whether this content reaches this person (T-5.5).</li>
 *   <li><b>Gate.</b> Whether it reaches them <em>yet</em> (T-5.3).</li>
 *   <li><b>Playable.</b> Whether there is anything to sign for — a node can be fully entitled
 *       and still be a video that has not finished encoding (T-3.3).</li>
 * </ol>
 *
 * <p>Cheapest and broadest first, so the common refusals are cheap and the expensive question is
 * only asked of a caller who has passed everything else.
 *
 * <h2>Why the status check is here as well as at the edge</h2>
 *
 * {@code StatusGateFilter} already refuses this endpoint for a suspended or read-only account —
 * it is a POST, so it counts as a write. This check is not redundant with it. The filter is a
 * property of the HTTP path; this is a property of the decision, which means the decision stays
 * complete and testable on its own, its refusal is audited with a reason like every other one,
 * and the check cannot be lost by a future caller reaching the decision another way.
 */
@Service
public class PlaybackTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(PlaybackTokenService.class);

    /**
     * What a caller gets, and what a player needs to keep playing.
     *
     * @param renewAfter when to ask for the next token — comfortably before {@code expiresAt},
     *                   so a failed renewal has room to be retried without the learner seeing it
     */
    public record IssuedPlayback(UUID nodeId, UUID videoAssetId, String token,
                                 Instant expiresAt, Instant renewAfter) {}

    private final MintRateLimiter rateLimiter;
    private final TenantStatusLookup statusLookup;
    private final ViewerPermissions viewerPermissions;
    private final ContentEntitlement entitlement;
    private final VideoAssetRepository videoAssets;
    private final MediaProvider mediaProvider;
    private final ViewerDirectory viewerDirectory;
    private final PlaybackAudit audit;
    private final PlaybackProperties properties;
    private final Clock clock;

    public PlaybackTokenService(MintRateLimiter rateLimiter,
            ObjectProvider<TenantStatusLookup> statusLookup, ViewerPermissions viewerPermissions,
            ContentEntitlement entitlement, VideoAssetRepository videoAssets,
            MediaProvider mediaProvider, ViewerDirectory viewerDirectory, PlaybackAudit audit,
            PlaybackProperties properties, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.statusLookup = statusLookup.getIfAvailable();
        this.viewerPermissions = viewerPermissions;
        this.entitlement = entitlement;
        this.videoAssets = videoAssets;
        this.mediaProvider = mediaProvider;
        this.viewerDirectory = viewerDirectory;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        if (this.statusLookup == null) {
            LOG.warn("No TenantStatusLookup is configured, so playback tokens are minted without "
                + "checking whether the account may still watch (T-1.4). The module owning the "
                + "rows still refuses writes; nothing here bounds a suspension.");
        }
    }

    /**
     * Decide, then sign — and audit whichever way it goes wrong.
     *
     * <p>Read-only as far as this service's own tables are concerned: nothing about minting a
     * token changes state here, which is what lets the hot path stay a read plus a signature.
     * The audit write is deliberately outside it, in its own transaction.
     */
    @Transactional(readOnly = true)
    public IssuedPlayback mint(UUID nodeId) {
        Viewer viewer = currentViewer();
        try {
            return decide(nodeId, viewer);
        } catch (PlaybackRefusedException refused) {
            // Resolved here rather than inside the audit, so the hop to identity happens before
            // the REQUIRES_NEW transaction opens instead of holding a connection across it.
            UUID actor = viewerDirectory.currentAppUserId().orElse(null);
            audit.recordRefusal(viewer.tenantId(), actor, nodeId, refused.reason(), refused.detail());
            throw refused;
        }
    }

    private IssuedPlayback decide(UUID nodeId, Viewer viewer) {
        if (!rateLimiter.permit(viewer)) {
            throw new PlaybackRefusedException(RefusalReason.RATE_LIMITED);
        }

        AccountStatus status = statusLookup == null
            ? AccountStatus.ACTIVE
            : statusLookup.statusOf(viewer.tenantId(), viewer.subject());
        if (status == AccountStatus.SUSPENDED) {
            throw new PlaybackRefusedException(RefusalReason.ACCOUNT_SUSPENDED);
        }
        if (status == AccountStatus.READ_ONLY) {
            // A read-only account keeps its reads, and this is not one: a playback token is a
            // fresh entitlement that keeps working after the request that made it.
            throw new PlaybackRefusedException(RefusalReason.ACCOUNT_READ_ONLY);
        }

        if (!viewerPermissions.mayViewContent()) {
            throw new PlaybackRefusedException(RefusalReason.NO_PERMISSION);
        }

        NodeEntitlement node = entitlement.lookUp(nodeId, viewer)
            .orElseThrow(() -> new PlaybackRefusedException(RefusalReason.UNKNOWN_NODE));
        if (!node.assigned()) {
            throw new PlaybackRefusedException(RefusalReason.NOT_ASSIGNED);
        }
        if (!node.reachable()) {
            throw new PlaybackRefusedException(RefusalReason.GATED, node.gateReason());
        }
        if (node.videoAssetId() == null) {
            throw new PlaybackRefusedException(RefusalReason.NOT_PLAYABLE,
                "the node points at no video");
        }

        // Tenant-filtered by the persistence layer (T-1.1), so a catalog answer naming another
        // tenant's asset resolves to nothing here rather than to a signature.
        VideoAsset asset = videoAssets.findById(node.videoAssetId())
            .orElseThrow(() -> new PlaybackRefusedException(RefusalReason.NOT_PLAYABLE,
                "no such video asset in this tenant: " + node.videoAssetId()));
        if (asset.getState() != VideoAssetState.READY) {
            throw new PlaybackRefusedException(RefusalReason.NOT_PLAYABLE,
                "the video is " + asset.getState());
        }

        PlaybackToken token = mediaProvider.mintPlaybackToken(asset.getProviderRef(),
            new PlaybackGrant(viewer.subject(), properties.tokenTtl()));
        return new IssuedPlayback(nodeId, asset.getId(), token.token(), token.expiresAt(),
            clock.instant().plus(properties.renewAfter()));
    }

    private static Viewer currentViewer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // The security chain authenticates everything under /api, so reaching here without
            // a JWT is a wiring mistake rather than a caller error.
            throw new IllegalStateException("A playback token needs a verified caller");
        }
        return new Viewer(TenantContext.require(), token.getToken().getSubject());
    }
}
