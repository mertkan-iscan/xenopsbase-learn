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
    ABANDONED
}
