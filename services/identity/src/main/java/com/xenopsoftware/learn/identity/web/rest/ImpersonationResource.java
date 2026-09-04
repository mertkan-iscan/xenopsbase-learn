package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.audit.CurrentUser;
import com.xenopsoftware.learn.identity.impersonation.Impersonation;
import com.xenopsoftware.learn.identity.impersonation.ImpersonationFilter;
import com.xenopsoftware.learn.identity.impersonation.ImpersonationSessions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Support impersonation, from our side (T-2.8).
 *
 * <p>Starting a session returns an id and nothing else that matters: the id goes in the
 * {@code X-Impersonate-Session} header of subsequent requests, and every one of them is judged
 * against the row behind it. There is deliberately no token — a minted credential would be
 * something that keeps working after the session was ended, and "ended" has to mean ended.
 *
 * <p>There is also no renew. A session expires and a longer investigation is a second session
 * with its own reason, which is the record the customer should get.
 */
@RestController
@RequestMapping("/api/v1/platform/impersonations")
public class ImpersonationResource {

    private final ImpersonationSessions sessions;
    private final CurrentUser currentUser;

    public ImpersonationResource(ImpersonationSessions sessions, CurrentUser currentUser) {
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    /**
     * @param writable whether this session may change the customer's data. Requesting it needs a
     *                 second permission, checked at the door below rather than inside — so a
     *                 request for a writable session from somebody who only holds
     *                 {@code support:impersonate} is refused outright instead of quietly
     *                 downgraded into a session that is not the one they asked for
     */
    public record StartRequest(String tenantId, UUID userId, String reason, boolean writable) {}

    /** The header value to send, and the two things a console must show while it is set. */
    public record StartedView(UUID sessionId, String header, String tenantId, UUID impersonatedUserId,
                              boolean writable, Instant expiresAt) {}

    @PostMapping
    @PreAuthorize("hasPermission('support', 'impersonate')"
        + " and (#request.writable() == false or hasPermission('support', 'impersonate_write'))")
    public StartedView start(@P("request") @RequestBody StartRequest request) {
        Impersonation session = sessions.start(request.tenantId(), request.userId(),
            request.reason(), request.writable());
        return new StartedView(session.sessionId(), ImpersonationFilter.HEADER, session.tenantId(),
            session.impersonatedUserId(), session.writable(), session.expiresAt());
    }

    /** Ends a session now, rather than waiting for its expiry. Idempotent. */
    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasPermission('support', 'impersonate')")
    public void end(@PathVariable UUID sessionId) {
        sessions.end(sessionId, currentUser.requireId(), ImpersonationSessions.ENDED_BY_ACTOR);
    }

    /**
     * The caller's own sessions, and only theirs. Not an oversight: an engineer needs to find the
     * session they left open, and nobody needs a platform-wide list here — that question is the
     * audit log's, which is queryable across tenants by the people who own it.
     */
    @GetMapping
    @PreAuthorize("hasPermission('support', 'impersonate')")
    public List<ImpersonationSessions.SessionView> mine() {
        return sessions.byActor(currentUser.requireId());
    }

}
