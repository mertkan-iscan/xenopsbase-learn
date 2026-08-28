package com.xenopsoftware.learn.identity.authz;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The real answer to "is this role in use" (T-2.3), replacing the placeholder that could only
 * ever say zero. T-2.2's delete path was written against this port and needs no change.
 */
@Component
public class AssignmentRoleUsage implements RoleUsageCounter {

    private final RoleAssignmentRepository assignments;

    public AssignmentRoleUsage(RoleAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    @Override
    public long assignmentsOf(UUID roleId) {
        return assignments.countByRoleId(roleId);
    }
}
