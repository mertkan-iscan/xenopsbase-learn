package com.xenopsoftware.learn.streaming.progress;

/** A batch that will not be credited, with the reason the client is told and the metric counts. */
public class ProgressRejectedException extends RuntimeException {

    private final transient ProgressRejection reason;

    public ProgressRejectedException(ProgressRejection reason, String detail) {
        super(reason.name() + ": " + detail, null, false, false);
        this.reason = reason;
    }

    public ProgressRejection reason() {
        return reason;
    }
}
