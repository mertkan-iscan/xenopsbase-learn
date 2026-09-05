package com.xenopsoftware.learn.catalog.assign;

import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code learner_group_reach} current from identity's events (T-9.8, T-5.5).
 *
 * <p><b>This is the writer T-5.5 said did not exist yet.</b> Until now a group assignment reached
 * nobody, which was the correct answer for a platform holding no membership data; now it reaches
 * whoever identity says it does.
 *
 * <p><b>Idempotent by the shape of the message, not by care.</b> The event carries the learner's
 * WHOLE reach set, so applying it is "make the rows equal this" — delete what is there, insert what
 * arrived. Doing that twice leaves the same state as doing it once, which is what makes an
 * at-least-once bus safe here without the handler reasoning about duplicates at all. A delta event
 * ("added to Engineering") would need exactly that reasoning, and would be permanently wrong after
 * one lost message with nothing to detect it.
 *
 * <p>Catalog owns this table outright and identity never touches it (ADR-0109). What crosses the
 * boundary is an event, not a query.
 */
@Component
public class GroupReachHandler implements MessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GroupReachHandler.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public GroupReachHandler(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public String subject() {
        return "identity.group.reach";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        String tenantId = body.get("tenantId").asString();
        UUID learnerId = UUID.fromString(body.get("learnerId").asString());

        List<Object[]> rows = new ArrayList<>();
        for (JsonNode groupId : body.get("groupIds")) {
            rows.add(new Object[] {tenantId, learnerId, UUID.fromString(groupId.asString())});
        }

        // Replace, do not merge. The message is the whole truth about this learner, so anything
        // here that it does not mention is a group they have left -- and merging would leave that
        // row behind forever, quietly assigning them work from a department they are no longer in.
        jdbc.update("DELETE FROM learner_group_reach WHERE tenant_id = ? AND learner_id = ?",
            tenantId, learnerId);
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                INSERT INTO learner_group_reach (tenant_id, learner_id, group_id) VALUES (?, ?, ?)
                """, rows);
        }
        LOG.debug("Reach for {} in {} is now {} group(s)", learnerId, tenantId, rows.size());
    }
}
