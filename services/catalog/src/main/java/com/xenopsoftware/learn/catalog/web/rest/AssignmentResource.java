package com.xenopsoftware.learn.catalog.web.rest;

import com.xenopsoftware.learn.catalog.assign.Assignment;
import com.xenopsoftware.learn.catalog.assign.AssignmentService;
import com.xenopsoftware.learn.catalog.assign.ReferenceKind;
import com.xenopsoftware.learn.catalog.assign.TargetKind;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Assignments over HTTP (T-5.5).
 *
 * <p>One endpoint for every combination, because they are one feature: a target kind, a reference
 * kind, and two ids. "Assign a video to a student" and "assign a course to a department" differ by
 * the value of two fields.
 *
 * <p>Authentication-only; {@code CatalogCoverageTest} carries the reason with the rest.
 */
@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentResource {

    private final AssignmentService assignments;

    public AssignmentResource(AssignmentService assignments) {
        this.assignments = assignments;
    }

    /**
     * @param targetId   null only when {@code targetType} is TENANT
     * @param assignedBy app_user.id of whoever is doing it. Taken from the body rather than the
     *                   token because this service cannot resolve a token to an app_user — that
     *                   mapping is identity's (ADR-0104), and reaching for it would mean a
     *                   synchronous call per assignment. It becomes the caller's own id once
     *                   grants and identity travel together (T-9.11), and until then it is
     *                   recorded as stated rather than silently left null.
     */
    public record AssignRequest(String targetType, UUID targetId, String referenceType,
                                UUID referenceId, UUID assignedBy) {}

    public record BulkRequest(List<AssignRequest> assignments) {}

    public record AssignmentView(UUID id, String targetType, UUID targetId, String referenceType,
                                 UUID referenceId, Long pinnedVersion, boolean drifted,
                                 UUID assignedBy, Instant assignedAt) {}

    /**
     * @param sources every assignment that produced this one obligation. Usually one; more when a
     *                learner is reached by two groups, or by a group and by name.
     */
    public record ObligationView(String referenceType, UUID referenceId, Instant assignedAt,
                                 Long pinnedVersion, List<UUID> sources) {}

    @GetMapping
    public List<AssignmentView> all() {
        return assignments.all().stream().map(this::view).toList();
    }

    @PostMapping
    public AssignmentView assign(@RequestBody AssignRequest request) {
        return view(assignments.assign(request(request), assignedBy(request)));
    }

    /**
     * Many at once, in one transaction.
     *
     * <p>Worth saying plainly: assigning a course to a department of five thousand does not need
     * this endpoint at all — that is ONE assignment, because the audience is resolved when
     * somebody reads. This is for many references or many named people, and the work is
     * proportional to what was asked for rather than to who it lands on.
     */
    @PostMapping("/bulk")
    public List<AssignmentView> assignAll(@RequestBody BulkRequest request) {
        List<AssignmentService.Request> requests = request.assignments() == null ? List.of()
            : request.assignments().stream().map(AssignmentResource::request).toList();
        UUID by = request.assignments() == null || request.assignments().isEmpty() ? null
            : assignedBy(request.assignments().getFirst());
        return assignments.assignAll(requests, by).stream().map(this::view).toList();
    }

    @DeleteMapping("/{assignmentId}")
    public void revoke(@PathVariable UUID assignmentId) {
        assignments.revoke(assignmentId);
    }

    /**
     * What one learner owes, with duplicates collapsed into one obligation each.
     *
     * <p>The read a home screen makes (T-5.8 builds on it). Two queries whatever the size of the
     * groups involved.
     */
    @GetMapping("/of/{learnerId}")
    public List<ObligationView> obligations(@PathVariable UUID learnerId) {
        return assignments.obligationsOf(learnerId).stream()
            .map(obligation -> new ObligationView(obligation.referenceType().name(),
                obligation.referenceId(), obligation.assignedAt(), obligation.pinnedVersion(),
                obligation.sources()))
            .toList();
    }

    private static AssignmentService.Request request(AssignRequest request) {
        return new AssignmentService.Request(
            parse(TargetKind.class, request.targetType(),
                "targetType must be USER, GROUP or TENANT"),
            request.targetId(),
            parse(ReferenceKind.class, request.referenceType(),
                "referenceType must be COURSE, MODULE, NODE or CONTENT_ITEM"),
            request.referenceId());
    }

    private static UUID assignedBy(AssignRequest request) {
        if (request.assignedBy() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "assignedBy is required: an obligation nobody is recorded as having given is one "
                + "nobody can be asked about");
        }
        return request.assignedBy();
    }

    private AssignmentView view(Assignment assignment) {
        return new AssignmentView(assignment.getId(), assignment.getTargetType().name(),
            assignment.getTargetId(), assignment.getReferenceType().name(),
            assignment.getReferenceId(), assignment.getPinnedVersion(),
            assignments.hasDrifted(assignment), assignment.getAssignedBy(),
            assignment.getAssignedAt());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String complaint) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, complaint);
        }
    }
}
