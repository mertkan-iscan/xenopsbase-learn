package com.xenopsoftware.learn.reporting.web.rest;

import com.xenopsoftware.learn.reporting.telemetry.HeartbeatBatch;
import com.xenopsoftware.learn.reporting.telemetry.HeartbeatIngestService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the player posts what it watched (T-3.6).
 *
 * <h2>On analytics, and that is the criterion rather than a detail</h2>
 *
 * This endpoint is on {@code reporting} and not on core, so the most write-heavy workload in the
 * product cannot slow the database a learner's playback and progress depend on. The other half of
 * the same property is enforced structurally: an ArchUnit rule forbids any module from depending
 * on this one, so nothing on a learner's request path can call in here even by accident (T-9.7).
 *
 * <h2>202, not 200</h2>
 *
 * Accepted means written down, and deliberately not "counted towards your progress" — merging
 * into watched intervals is T-3.7's and happens afterwards. A 200 would invite a client to treat
 * the response as confirmation of completion, which is exactly the claim ADR-0107 refuses to let
 * a client make.
 *
 * <h2>No permission check, and the reason is narrow</h2>
 *
 * A learner posting what they themselves watched needs no grant — the token is the whole
 * credential, the tenant comes from it, and the subject is taken from it rather than from the
 * body, so a caller cannot post heartbeats as somebody else. There is no wider version of this
 * endpoint to gate: it writes only for the caller.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryResource {

    private final HeartbeatIngestService ingest;

    public TelemetryResource(HeartbeatIngestService ingest) {
        this.ingest = ingest;
    }

    /** What was accepted, so a client can tell a partial acceptance from a silent drop. */
    public record AcceptedView(int samples) {}

    @PostMapping("/playback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedView playback(@RequestBody HeartbeatBatch batch) {
        return new AcceptedView(ingest.record(batch, subject()));
    }

    private static String subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // The security chain authenticates everything under /api, so this is a wiring
            // mistake rather than a caller's error.
            throw new IllegalStateException("A heartbeat needs a verified caller");
        }
        return token.getToken().getSubject();
    }
}
