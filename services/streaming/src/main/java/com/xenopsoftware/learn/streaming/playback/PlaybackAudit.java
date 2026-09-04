package com.xenopsoftware.learn.streaming.playback;

import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every refused playback request, with the reason (T-3.4).
 *
 * <p>Refusals and not grants. A grant is one row per viewer per five minutes on the learner hot
 * path, and what a learner watched is already E7's subject (T-7.1) — ADR-0107 derives completion
 * from it. Writing it here as well would put a second write on the path ADR-0101 exists to keep
 * light, to produce a worse copy of a dataset that already has an owner. A refusal has no such
 * owner, happens rarely, and is the thing somebody has to explain.
 *
 * <p>Its own table in this service's own schema, rather than identity's {@code audit_log},
 * because no module reads another module's schema — the rule the README states. The two are
 * joined on {@code app_user.id} when a question needs both.
 *
 * <p>The actor is {@code app_user.id} and never an IdP {@code sub}, which is a rule this
 * service's schema test enforces rather than a convention to remember (ADR-0104): a {@code sub}
 * is a link identity may repair, so one stored here would go stale silently and the audit trail
 * would quietly stop pointing at anybody. {@link ViewerDirectory} is what resolves it, and it is
 * asked here rather than on the way to a token because refusals are rare and tokens are not.
 *
 * <p>Two properties borrowed from identity's {@code AuditLogger}, for the reasons it learned
 * them: the write happens in its own transaction, because a refusal that rolls its caller back
 * would destroy the only record of itself; and it is best-effort, because an audit failure must
 * never turn a refusal into a grant.
 */
@Component
public class PlaybackAudit {

    private static final Logger LOG = LoggerFactory.getLogger(PlaybackAudit.class);

    private final JdbcTemplate jdbc;

    public PlaybackAudit(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * @param actorUserId the caller as {@code app_user.id}, or null when identity could not be
     *                    asked. Null loses the person and keeps the reason, which is the right
     *                    way round: "learners are being refused for want of a grant" is still
     *                    answerable without knowing which learner.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefusal(String tenantId, UUID actorUserId, UUID nodeId, RefusalReason reason,
            String detail) {
        try {
            jdbc.update("""
                INSERT INTO playback_refusal (id, tenant_id, actor_user_id, node_id, reason, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), tenantId, actorUserId, nodeId, reason.name(), detail);
        } catch (RuntimeException e) {
            LOG.error("Could not audit the refused playback token for node {} ({}) -- the "
                + "refusal still stands", nodeId, reason, e);
        }
    }
}
