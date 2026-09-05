package com.xenopsoftware.learn.catalog.assign;

import com.xenopsoftware.learn.catalog.content.ContentItem;
import com.xenopsoftware.learn.catalog.due.AssignmentCycle;
import com.xenopsoftware.learn.catalog.due.CycleService;
import com.xenopsoftware.learn.catalog.due.Deadlines;
import com.xenopsoftware.learn.catalog.due.DueKind;
import com.xenopsoftware.learn.catalog.due.Reminders;
import com.xenopsoftware.learn.catalog.content.ContentItemRepository;
import com.xenopsoftware.learn.catalog.structure.CourseModuleRepository;
import com.xenopsoftware.learn.catalog.structure.CourseNodeRepository;
import com.xenopsoftware.learn.catalog.structure.CourseRepository;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Making, withdrawing and reading assignments (T-5.5).
 *
 * <p>Three properties worth reading for. <b>A group assignment is one row</b>, resolved to people
 * when somebody reads. <b>A learner reached twice has one obligation</b>, not two. And <b>an
 * assignment pins the structure version it was made against</b>, so "this course changed since you
 * assigned it" is a query rather than a guess.
 */
@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final LearnerGroupReach reach;
    private final CourseRepository courses;
    private final CourseModuleRepository modules;
    private final CourseNodeRepository nodes;
    private final ContentItemRepository items;
    private final com.xenopsoftware.learn.catalog.version.CourseVersionService versions;
    private final CycleService cycles;
    private final Reminders reminders;
    private final LearnerProfiles profiles;

    public AssignmentService(AssignmentRepository assignments, LearnerGroupReach reach,
            CourseRepository courses, CourseModuleRepository modules, CourseNodeRepository nodes,
            ContentItemRepository items,
            com.xenopsoftware.learn.catalog.version.CourseVersionService versions,
            CycleService cycles, Reminders reminders, LearnerProfiles profiles) {
        this.versions = versions;
        this.cycles = cycles;
        this.reminders = reminders;
        this.profiles = profiles;
        this.assignments = assignments;
        this.reach = reach;
        this.courses = courses;
        this.modules = modules;
        this.nodes = nodes;
        this.items = items;
    }

    /**
     * One thing to assign, as a caller states it.
     *
     * @param due             the deadline, or {@link Deadlines.DueSpec#none()}. Never inferred:
     *                        an obligation nobody put a date on has no date (T-5.6)
     * @param reminderOffsets days added to the due date at which to remind, so -14 is a fortnight
     *                        before and +7 is a nudge a week after it passed
     */
    public record Request(TargetKind targetType, UUID targetId, ReferenceKind referenceType,
                          UUID referenceId, Deadlines.DueSpec due, List<Integer> reminderOffsets) {

        public Request {
            due = due == null ? Deadlines.DueSpec.none() : due;
            reminderOffsets = reminderOffsets == null ? List.of() : List.copyOf(reminderOffsets);
        }

        /** The deadline-free assignment, which is still the common one. */
        public Request(TargetKind targetType, UUID targetId, ReferenceKind referenceType,
                UUID referenceId) {
            this(targetType, targetId, referenceType, referenceId, Deadlines.DueSpec.none(),
                List.of());
        }
    }

    /**
     * What a learner owes, after duplicates have collapsed (T-5.5's third criterion).
     *
     * @param sources every assignment that produced this obligation — usually one, and more when
     *                somebody is reached by two groups or by a group and by name. A UI shows one
     *                row and can still answer "why do I have this".
     */
    public record Obligation(ReferenceKind referenceType, UUID referenceId, Instant assignedAt,
                             Long pinnedVersion, List<UUID> sources, LocalDate dueOn,
                             boolean overdue, Integer cycleNumber) {}

    /**
     * Assigns one thing to one target.
     *
     * <p>The reference is checked here rather than by a foreign key, because a column that may
     * point at one of four tables cannot have one. That check also carries the rule a foreign key
     * could never have expressed: only a PUBLISHED content item accepts a new reference (T-5.1).
     */
    @Transactional
    public Assignment assign(Request request, UUID assignedBy) {
        Long pinned = validateAndPin(request.referenceType(), request.referenceId());
        if (request.targetType() != TargetKind.TENANT && request.targetId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A " + request.targetType() + " assignment needs a targetId");
        }
        Assignment assignment = Assignment.of(request.targetType(), request.targetId(),
            request.referenceType(), request.referenceId(), pinned, assignedBy);
        try {
            assignment.setDue(request.due());
        } catch (IllegalArgumentException badDeadline) {
            // A shape the CHECK constraint would refuse anyway. Refused here so the caller is told
            // what is wrong with their request rather than the name of a constraint.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, badDeadline.getMessage());
        }
        try {
            // saveAndFlush, not save. Hibernate defers the insert to commit, so a plain save()
            // would let the unique-index violation surface AFTER this method returned -- past the
            // catch below, out of the controller, and to the caller as a 500 about a constraint
            // rather than a 409 about their request. Flushing here is what makes the refusal
            // belong to the request that caused it.
            Assignment saved = assignments.saveAndFlush(assignment);
            if (!request.reminderOffsets().isEmpty()) {
                reminders.setOffsets(saved.getTenantId(), saved.getId(),
                    request.reminderOffsets());
            }
            // Cycle 1, now, rather than when somebody first reads. Not for correctness -- cycles
            // are derived and would be opened on demand anyway -- but so that "what is this
            // assignment's deadline" is answerable from the database immediately after it is made,
            // by a report that never calls this service.
            //
            // In THIS transaction, deliberately: the assignment it points at is not committed yet,
            // so the read path's REQUIRES_NEW opener cannot see it and the insert would fail on
            // the foreign key.
            cycles.openFirstCycle(saved);
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The partial unique index. Assigning the same thing to the same target twice is not
            // a stronger obligation, and the honest answer is that it is already assigned rather
            // than a second row that shows up twice on a learner's list.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "That is already assigned to this target");
        }
    }

    /**
     * Many at once, in one transaction (T-5.5's last criterion).
     *
     * <p><b>The reason this does not time out is the model, not a batch size.</b> Assigning a
     * course to a department of five thousand is ONE row, because the audience is resolved at
     * read time — so "bulk" here means many REFERENCES or many named people, and the work is
     * proportional to what was asked for rather than to who it lands on. A design that
     * materialised a row per member would need this endpoint to be a background job, and would
     * need a second job every time somebody joined the group.
     *
     * <p>One transaction: half-applied bulk assignment is the state nobody can reason about, and
     * the caller cannot tell which half.
     */
    @Transactional
    public List<Assignment> assignAll(List<Request> requests, UUID assignedBy) {
        List<Assignment> made = new ArrayList<>();
        for (Request request : requests) {
            made.add(assign(request, assignedBy));
        }
        return made;
    }

    @Transactional
    public void revoke(UUID assignmentId) {
        Assignment assignment = assignments.findById(assignmentId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No such assignment"));
        assignment.revoke();
        assignments.save(assignment);
    }

    /**
     * What this learner owes, with duplicates collapsed.
     *
     * <p><b>Two queries, whatever the size of the groups involved</b>: which groups reach them,
     * and everything live for those targets. Nothing here is per-member or per-group.
     *
     * <p>Somebody in two groups that were both assigned the same course has ONE obligation — the
     * earliest assignment wins the date, because the obligation started when the first one did,
     * and both are listed as sources so "why do I have this" is answerable.
     */
    @Transactional(readOnly = true)
    public List<Obligation> obligationsOf(UUID learnerId) {
        return obligationsOf(learnerId, Instant.now());
    }

    /**
     * The same, against a stated clock.
     *
     * <p>Its own method so a test can ask what this learner's list looks like the day after a
     * deadline without waiting for one, and so every obligation in one answer is reckoned against
     * the same instant — a list where one row went overdue between two calls to {@code now()} is a
     * list nobody can explain.
     */
    @Transactional(readOnly = true)
    public List<Obligation> obligationsOf(UUID learnerId, Instant now) {
        String tenantId = TenantContext.require();
        List<UUID> groups = reach.of(tenantId, learnerId);
        Map<UUID, Instant> reachedAt = reach.reachedAtOf(tenantId, learnerId);
        // The learner's OWN timezone, not the server's (T-5.6). A deadline expires when the day
        // ends where they are, so somebody in Auckland finishing at 23:00 on the due date is on
        // time -- and a server reckoning in UTC would have filed them as late that morning.
        ZoneId zone = Deadlines.zoneOf(profiles.of(tenantId, learnerId)
            .map(LearnerProfiles.Profile::timeZone).orElse(null));
        Instant firstSeen = profiles.of(tenantId, learnerId)
            .map(LearnerProfiles.Profile::firstSeenAt).orElse(Instant.EPOCH);

        Map<String, Obligation> byReference = new LinkedHashMap<>();
        for (Assignment assignment : assignments.reaching(learnerId, groups)) {
            String key = assignment.getReferenceType() + ":" + assignment.getReferenceId();
            LocalDate due = dueDateFor(assignment, learnerId, reachedAt, firstSeen, zone, now)
                .orElse(null);
            Integer cycleNumber = assignment.due().kind() == DueKind.NONE ? null
                : cycles.currentCycle(assignment, now).map(AssignmentCycle::getCycleNumber)
                    .orElse(null);
            boolean overdue = due != null && Deadlines.isOverdue(due, zone, now);

            Obligation existing = byReference.get(key);
            if (existing == null) {
                byReference.put(key, new Obligation(assignment.getReferenceType(),
                    assignment.getReferenceId(), assignment.getAssignedAt(),
                    assignment.getPinnedVersion(),
                    new ArrayList<>(List.of(assignment.getId())), due, overdue, cycleNumber));
                continue;
            }
            List<UUID> sources = new ArrayList<>(existing.sources());
            sources.add(assignment.getId());
            // The query returns them oldest first, so the existing entry already holds the
            // earliest date and the version pinned at that moment. Keeping the LATER pin would
            // mean a learner's obligation quietly re-pointing at a newer structure because
            // somebody assigned the same course to a second group.
            //
            // THE DEADLINE IS THE OTHER WAY ROUND: the EARLIEST wins. Somebody reached by two
            // assignments for the same course owes it by the sooner of the two dates -- a second,
            // laxer assignment must not quietly extend a deadline the first one set. A row with a
            // date always beats a row without one for the same reason.
            LocalDate keptDue = earlier(existing.dueOn(), due);
            byReference.put(key, new Obligation(existing.referenceType(), existing.referenceId(),
                existing.assignedAt(), existing.pinnedVersion(), sources, keptDue,
                keptDue != null && Deadlines.isOverdue(keptDue, zone, now),
                keptDue == null || keptDue.equals(existing.dueOn())
                    ? existing.cycleNumber() : cycleNumber));
        }
        return List.copyOf(byReference.values());
    }

    private static LocalDate earlier(LocalDate one, LocalDate other) {
        if (one == null) {
            return other;
        }
        if (other == null) {
            return one;
        }
        return one.isBefore(other) ? one : other;
    }

    /**
     * When this learner's copy of an obligation is due.
     *
     * <p>The shared date where the assignment has one; their own where it counts from when they
     * were reached (T-5.6, question 2). Reached is the LATER of "the assignment was made" and
     * "this learner came into its scope", so a new onboarding course does not land on the existing
     * department already thirty days overdue.
     */
    private java.util.Optional<LocalDate> dueDateFor(Assignment assignment, UUID learnerId,
            Map<UUID, Instant> reachedAt, Instant firstSeen, ZoneId zone, Instant now) {
        if (assignment.due().kind() == DueKind.NONE) {
            return java.util.Optional.empty();
        }
        java.util.Optional<AssignmentCycle> cycle = cycles.currentCycle(assignment, now);
        if (cycle.isEmpty()) {
            return java.util.Optional.empty();
        }
        Instant reached = switch (assignment.getTargetType()) {
            case USER -> assignment.getAssignedAt();
            case GROUP -> reachedAt.getOrDefault(assignment.getTargetId(),
                assignment.getAssignedAt());
            case TENANT -> firstSeen;
        };
        if (reached.isBefore(assignment.getAssignedAt())) {
            reached = assignment.getAssignedAt();
        }
        return Deadlines.dueDateForLearner(assignment.due(),
            cycle.get().getOpensAt().atZone(zone).toLocalDate(), cycle.get().getDueOn(),
            reached.atZone(zone).toLocalDate());
    }

    @Transactional(readOnly = true)
    public List<Assignment> all() {
        return assignments.findByRevokedAtIsNullOrderByAssignedAtDesc();
    }

    /**
     * Whether the thing an assignment points at has changed structurally since it was made.
     *
     * <p>What the pin buys, and the honest limit of it: this answers "the course is not what you
     * assigned any more", which is worth surfacing to an administrator. It does NOT let anyone
     * serve the old structure — immutable published versions are T-5.7's, and a column that
     * looked like it guaranteed a snapshot would be worse than none.
     */
    @Transactional(readOnly = true)
    public boolean hasDrifted(Assignment assignment) {
        if (assignment.getPinnedVersion() == null) {
            return false;
        }
        return courseOf(assignment.getReferenceType(), assignment.getReferenceId())
            .map(version -> !version.equals(assignment.getPinnedVersion()))
            .orElse(true);
    }

    /**
     * Checks the reference exists in this tenant and returns the version to pin.
     *
     * <p>One method for all four kinds, which is the point of the model: the switch is four lines
     * here rather than four code paths everywhere else.
     */
    private Long validateAndPin(ReferenceKind kind, UUID referenceId) {
        Optional<Long> version = courseOf(kind, referenceId);
        if (kind == ReferenceKind.CONTENT_ITEM) {
            ContentItem item = items.findById(referenceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such content item"));
            if (!item.getState().acceptsNewReferences()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "\"" + item.getTitle() + "\" is " + item.getState()
                    + " and cannot be assigned. Only PUBLISHED items accept new references "
                    + "(T-5.1); publish it first.");
            }
            // No structure, so nothing to drift.
            return null;
        }
        return version.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "No such " + kind + " in this company"));
    }

    /**
     * The PUBLISHED version an assignment pins, for whatever level it points at.
     *
     * <p>Changed by T-5.7 from {@code course.structure_version} -- the draft's edit counter -- to
     * the number of the latest published version. The old pin recorded which shape of the DRAFT an
     * assignment was made against, which is not a thing anybody can be served: a draft moves.
     * Pinning a published version means the pin resolves to a document that cannot change, which
     * is what the pin was always supposed to mean.
     */
    private Optional<Long> courseOf(ReferenceKind kind, UUID referenceId) {
        Optional<UUID> courseId = switch (kind) {
            case COURSE -> courses.findById(referenceId).map(course -> course.getId());
            case MODULE -> modules.findById(referenceId).map(CourseModuleId::of);
            case NODE -> nodes.findById(referenceId)
                .flatMap(node -> modules.findById(node.getModuleId()))
                .map(CourseModuleId::of);
            case CONTENT_ITEM -> Optional.empty();
        };
        return courseId.map(id -> versions.latest(id)
            .map(published -> published.version())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "That course has never been published, so there is no version to pin. Publish it "
                + "first (T-5.7): assigning a draft would mean a learner working through "
                + "something that changes underneath them.")));
    }

    /** Reads a module's course id without dragging the course in. */
    private interface CourseModuleId {
        static UUID of(com.xenopsoftware.learn.catalog.structure.CourseModule module) {
            return module.getCourseId();
        }
    }
}
