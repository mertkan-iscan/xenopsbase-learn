package com.xenopsoftware.learn.catalog.assign;

/**
 * Who an assignment is for (T-5.5).
 *
 * <p>Three, and one model rather than three features. The reason is arithmetic: separate paths for
 * "assign to a person" and "assign to a group" become four paths once a company-wide assignment
 * arrives, and the one nobody exercises is the one that silently stops reaching people.
 */
public enum TargetKind {

    /** One person, by app_user.id. */
    USER,

    /**
     * A group, and everything inside it. Containment is what the tree means and it is the rule
     * role assignments already follow (T-2.3): assigning to Engineering reaches its departments.
     */
    GROUP,

    /** Everybody in the company. Carries no target id -- "everyone" is not a row. */
    TENANT
}
