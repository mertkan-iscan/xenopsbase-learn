package com.xenopsoftware.learn.catalog.home;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the learner home screen draws, in one answer (T-5.8).
 *
 * <h2>Why the shape says what the screen should show, including when there is nothing</h2>
 *
 * {@link #state} is the discriminator, and it exists so that "nothing here" is a state the server
 * decided rather than a blank page a client fell into. A screen that renders an empty list the same
 * way for "you have not been assigned anything yet" and "you have finished everything" is a screen
 * that tells a new starter their platform is broken.
 *
 * <h2>What is not here</h2>
 *
 * No copy. The sentences a learner reads are the client's, except the one thing only the server can
 * say: <b>why something is locked</b> (T-5.3's explanation), which is carried verbatim so that two
 * clients cannot invent two different reasons for the same gate.
 *
 * @param state       {@code NOTHING_ASSIGNED}, {@code ALL_DONE} or {@code READY}
 * @param summary     the counts a header line is built from
 * @param nextUp      the one thing to offer as "continue", or null when there is nothing to do
 * @param courses     assigned courses, expanded to modules and nodes
 * @param items       assigned things that are not courses — a module, a single node, or a content
 *                    item — flat, because there is no structure to draw around them
 * @param generatedAt when this answer was assembled. Exposed because it may be served from a cache
 *                    and a screen that shows a time should show the one it is describing
 */
public record HomeView(String state, Summary summary, NextUp nextUp, List<CourseView> courses,
                       List<ItemView> items, Instant generatedAt) {

    /** Nobody has assigned this person anything: the first-run state, and not an error. */
    public static final String NOTHING_ASSIGNED = "NOTHING_ASSIGNED";

    /** Everything assigned is finished. Worth its own state so a screen can say so. */
    public static final String ALL_DONE = "ALL_DONE";

    /** There is something to do. */
    public static final String READY = "READY";

    /**
     * @param dueSoon obligations due within a week that are not yet overdue — the number a header
     *                line exists to make visible before it becomes the overdue one
     */
    public record Summary(int assigned, int completed, int inProgress, int overdue, int dueSoon) {}

    /**
     * The one thing to put behind a "continue" button.
     *
     * <p>Chosen server-side rather than left to the client, because the rule involves everything
     * this answer knows — what is locked, what is overdue, what was already started — and two
     * clients choosing differently would be two products.
     */
    public record NextUp(UUID courseId, String courseTitle, UUID nodeId, String title, int percent,
                         int resumeSecond, LocalDate dueOn, boolean overdue) {}

    /**
     * @param percentComplete required nodes finished, as a percentage — the structural measure a
     *                        gate uses, not an average of how far into each video somebody is
     * @param sources         the assignments this obligation came from, so "why do I have this" is
     *                        answerable (T-5.5)
     */
    public record CourseView(UUID courseId, String title, LocalDate dueOn, boolean overdue,
                             Integer cycleNumber, int percentComplete, boolean completed,
                             List<UUID> sources, List<ModuleView> modules) {}

    public record ModuleView(UUID moduleId, String title, boolean locked, String lockedReason,
                             List<NodeView> nodes) {}

    /**
     * @param state        {@code COMPLETE}, {@code IN_PROGRESS}, {@code AVAILABLE} or
     *                     {@code LOCKED}
     * @param lockedReason T-5.3's sentence, verbatim, or null when nothing is in the way
     * @param resumeSecond where playback picks up, from the same derivation reporting uses (T-3.7)
     */
    public record NodeView(UUID nodeId, String title, String type, boolean required, String state,
                           String lockedReason, int percent, int resumeSecond) {}

    /** An assignment that is not a course: a module, a node, or a content item on its own. */
    public record ItemView(String referenceType, UUID referenceId, String title, String state,
                           LocalDate dueOn, boolean overdue, Integer cycleNumber, int percent,
                           int resumeSecond, List<UUID> sources) {}
}
