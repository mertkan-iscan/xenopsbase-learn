package com.xenopsoftware.learn.streaming.playback;

/**
 * Who is asking to watch (T-3.4).
 *
 * <p>The subject is the IdP {@code sub} from the verified token, not {@code app_user.id}, and
 * the distinction matters in exactly one direction. A {@code sub} is fine to <b>sign into a
 * token</b> — the token lives for five minutes and is not a row anybody joins against later.
 * It is not fine to <b>store</b>: identity may repair the link (T-1.7 has a script for it), so a
 * {@code sub} written into this service's tables is a reference that goes stale in silence, and
 * this service's schema test refuses one outright (ADR-0104).
 *
 * <p>So nothing here resolves {@code app_user.id} on the way to a token: every check is answered
 * from the tenant and subject, or by a service that can resolve them itself. The hop happens
 * only when a refusal is being written down, where {@link ViewerDirectory} turns the subject
 * into the durable id the row keeps.
 */
public record Viewer(String tenantId, String subject) {

    public Viewer {
        if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("A viewer needs both a tenant and a subject");
        }
    }
}
