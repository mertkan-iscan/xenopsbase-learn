package com.xenopsoftware.learn.assessment.exam;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No such section in this tenant (T-6.5). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SectionNotFound extends RuntimeException {

    public SectionNotFound() {
        super("No such section");
    }
}
