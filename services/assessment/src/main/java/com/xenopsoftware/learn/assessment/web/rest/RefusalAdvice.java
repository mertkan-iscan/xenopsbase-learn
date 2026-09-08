package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.common.web.Problems;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refusals that say why (T-6.1), in the one shape this platform writes: RFC 9457 (T-9.10).
 *
 * <p>Catalog's copy of this carries the full reasoning; the short version is that Spring's default
 * error body deliberately drops the exception message, which is right for an unexpected exception
 * and wrong for a deliberate one. The usual fix — {@code server.error.include-message=always} —
 * buys the useful case by also putting every unanticipated 500's message on the wire.
 *
 * <p>So this handles exactly one type. A {@link ResponseStatusException} is thrown on purpose, at
 * a place that chose both the status and the sentence, and here the sentence is what an author
 * needs: "two banks with one name are two populations a section could draw from, with no way for
 * an author to tell which one they picked". A bare 409 is one nobody can act on.
 */
@RestControllerAdvice
public class RefusalAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> refused(ResponseStatusException refusal) {
        // getReason rather than getMessage: getMessage prefixes the status, so a client rendering
        // it shows the code twice.
        //
        // No `code`: these are thrown with a status and a sentence and no machine-readable
        // identity, and inventing one here would be inventing it. The document then carries type
        // about:blank, which is what RFC 9457 says an unidentified problem is.
        return Problems.respond(refusal.getStatusCode(), null, refusal.getReason());
    }
}
