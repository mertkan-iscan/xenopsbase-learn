package com.xenopsoftware.learn.streaming.media;

/**
 * A point-in-time answer from the provider, never stored as-is — {@code video_asset} keeps its
 * own state column and T-3.3 reconciles the two idempotently.
 *
 * @param durationSeconds known once encoding has measured it; null before READY
 * @param error           the provider's reason when {@code state} is ERRORED, for the operator
 */
public record MediaAssetStatus(MediaAssetState state, Double durationSeconds, String error) {

    public static MediaAssetStatus of(MediaAssetState state) {
        return new MediaAssetStatus(state, null, null);
    }
}
