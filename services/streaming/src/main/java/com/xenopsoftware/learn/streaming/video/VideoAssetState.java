package com.xenopsoftware.learn.streaming.video;

/**
 * Our asset lifecycle — the provider's vocabulary ({@code MediaAssetState}) maps onto this, but
 * it is not this: ABANDONED is a state only we know, because only we know a target was issued
 * and nobody ever finished using it.
 */
public enum VideoAssetState {
    PENDING_UPLOAD,
    PROCESSING,
    READY,
    ERRORED,
    /** Reaped by {@code UploadReaper}: the target expired unused and the provider asset is deleted. */
    ABANDONED,
    /**
     * Somebody asked for this video to be deleted and the provider has not confirmed yet (T-3.8).
     *
     * <p>Playback stops here rather than at DELETED, because the request is the point at which the
     * customer expects the video to be gone; waiting for a provider round trip to stop serving it
     * would mean a deletion that is honoured only as fast as the vendor's API happens to be.
     */
    DELETING,
    /**
     * The provider has confirmed the bytes are gone (T-3.8).
     *
     * <p>The row stays. It is the record of what was deleted, when, by whom and why, and it is
     * what a dangling {@code content_item} reference resolves to — a clear answer instead of a
     * missing row.
     */
    DELETED
}
