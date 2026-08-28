package com.xenopsoftware.learn.streaming.media;

/**
 * What the provider needs to prepare an upload target. Deliberately small: tenancy, ownership
 * and naming are OUR data ({@code video_asset}), not something to teach a vendor.
 *
 * @param maxDurationSeconds upper bound the provider may enforce at ingest; also what the
 *        prepaid storage bill is exposed to, so it is required rather than defaulted
 */
public record UploadRequest(long maxDurationSeconds) {

    public UploadRequest {
        if (maxDurationSeconds <= 0) {
            throw new IllegalArgumentException("maxDurationSeconds must be positive");
        }
    }
}
