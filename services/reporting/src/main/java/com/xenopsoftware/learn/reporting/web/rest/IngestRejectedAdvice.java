package com.xenopsoftware.learn.reporting.web.rest;

import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import com.xenopsoftware.learn.common.web.Problems;
import com.xenopsoftware.learn.reporting.telemetry.BatchRejectedException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A refused batch answers a specific status and a code, never a 500 (T-3.6, T-7.1).
 *
 * <p>The distinction matters to a client that cannot ask a person what to do: a 413 means split
 * the batch and resend, a 400 means stop resending this one, and a 500 means "unknown, try
 * again" — which is what an unhandled exception would say about a batch that will never be
 * accepted however many times it is sent. One broken player then becomes sustained load, and the
 * bug is invisible because it looks like server trouble.
 *
 * <p>Unparseable JSON is handled here too, for the same reason and with the same shape: it is the
 * client's problem, it will not fix itself on retry, and a stack trace in the response body would
 * tell an anonymous poster about our internals.
 */
@RestControllerAdvice
public class IngestRejectedAdvice {

    @ExceptionHandler(BatchRejectedException.class)
    public ResponseEntity<ProblemDetail> rejected(BatchRejectedException rejected) {
        return Problems.respond(rejected.reason().status(), rejected.reason().name(), "This batch was not recorded.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable() {
        return Problems.respond(HttpStatus.BAD_REQUEST, "MALFORMED_BATCH", "This batch could not be read.");
    }
}
