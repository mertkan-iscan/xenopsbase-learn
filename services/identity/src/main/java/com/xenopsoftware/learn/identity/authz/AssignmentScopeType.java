package com.xenopsoftware.learn.identity.authz;

/**
 * Where an assignment lets its role be exercised (T-2.3).
 *
 * <p>Ordered by width, and the width is what makes two things work: overlapping assignments
 * union with the widest winning, and a permission cannot be granted below its catalog floor
 * ({@link Permission#minScope()}) — {@code role:manage} at GROUP scope would be a role edit
 * performed by someone who cannot see everyone the role affects.
 */
public enum AssignmentScopeType {

    /** One course. Narrower than a group: catalog owns the id, so nothing here can check it. */
    COURSE(1),

    /** One group and everything beneath it — {@code GroupHierarchy.subtreeIds}. */
    GROUP(2),

    /** The whole company. */
    TENANT(3),

    /** Platform staff, across companies. Seeded (T-2.7), never granted at runtime. */
    PLATFORM(4);

    private final int width;

    AssignmentScopeType(int width) {
        this.width = width;
    }

    public int width() {
        return width;
    }

    /** Whether an assignment at this scope may carry a permission with this catalog floor. */
    public boolean covers(PermissionScope floor) {
        return width >= widthOf(floor);
    }

    /** {@code true} for TENANT and PLATFORM, which point at everything rather than at a row. */
    public boolean isUnbounded() {
        return this == TENANT || this == PLATFORM;
    }

    private static int widthOf(PermissionScope floor) {
        return switch (floor) {
            // SELF sits below every assignment scope: a permission floored at SELF is one
            // anybody may exercise over their own things, so any scope covers it.
            case SELF -> 0;
            case GROUP -> GROUP.width;
            case TENANT -> TENANT.width;
            case PLATFORM -> PLATFORM.width;
        };
    }
}
