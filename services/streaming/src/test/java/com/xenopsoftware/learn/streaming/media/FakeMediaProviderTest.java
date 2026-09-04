package com.xenopsoftware.learn.streaming.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The fake's contract IS the port's contract — these tests document the lifecycle every adapter
 * must honor, on the implementation the whole domain suite will run against.
 */
class FakeMediaProviderTest {

    private final FakeMediaProvider provider = new FakeMediaProvider(java.time.Clock.systemUTC());

    @Test
    void theFullLifecycleRoundTrips() {
        UploadTarget target = provider.createUploadTarget(new UploadRequest(3600, 1_000_000));

        assertThat(target.providerRef()).startsWith("fake-");
        assertThat(target.uploadUrl().getHost()).endsWith(".invalid");
        assertThat(provider.status(target.providerRef()).state())
            .isEqualTo(MediaAssetState.PENDING_UPLOAD);

        provider.completeUpload(target.providerRef(), 542.5);
        MediaAssetStatus ready = provider.status(target.providerRef());
        assertThat(ready.state()).isEqualTo(MediaAssetState.READY);
        assertThat(ready.durationSeconds()).isEqualTo(542.5);

        provider.delete(target.providerRef());
        assertThat(provider.status(target.providerRef()).state()).isEqualTo(MediaAssetState.GONE);
        // Idempotent: the second delete of anything is a no-op, not an error.
        provider.delete(target.providerRef());
    }

    @Test
    void anUnknownRefIsGoneNotAnError() {
        assertThat(provider.status("fake-never-existed").state()).isEqualTo(MediaAssetState.GONE);
    }

    @Test
    void aFailedEncodeCarriesItsReason() {
        UploadTarget target = provider.createUploadTarget(new UploadRequest(3600, 1_000_000));
        provider.failUpload(target.providerRef(), "codec not supported");

        MediaAssetStatus status = provider.status(target.providerRef());
        assertThat(status.state()).isEqualTo(MediaAssetState.ERRORED);
        assertThat(status.error()).isEqualTo("codec not supported");
    }

    @Test
    void aPlaybackTokenExpiresWithItsGrant() {
        UploadTarget target = provider.createUploadTarget(new UploadRequest(3600, 1_000_000));
        Instant before = Instant.now();

        PlaybackToken token = provider.mintPlaybackToken(target.providerRef(),
            new PlaybackGrant("sub-viewer", Duration.ofMinutes(10)));

        assertThat(token.token())
            .contains(target.providerRef())
            .as("bound to the viewer it was decided for (T-3.4)")
            .contains("sub-viewer");
        assertThat(token.manifestUrl().toString())
            .as("somewhere to play it from, which is not this service (T-3.5, ADR-0101)")
            .startsWith("https://fake-media.invalid/")
            .endsWith("/manifest/video.m3u8?token=" + token.token());
        assertThat(token.expiresAt()).isBetween(
            before.plus(Duration.ofMinutes(10)).minusSeconds(5),
            Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
    }

    @Test
    void theGrantCeilingIsARuleNotASuggestion() {
        assertThatThrownBy(() -> new PlaybackGrant("sub-viewer", Duration.ofHours(2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be revoked");

        // And the other half of the binding: a grant that names nobody is one no log can
        // attribute afterwards, so it is not constructible either.
        assertThatThrownBy(() -> new PlaybackGrant(null, Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("names the viewer");
    }
}
