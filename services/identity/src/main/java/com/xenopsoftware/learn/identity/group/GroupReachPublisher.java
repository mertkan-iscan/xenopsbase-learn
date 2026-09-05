package com.xenopsoftware.learn.identity.group;

import com.xenopsoftware.learn.common.messaging.Outbox;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Announces which groups reach a person (T-9.8, and what T-5.5 was waiting for).
 *
 * <p><b>Reach, not membership, and identity is the only module that can compute it.</b> A group
 * assignment reaches the members of that group and of everything inside it — containment is what
 * the tree means, and it is the rule role assignments already follow (T-2.3). The tree lives here,
 * bounded by {@link GroupHierarchy#MAX_DEPTH}, so the walk happens here and consumers receive the
 * answer. A consumer that received raw membership would have to rebuild the tree to use it, which
 * is the copy ADR-0109 forbids wearing a different hat.
 *
 * <p><b>The whole reach set, not a delta.</b> An event saying "added to Engineering" makes a
 * consumer derive the new set from its own previous state, so one lost or duplicated message
 * leaves it permanently wrong with nothing to detect it. Sending the full set makes every message
 * self-contained: applying it twice is applying it once, which is exactly the idempotency the
 * at-least-once bus requires, achieved by the message shape rather than by the handler being
 * careful.
 *
 * <p>Written to the outbox inside the caller's transaction, so a membership change that rolls back
 * announces nothing.
 */
@Component
public class GroupReachPublisher {

    /** What a consumer switches on. Stable forever: it outlives any class name. */
    public static final String TYPE = "group.reach.changed";

    /** Under identity's stream (see {@code Streams}). */
    public static final String SUBJECT = "identity.group.reach";

    private final Outbox outbox;
    private final GroupHierarchy hierarchy;
    private final GroupMembershipRepository memberships;
    private final JsonMapper json = JsonMapper.builder().build();

    public GroupReachPublisher(Outbox outbox, GroupHierarchy hierarchy,
            GroupMembershipRepository memberships) {
        this.outbox = outbox;
        this.hierarchy = hierarchy;
        this.memberships = memberships;
    }

    /**
     * Announces this person's current reach.
     *
     * <p>Called after any change to their memberships, from inside the same transaction.
     */
    public void announce(UUID userId) {
        Set<UUID> reaching = new LinkedHashSet<>();
        for (GroupMembership membership : memberships.findAll()) {
            if (membership.getUserId().equals(userId)) {
                reaching.add(membership.getGroupId());
                // Up the tree: an assignment on any ancestor of a group they are in reaches them.
                reaching.addAll(hierarchy.ancestorIds(membership.getGroupId()));
            }
        }
        outbox.publish(SUBJECT, TYPE, json.writeValueAsString(java.util.Map.of(
            "tenantId", TenantContext.require(),
            "learnerId", userId.toString(),
            "groupIds", reaching.stream().map(UUID::toString).toList())));
    }
}
