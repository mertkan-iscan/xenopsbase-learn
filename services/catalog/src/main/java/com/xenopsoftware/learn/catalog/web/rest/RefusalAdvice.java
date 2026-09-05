package com.xenopsoftware.learn.catalog.web.rest;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refusals that say why (T-5.1).
 *
 * <p>Spring's default error body carries a status and a path and deliberately drops the exception
 * message, which is the right default: an unexpected exception's message is an internal detail and
 * putting it on the wire is how stack-shaped strings end up in a customer's browser. The usual fix
 * — {@code server.error.include-message=always} — buys the useful case by also exposing every
 * message this service never meant to send, including from a 500 nobody anticipated.
 *
 * <p>So this handles exactly one exception type. A {@link ResponseStatusException} is thrown on
 * purpose, at a place that chose both the status and the sentence, and the sentence is written for
 * whoever is on the other end: "a PUBLISHED item cannot become DRAFT — something may already point
 * at it and a learner may be part-way through it". A 409 without that is a 409 an author cannot
 * act on. Everything else keeps the opaque default.
 *
 * <p>The shape matches the refusals {@code identity} writes from its filters — a machine-readable
 * field beside the sentence — so a client parses one thing across services.
 */
@RestControllerAdvice
public class RefusalAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> refused(ResponseStatusException refusal) {
        return ResponseEntity.status(refusal.getStatusCode())
            .body(Map.of(
                "status", refusal.getStatusCode().value(),
                // getReason rather than getMessage: getMessage prefixes the status, so a client
                // rendering it shows the code twice and an author reads "409 CONFLICT ..." in a
                // toast that already says Conflict.
                "message", refusal.getReason() == null ? "" : refusal.getReason()));
    }
}
