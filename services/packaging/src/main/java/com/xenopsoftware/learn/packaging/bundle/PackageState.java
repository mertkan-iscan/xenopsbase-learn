package com.xenopsoftware.learn.packaging.bundle;

/**
 * How far an uploaded archive has been allowed to get (T-4.1).
 *
 * <p>The states this platform decided on, not a vendor's and not a file format's — the same
 * argument {@code VideoAssetState} makes for encode state. Nothing here is ever set from something
 * an archive said about itself.
 */
public enum PackageState {

    /** A target has been issued. No byte of this package has been examined yet. */
    PENDING_UPLOAD,

    /** The archive is being streamed, checked and extracted. */
    PROCESSING,

    /** Every check passed. This is the only state a launch URL is issued for. */
    READY,

    /**
     * The archive was refused, and {@code error} says by which check.
     *
     * <p><b>Distinct from {@link #FAILED}, and the distinction is who has to act.</b> A rejection
     * is the author's to fix — an entry escaping the root, a ratio no honest export produces, a
     * manifest that names no launchable resource — and the sentence tells them what. Telling that
     * person to re-export because our object storage was briefly unreachable would be a lie that
     * costs them an afternoon.
     */
    REJECTED,

    /** Something on our side broke. Retryable, and never the author's problem to solve. */
    FAILED,

    /** Somebody asked for it to go; the bytes are on their way out. */
    DELETING,

    /** The bytes are gone. Only ever set after storage has confirmed it (T-3.8's rule). */
    DELETED;

    /** Whether this package can still be launched. Nothing else may decide that. */
    public boolean isLaunchable() {
        return this == READY;
    }

    /** Whether the row is on its way out or already gone — either way it never launches again. */
    public boolean isBeingRemoved() {
        return this == DELETING || this == DELETED;
    }
}
