package com.xenopsoftware.learn.streaming.media;

/**
 * Where an asset is in its life, in our words — every adapter maps its vendor's vocabulary onto
 * exactly these, so encode-state handling (T-3.3) is written once.
 */
public enum MediaAssetState {
    PENDING_UPLOAD,
    PROCESSING,
    READY,
    ERRORED,
    /** The provider no longer knows the ref — deleted, expired unused, or never valid. */
    GONE
}
