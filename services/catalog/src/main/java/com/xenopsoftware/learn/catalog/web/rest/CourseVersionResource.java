package com.xenopsoftware.learn.catalog.web.rest;

import com.xenopsoftware.learn.catalog.version.CourseDiff;
import com.xenopsoftware.learn.catalog.version.CourseVersionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publishing, comparing and migrating course versions (T-5.7).
 *
 * <p>Note what is missing: there is no endpoint that edits a published version. That is the point,
 * and the trigger behind the table means adding one later fails loudly rather than quietly
 * rewriting what a report describes.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/versions")
public class CourseVersionResource {

    private final CourseVersionService versions;

    public CourseVersionResource(CourseVersionService versions) {
        this.versions = versions;
    }

    /**
     * @param notes what the author says changed. Free text and never a substitute for the diff:
     *              "minor updates" is what somebody types at 5pm
     */
    public record PublishRequest(String notes, UUID publishedBy) {}

    public record MigrateRequest(long from, long to) {}

    /**
     * @param textOnly nothing a learner must do is different. Assignments pinned to an earlier
     *                 version follow this one automatically, so a typo fix reaches everybody
     *                 without moving anybody
     */
    public record VersionView(UUID id, long version, boolean textOnly, String notes,
                              Instant publishedAt, UUID publishedBy) {}

    /**
     * @param lostForLearners what migrating onto this version would take away. Empty is the common
     *                        case and the one worth being able to see
     */
    public record DiffView(List<String> addedNodes, List<String> removedNodes,
                           List<String> reorderedModules, List<String> requirementChanges,
                           List<String> gateChanges, List<String> textChanges,
                           boolean textOnly, List<String> lostForLearners) {}

    public record MigrationView(int assignmentsMoved) {}

    @GetMapping
    public List<VersionView> all(@PathVariable UUID courseId) {
        return versions.versionsOf(courseId).stream().map(CourseVersionResource::view).toList();
    }

    @PostMapping
    public VersionView publish(@PathVariable UUID courseId, @RequestBody PublishRequest request) {
        if (request.publishedBy() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "publishedBy is required: a version nobody is recorded as having published is one "
                + "nobody can be asked about");
        }
        return view(versions.publish(courseId, request.notes(), request.publishedBy()));
    }

    /** What changed. The first question anyone asks. */
    @GetMapping("/diff")
    public DiffView diff(@PathVariable UUID courseId, @RequestParam long from,
            @RequestParam long to) {
        CourseDiff diff = versions.diff(courseId, from, to);
        return new DiffView(diff.addedNodes(), diff.removedNodes(), diff.reorderedModules(),
            diff.requirementChanges(), diff.gateChanges(), diff.textChanges(), diff.isTextOnly(),
            diff.lostForLearners());
    }

    /**
     * What a migration would cost, without performing it.
     *
     * <p>A GET, so an administrator can look at the consequences as many times as they like
     * without the possibility of having done it.
     */
    @GetMapping("/migration-cost")
    public List<String> migrationCost(@PathVariable UUID courseId, @RequestParam long from,
            @RequestParam long to) {
        return versions.whatMigrationWouldCost(courseId, from, to);
    }

    /**
     * Moves learners forward. Deliberate, and never a side effect of publishing.
     *
     * <p>Republishing must not move anybody: somebody half-way through a course that has just
     * gained a module should finish the one they started, and interrupting them is a decision for a
     * person who has seen {@code /migration-cost}.
     */
    @PostMapping("/migrate")
    public MigrationView migrate(@PathVariable UUID courseId, @RequestBody MigrateRequest request) {
        return new MigrationView(versions.migrate(courseId, request.from(), request.to()));
    }

    private static VersionView view(CourseVersionService.PublishedVersion version) {
        return new VersionView(version.id(), version.version(), version.textOnly(),
            version.notes(), version.publishedAt(), version.publishedBy());
    }
}
