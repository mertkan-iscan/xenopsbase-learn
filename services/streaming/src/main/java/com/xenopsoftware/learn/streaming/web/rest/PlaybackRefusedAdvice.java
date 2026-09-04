package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.streaming.playback.PlaybackRefusedException;
import com.xenopsoftware.learn.streaming.playback.RefusalReason;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders a refused entitlement decision (T-3.4) in the envelope the platform already uses —
 * the same {@code {"error":{"code","message"}}} shape {@code StatusGateFilter} writes, so a
 * client has one thing to parse whether the refusal came from the edge or from the decision.
 *
 * <p>The disclosure rule lives in {@link RefusalReason}, not here: a reason either names itself
 * to the caller or answers a bare 404, and this only renders what the reason permits. Keeping
 * the two apart is what stops a helpful message being added next to a 404 that was carefully
 * chosen to say nothing.
 */
@RestControllerAdvice
public class PlaybackRefusedAdvice {

    @ExceptionHandler(PlaybackRefusedException.class)
    public ResponseEntity<Map<String, Object>> refused(PlaybackRefusedException refused) {
        RefusalReason reason = refused.reason();
        if (!reason.isDisclosed()) {
            // No body at all. An empty 404 is the same answer a genuinely missing node gives,
            // which is the point: the caller cannot tell "no such node" from "not yours".
            return ResponseEntity.status(reason.status()).build();
        }
        // The gate supplies its own sentence (T-5.3 requires the rule to be readable by the
        // learner it stops); everything else uses the reason's standing message.
        String message = reason == RefusalReason.GATED && refused.detail() != null
            ? refused.detail()
            : reason.message();
        return ResponseEntity.status(reason.status())
            .body(Map.of("error", Map.of("code", reason.code(), "message", message)));
    }
}
