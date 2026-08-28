package com.xenopsoftware.learn.streaming.video;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The declared upload exceeds the per-file ceiling. Refused before a target exists. */
@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
public class UploadLimitException extends RuntimeException {

    public UploadLimitException(String message) {
        super(message);
    }
}
