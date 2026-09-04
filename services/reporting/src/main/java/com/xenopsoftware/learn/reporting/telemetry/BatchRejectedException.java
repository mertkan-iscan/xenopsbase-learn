package com.xenopsoftware.learn.reporting.telemetry;

/** A batch that will not be recorded, with the reason the client is told and the metric counts. */
public class BatchRejectedException extends RuntimeException {

    private final transient RejectionReason reason;

    public BatchRejectedException(RejectionReason reason, String detail) {
        super(reason.name() + ": " + detail, null, false, false);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
