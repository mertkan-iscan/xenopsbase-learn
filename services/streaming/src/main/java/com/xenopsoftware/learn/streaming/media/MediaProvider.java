package com.xenopsoftware.learn.streaming.media;

/**
 * The port that keeps the delivery decision reversible (T-3.1, ADR-0101).
 *
 * <p>Four operations, and no vendor type in any signature. The Cloudflare Stream adapter behind
 * this today is a pricing decision someone else controls; the escape hatch ADR-0101 names — own
 * transcode into R2, same edge, same signing — stays an adapter swap only while no domain code
 * ever learns the word "Cloudflare". An ArchUnit rule holds that line, because it erodes one
 * reasonable-looking convenience at a time: a provider id in a controller, a vendor field in a
 * report, and the swap has become a migration.
 *
 * <p>{@code providerRef} is opaque everywhere: minted by the provider, stored next to
 * {@link #providerId()} as a discriminator, meaningful to nothing but the adapter that issued
 * it. Domain tables (and {@code content_item} in catalog, T-5.1) reference video by OUR asset
 * id; the pair (provider, provider_ref) appears exactly once, in {@code video_asset}.
 */
public interface MediaProvider {

    /** The discriminator stored beside every ref this adapter mints, e.g. {@code fake}. */
    String providerId();

    /**
     * A place for a client to send bytes, so the bytes never pass through a request thread of
     * ours (T-3.2's rule). The ref exists from this moment; the asset becomes watchable later.
     */
    UploadTarget createUploadTarget(UploadRequest request);

    /** Where the asset is in its life — encode state arrives by webhook AND poll (T-3.3). */
    MediaAssetStatus status(String providerRef);

    /**
     * The entitlement decision made durable for a few minutes (T-3.4): a short-lived token the
     * edge will honor with no call back to us. Minting is local — no network on the hot path.
     */
    PlaybackToken mintPlaybackToken(String providerRef, PlaybackGrant grant);

    /** Deleting a video must delete the bytes (T-3.8). Idempotent: a second delete is a no-op. */
    void delete(String providerRef);
}
