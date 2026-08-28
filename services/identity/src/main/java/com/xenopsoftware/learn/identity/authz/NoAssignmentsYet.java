package com.xenopsoftware.learn.identity.authz;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Nothing can hold a role until assignments exist (T-2.3), so nothing is in use. Honest rather
 * than convenient: it answers zero because zero is currently true, not to wave deletes through.
 */
@Component
public class NoAssignmentsYet implements RoleUsageCounter {

    @Override
    public long assignmentsOf(UUID roleId) {
        return 0;
    }
}
