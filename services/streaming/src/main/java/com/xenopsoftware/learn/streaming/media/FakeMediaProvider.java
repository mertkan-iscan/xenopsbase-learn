package com.xenopsoftware.learn.streaming.media;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The provider the local stack runs against (local-stack.md): video has no local equivalent, so
 * this holds asset state in memory and mints readable pseudo-tokens. It is enough for the entire
 * domain — entitlement, token minting, heartbeats, interval accounting, gating — and it proves
 * nothing about edge delivery, which only T-9.14's real account can.
 *
 * <p>Main code rather than test code, deliberately: `make run S=streaming` must work with no
 * vendor account (T-9.14), and tests and local dev must exercise the same adapter wiring
 * production uses, not a parallel one.
 *
 * <p>{@link #completeUpload} and {@link #failUpload} are the control surface a real provider's
 * encode pipeline would drive: local tooling and T-3.3's tests play the pipeline's part.
 */
@Component
@ConditionalOnProperty(name = "streaming.media.provider", havingValue = "fake", matchIfMissing = true)
public class FakeMediaProvider implements MediaProvider {

    public static final String PROVIDER_ID = "fake";

    private static final Logger LOG = LoggerFactory.getLogger(FakeMediaProvider.class);

    private record Asset(MediaAssetState state, Double durationSeconds, String error) {}

    private final Map<String, Asset> assets = new ConcurrentHashMap<>();

    @PostConstruct
    void warnLoudly() {
        // WARN, not INFO: a green run against this provider must never be read as evidence
        // that edge delivery works.
        LOG.warn("Media provider is FAKE. Uploads, encoding and playback tokens are simulated; "
            + "nothing here proves edge delivery works (T-9.14 is the real account).");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public UploadTarget createUploadTarget(UploadRequest request) {
        String ref = "fake-" + UUID.randomUUID();
        assets.put(ref, new Asset(MediaAssetState.PENDING_UPLOAD, null, null));
        // .invalid is reserved (RFC 2606): unroutable by construction, so nothing can mistake
        // a fake upload URL for a place bytes actually went.
        return new UploadTarget(ref, URI.create("https://fake-media.invalid/upload/" + ref),
            Instant.now().plus(Duration.ofHours(1)));
    }

    @Override
    public MediaAssetStatus status(String providerRef) {
        Asset asset = assets.get(providerRef);
        if (asset == null) {
            return MediaAssetStatus.of(MediaAssetState.GONE);
        }
        return new MediaAssetStatus(asset.state(), asset.durationSeconds(), asset.error());
    }

    @Override
    public PlaybackToken mintPlaybackToken(String providerRef, PlaybackGrant grant) {
        Instant expiresAt = Instant.now().plus(grant.validity());
        // Readable on purpose, so a test can pick it apart; nothing verifies it, which is
        // faithful to the real shape -- only the edge verifies tokens, never our services.
        return new PlaybackToken("fake-token." + providerRef + "." + expiresAt.getEpochSecond(),
            expiresAt);
    }

    @Override
    public void delete(String providerRef) {
        assets.remove(providerRef);
    }

    /** The encode pipeline's happy path, played by tests and local tooling. */
    public void completeUpload(String providerRef, double durationSeconds) {
        assets.computeIfPresent(providerRef,
            (ref, asset) -> new Asset(MediaAssetState.READY, durationSeconds, null));
    }

    /** The encode pipeline's failure path. */
    public void failUpload(String providerRef, String error) {
        assets.computeIfPresent(providerRef,
            (ref, asset) -> new Asset(MediaAssetState.ERRORED, null, error));
    }
}
