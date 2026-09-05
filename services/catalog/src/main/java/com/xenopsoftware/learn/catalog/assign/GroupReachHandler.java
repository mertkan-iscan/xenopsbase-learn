package com.xenopsoftware.learn.catalog.assign;

import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.time.Instant;
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

        List<UUID> groupIds = new ArrayList<>();
        for (JsonNode groupId : body.get("groupIds")) {
            groupIds.add(UUID.fromString(groupId.asString()));
        }

        // Replace, do not merge. The message is the whole truth about this learner, so anything
        // here that it does not mention is a group they have left -- and merging would leave that
        // row behind forever, quietly assigning them work from a department they are no longer in.
        //
        // WHAT CHANGED IN T-5.6, AND WHY IT IS NOT A DELETE-THEN-INSERT ANY MORE. The row now
        // carries reached_at, which is what makes "within 30 days of joining" computable. Deleting
        // and reinserting the whole set would move that date forward every time anything about
        // this person's memberships changed -- so somebody added to a second, unrelated group in
        // month eleven would silently get a fresh thirty days on the training they were already
        // late for. Rows that survive the message keep the date they had; only the ones the
        // message does not mention are removed, and only genuinely new ones get today's.
        Instant now = Instant.now();
        if (groupIds.isEmpty()) {
            jdbc.update("DELETE FROM learner_group_reach WHERE tenant_id = ? AND learner_id = ?",
                tenantId, learnerId);
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(groupIds.size(), "?"));
            Object[] arguments = new Object[groupIds.size() + 2];
            arguments[0] = tenantId;
            arguments[1] = learnerId;
            int index = 2;
            for (UUID groupId : groupIds) {
                arguments[index++] = groupId;
            }
            jdbc.update("""
                DELETE FROM learner_group_reach
                 WHERE tenant_id = ? AND learner_id = ? AND group_id NOT IN (%s)
                """.formatted(placeholders), arguments);

            List<Object[]> rows = new ArrayList<>();
            for (UUID groupId : groupIds) {
                rows.add(new Object[] {tenantId, learnerId, groupId,
                    java.sql.Timestamp.from(now)});
            }
            jdbc.batchUpdate("""
                INSERT INTO learner_group_reach (tenant_id, learner_id, group_id, reached_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, learner_id, group_id) DO NOTHING
                """, rows);
        }
        LOG.debug("Reach for {} in {} is now {} group(s)", learnerId, tenantId, groupIds.size());
    }
}
