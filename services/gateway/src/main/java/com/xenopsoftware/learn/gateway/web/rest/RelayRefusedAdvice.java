package com.xenopsoftware.learn.gateway.web.rest;

import com.xenopsoftware.learn.common.web.Problems;
import com.xenopsoftware.learn.gateway.relay.SessionEndedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A session that ended is a 401 the frontend can act on (T-10.2, T-9.10's shape).
 *
 * <p>The {@code code} is the whole value of this class. A bare 401 leaves the frontend guessing
 * between "sign in again" and "you may not do this", and the guess it makes decides whether a
 * half-finished exam is preserved or thrown away. {@code SESSION_ENDED} says which, and
 * {@code useRecovery} in the frontend keys off exactly this.
 */
@RestControllerAdvice
public class RelayRefusedAdvice {

    @ExceptionHandler(SessionEndedException.class)
    public ResponseEntity<ProblemDetail> sessionEnded(SessionEndedException ended) {
        return Problems.respond(HttpStatus.UNAUTHORIZED, "SESSION_ENDED",
            "Your session has ended. Sign in again; anything you were part-way through is still "
                + "here.");
    }
}
