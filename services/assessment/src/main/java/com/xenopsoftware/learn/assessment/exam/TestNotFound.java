package com.xenopsoftware.learn.assessment.exam;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No such test in THIS tenant, which is the only sense in which "no such test" has a meaning here:
 * another company's id resolves to nothing because the discriminator filtered it, not because a
 * check refused it. Same 404 either way, which is the point (ADR-0102).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class TestNotFound extends RuntimeException {

    public TestNotFound() {
        super("No such test");
    }
}
