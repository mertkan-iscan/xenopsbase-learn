package com.xenopsoftware.learn.identity.authz;

import java.util.UUID;

/**
 * One permission held at one place (T-2.3): the scope type, and the group or course it points
 * at. {@code targetId} is null exactly when {@link AssignmentScopeType#isUnbounded()}.
 */
public record ScopeGrant(AssignmentScopeType type, UUID targetId) {

    public static ScopeGrant tenantWide() {
        return new ScopeGrant(AssignmentScopeType.TENANT, null);
    }

    public static ScopeGrant overGroup(UUID groupId) {
        return new ScopeGrant(AssignmentScopeType.GROUP, groupId);
    }

    public static ScopeGrant overCourse(UUID courseId) {
        return new ScopeGrant(AssignmentScopeType.COURSE, courseId);
    }
}
