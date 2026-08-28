package com.xenopsoftware.learn.identity.authz;

import java.util.Set;
import java.util.UUID;

/**
 * How far a caller can exercise one permission (T-2.3).
 *
 * <p>{@code wholeTenant} is not the same as "every group id we happened to list": a tenant-scoped
 * grant reaches groups created after the question was asked, and materialising the tenant's whole
 * tree to say so would be both slower and wrong the moment somebody adds a group.
 */
public record Reach(boolean wholeTenant, Set<UUID> groupIds, Set<UUID> courseIds) {

    public static Reach nothing() {
        return new Reach(false, Set.of(), Set.of());
    }

    public boolean isEmpty() {
        return !wholeTenant && groupIds.isEmpty() && courseIds.isEmpty();
    }

    public boolean includesGroup(UUID groupId) {
        return wholeTenant || groupIds.contains(groupId);
    }

    public boolean includesCourse(UUID courseId) {
        return wholeTenant || courseIds.contains(courseId);
    }
}
