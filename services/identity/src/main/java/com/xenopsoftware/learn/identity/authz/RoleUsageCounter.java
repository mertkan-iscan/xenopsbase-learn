package com.xenopsoftware.learn.identity.authz;

import java.util.UUID;

/**
 * How many assignments point at a role — the number a delete has to refuse with (T-2.2).
 *
 * <p>A port, because {@code role_assignment} is T-2.3. The refusal logic is finished and tested
 * against a counter that returns a real number; production simply cannot produce a non-zero one
 * yet, and T-2.3 replaces one bean rather than revisiting the delete path.
 */
public interface RoleUsageCounter {

    long assignmentsOf(UUID roleId);
}
