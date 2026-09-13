package com.xenopsoftware.learn.catalog.home;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
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
/*
 * THE NESTED RECORDS CARRY EXPLICIT SCHEMA NAMES, AND IT IS NOT DECORATION.
 *
 * springdoc keys components.schemas on a record's SIMPLE name. Four of the records below
 * -- CourseView, ModuleView, NodeView, ItemView -- share theirs with the authoring records
 * in `course` and `content`, so whichever springdoc reached last won and the other was
 * silently replaced. The published description said the home screen returns authoring
 * shapes, and the fields it is actually built from (locked, lockedReason, percent,
 * resumeSecond, state) appeared in no schema at all.
 *
 * Nothing failed. The spec generated, the contract gate passed -- it compares the spec to
 * the service, and the service really did serve what the spec described for the ONE name
 * that survived. Only a client generated from it was wrong, which is a frontend bug with
 * its cause three services away.
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
    @Schema(name = "HomeCourse")
    public record CourseView(UUID courseId, String title, LocalDate dueOn, boolean overdue,
                             Integer cycleNumber, int percentComplete, boolean completed,
                             List<UUID> sources, List<ModuleView> modules) {}

    @Schema(name = "HomeModule")
    public record ModuleView(UUID moduleId, String title, boolean locked, String lockedReason,
                             List<NodeView> nodes) {}

    /**
     * @param state        {@code COMPLETE}, {@code IN_PROGRESS}, {@code AVAILABLE} or
     *                     {@code LOCKED}
     * @param lockedReason T-5.3's sentence, verbatim, or null when nothing is in the way
     * @param resumeSecond where playback picks up, from the same derivation reporting uses (T-3.7)
     */
    /**
     * @param contentRef the one id inside the content item's payload — the video asset, the
     *                   package, the test — or null when the type has none or the item has gone.
     *                   <b>Added because a learner could not open a package without it</b> (T-4.4):
     *                   the screen knew this was a {@code scorm} node and had no way to learn WHICH
     *                   package, and there is no learner-facing endpoint that reads a content item.
     *                   Sending the whole payload instead would publish whatever a future type
     *                   chose to put in one; sending the id it references is the fact a client can
     *                   act on.
     */
    @Schema(name = "HomeNode")
    public record NodeView(UUID nodeId, String title, String type, boolean required, String state,
                           String lockedReason, int percent, int resumeSecond, UUID contentRef) {}

    /** An assignment that is not a course: a module, a node, or a content item on its own. */
    @Schema(name = "HomeItem")
    public record ItemView(String referenceType, UUID referenceId, String title, String state,
                           LocalDate dueOn, boolean overdue, Integer cycleNumber, int percent,
                           int resumeSecond, List<UUID> sources) {}
}
