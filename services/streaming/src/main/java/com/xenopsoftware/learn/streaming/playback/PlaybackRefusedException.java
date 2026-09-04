package com.xenopsoftware.learn.streaming.playback;

/**
 * A refused entitlement decision (T-3.4), carrying the reason the audit records and the
 * response renders — which are not always the same thing (see {@link RefusalReason}).
 */
public class PlaybackRefusedException extends RuntimeException {

    private final transient RefusalReason reason;
    private final transient String detail;

    public PlaybackRefusedException(RefusalReason reason) {
        this(reason, null);
    }

    /**
     * @param detail extra context for the audit, and — only where the reason discloses itself —
     *               the sentence shown to the caller in place of the generic one. A gate's
     *               reason arrives this way (T-5.3).
     */
    public PlaybackRefusedException(RefusalReason reason, String detail) {
        super(reason.name() + (detail == null ? "" : ": " + detail), null, false, false);
        this.reason = reason;
        this.detail = detail;
    }

    public RefusalReason reason() {
        return reason;
    }

    public String detail() {
        return detail;
    }
}
