package com.xenopsoftware.learn.assessment.question;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No such question in THIS tenant — the only sense the phrase has here.
 *
 * <p>Another company's id resolves to nothing because the discriminator filtered it, not because a
 * check refused it, and a retired question answers the same way to an authoring read. Same 404 in
 * all three cases, which is the point (ADR-0102): a caller cannot tell an id that never existed
 * from one that belongs to somebody else.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class QuestionNotFound extends RuntimeException {

    public QuestionNotFound() {
        super("No such question");
    }
}
