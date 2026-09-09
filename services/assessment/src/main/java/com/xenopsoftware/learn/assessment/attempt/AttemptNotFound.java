package com.xenopsoftware.learn.assessment.attempt;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No such attempt in this tenant (T-6.6). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AttemptNotFound extends RuntimeException {

    public AttemptNotFound() {
        super("No such attempt");
    }
}
