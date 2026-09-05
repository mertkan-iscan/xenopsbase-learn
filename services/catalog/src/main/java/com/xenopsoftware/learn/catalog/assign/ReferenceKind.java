package com.xenopsoftware.learn.catalog.assign;

/**
 * What an assignment points at (T-5.5).
 *
 * <p>Any level of the tree, plus a bare content item. THIS is the design test the issue names:
 * "assign one video to one student" and "assign a course to a group" are the same feature with
 * different values in two columns, and the moment they are two features there are four code paths.
 */
public enum ReferenceKind {

    COURSE,
    MODULE,
    NODE,

    /**
     * A content item with no course around it.
     *
     * <p>The case that keeps the model honest. It has no structure, so nothing to pin a version
     * of and no gates to evaluate -- a learner either has it or does not.
     */
    CONTENT_ITEM
}
