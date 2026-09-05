package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.streaming.progress.LearnerUnresolvedException;
import com.xenopsoftware.learn.streaming.progress.ProgressRejectedException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A batch that was not credited answers a specific status and a code, never a 500 (T-3.7).
 *
 * <p>The distinction matters to a client that cannot ask a person what to do: a 413 means split
 * the batch, a 400 means stop resending this one, a 409 means the rule it broke is one it was told
 * about, and a 503 means the samples are fine and later will work. An unhandled exception would
 * say "unknown, try again" about a batch that will never be accepted however many times it is
 * sent — and one broken player would become sustained load while looking like server trouble.
 *
 * <p>Separate from {@code PlaybackRefusedAdvice} because they answer different questions with
 * different disclosure rules: a refused <em>token</em> hides which of three reasons applied, and a
 * refused <em>batch</em> names its reason precisely, because the client is expected to act on it.
 */
@RestControllerAdvice
public class ProgressRejectedAdvice {

    @ExceptionHandler(ProgressRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(ProgressRejectedException rejected) {
        return ResponseEntity.status(rejected.reason().status())
            .body(Map.of("error", Map.of("code", rejected.reason().name(),
                "message", "This batch was not credited.")));
    }

    /**
     * Identity could not name the caller, so nothing durable can be credited (ADR-0104).
     *
     * <p>503 and not 500: nothing is wrong with the request, one dependency is unavailable, and
     * the player's correct response is to keep the samples and post them again — which is exactly
     * what a client does with a 5xx and exactly what it must not do with a 4xx.
     */
    @ExceptionHandler(LearnerUnresolvedException.class)
    public ResponseEntity<Map<String, Object>> unresolved() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", Map.of("code", "LEARNER_UNRESOLVED",
                "message", "Progress cannot be recorded right now. Nothing has been lost.")));
    }
}
