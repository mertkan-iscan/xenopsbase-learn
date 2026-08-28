package com.xenopsoftware.learn.streaming.video;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The tenant's accountable bytes plus this upload would pass their quota. Refused before a
 * target exists — after the bytes arrive is a bill, not an enforcement.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
