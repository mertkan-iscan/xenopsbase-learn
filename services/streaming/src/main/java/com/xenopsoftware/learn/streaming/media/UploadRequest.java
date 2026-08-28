package com.xenopsoftware.learn.streaming.media;

/**
 * What the provider needs to prepare an upload target. Deliberately small: tenancy, ownership
 * and naming are OUR data ({@code video_asset}), not something to teach a vendor.
 *
 * @param maxDurationSeconds upper bound the provider may enforce at ingest; also what the
 *        prepaid storage bill is exposed to, so it is required rather than defaulted
 * @param sizeBytes the declared upload size. Required up front for two reasons that reinforce
 *        each other: resumable (tus) targets need the length at creation, and T-3.2's quota
 *        rule — enforced before the target is issued, never after the bytes arrive — needs a
 *        number to enforce against
 */
public record UploadRequest(long maxDurationSeconds, long sizeBytes) {

    public UploadRequest {
        if (maxDurationSeconds <= 0) {
            throw new IllegalArgumentException("maxDurationSeconds must be positive");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }
}
