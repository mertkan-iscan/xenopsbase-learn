package com.xenopsoftware.learn.identity.authz;

import java.util.Set;
import java.util.UUID;

/**
 * How far a caller can exercise one permission (T-2.3).
 *
 * <p>{@code wholeTenant} is not the same as "every group id we happened to list": a tenant-scoped
 * grant reaches groups created after the question was asked, and materialising the tenant's whole
 * tree to say so would be both slower and wrong the moment somebody adds a group.
 *
 * <p><b>One set per kind of narrow target, not one set of ids</b> (T-6.1 added banks beside
 * courses). A single {@code targetIds} would make {@link #includesCourse} and {@link #includesBank}
 * the same method, and a grant over a course would then authorise an edit to a bank that happened
 * to share an id — which is a coincidence a UUID makes unlikely and a test fixture makes certain.
 */
public record Reach(boolean wholeTenant, Set<UUID> groupIds, Set<UUID> courseIds,
        Set<UUID> bankIds) {

    public static Reach nothing() {
        return new Reach(false, Set.of(), Set.of(), Set.of());
    }

    public boolean isEmpty() {
        return !wholeTenant && groupIds.isEmpty() && courseIds.isEmpty() && bankIds.isEmpty();
    }

    public boolean includesGroup(UUID groupId) {
        return wholeTenant || groupIds.contains(groupId);
    }

    public boolean includesCourse(UUID courseId) {
        return wholeTenant || courseIds.contains(courseId);
    }

    public boolean includesBank(UUID bankId) {
        return wholeTenant || bankIds.contains(bankId);
    }
}
