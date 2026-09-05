package com.xenopsoftware.learn.catalog.version;

import com.xenopsoftware.learn.catalog.gate.Gate;
import com.xenopsoftware.learn.catalog.gate.GateRepository;
import com.xenopsoftware.learn.catalog.gate.GateRequirement;
import com.xenopsoftware.learn.catalog.gate.GateRequirementRepository;
import com.xenopsoftware.learn.catalog.structure.CourseNode;
import com.xenopsoftware.learn.catalog.structure.CourseService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishing a course, and what that does to the people already on it (T-5.7).
 *
 * <p>Three things worth reading for. <b>A published version cannot change</b> — enforced by a
 * trigger, not by there being no code for it. <b>A learner mid-course stays on the version they
 * were assigned</b> until somebody moves them deliberately. And <b>a version whose only difference
 * is wording costs nobody anything</b>, which is how a typo gets fixed without disturbing anyone.
 */
@Service
public class CourseVersionService {

    private final JdbcTemplate jdbc;
    private final CourseService courses;
    private final GateRepository gates;
    private final GateRequirementRepository gateRequirements;
    private final JsonMapper json = JsonMapper.builder().build();

    public CourseVersionService(DataSource dataSource, CourseService courses, GateRepository gates,
            GateRequirementRepository gateRequirements) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.courses = courses;
        this.gates = gates;
        this.gateRequirements = gateRequirements;
    }

    /** A published version, as anyone reading it needs it. */
    public record PublishedVersion(UUID id, UUID courseId, long version, boolean textOnly,
                                   String notes, Instant publishedAt, UUID publishedBy) {}

    /**
     * Freezes the current draft as a new version.
     *
     * <p>Whether it is a real version or a text-only one is <b>decided by comparing snapshots</b>,
     * never by the caller saying so. An author who believes they only fixed a typo and actually
     * removed a node would otherwise move every learner onto it silently, which is the failure this
     * whole task exists to prevent.
     */
    @Transactional
    public PublishedVersion publish(UUID courseId, String notes, UUID publishedBy) {
        CourseSnapshot snapshot = draftOf(courseId);
        Optional<PublishedVersion> previous = latest(courseId);
        boolean textOnly = previous
            .map(version -> CourseDiff.between(snapshotOf(version.id()), snapshot))
            .map(CourseDiff::isTextOnly)
            .orElse(false);
        if (previous.isPresent() && CourseDiff.between(snapshotOf(previous.get().id()), snapshot)
                .isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Nothing has changed since version " + previous.get().version()
                + ". Publishing an identical version would add a row to every report and tell "
                + "nobody anything.");
        }

        long next = previous.map(PublishedVersion::version).orElse(0L) + 1;
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO course_version (id, tenant_id, course_id, version, snapshot, text_only,
                                        notes, published_at, published_by)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """, id, TenantContext.require(), courseId, next, json.writeValueAsString(snapshot),
            textOnly, notes, java.sql.Timestamp.from(now), publishedBy);
        return new PublishedVersion(id, courseId, next, textOnly, notes, now, publishedBy);
    }

    /** Every version of a course, newest first. */
    @Transactional(readOnly = true)
    public List<PublishedVersion> versionsOf(UUID courseId) {
        return jdbc.query("""
            SELECT id, course_id, version, text_only, notes, published_at, published_by
              FROM course_version WHERE tenant_id = ? AND course_id = ?
             ORDER BY version DESC
            """, (rows, index) -> new PublishedVersion(
                rows.getObject("id", UUID.class), rows.getObject("course_id", UUID.class),
                rows.getLong("version"), rows.getBoolean("text_only"), rows.getString("notes"),
                rows.getTimestamp("published_at").toInstant(),
                rows.getObject("published_by", UUID.class)),
            TenantContext.require(), courseId);
    }

    public Optional<PublishedVersion> latest(UUID courseId) {
        return versionsOf(courseId).stream().findFirst();
    }

    public Optional<PublishedVersion> versionNumbered(UUID courseId, long version) {
        return versionsOf(courseId).stream()
            .filter(published -> published.version() == version).findFirst();
    }

    /**
     * The version a learner pinned to {@code pinned} should actually be served.
     *
     * <p><b>This is the typo path.</b> A pin follows an unbroken chain of text-only versions
     * forward, so fixing a spelling mistake reaches everybody immediately without moving anyone
     * onto different work. The chain STOPS at the first version that changed what a learner must
     * do — from there they stay where they were until an administrator moves them deliberately.
     *
     * <p>The limit, stated: text-only means titles and descriptions. A node added, removed,
     * reordered, made required or optional, or any gate change, breaks the chain — and the server
     * decides which it was by comparing snapshots.
     */
    @Transactional(readOnly = true)
    public Optional<PublishedVersion> effectiveVersion(UUID courseId, long pinned) {
        List<PublishedVersion> ascending = new ArrayList<>(versionsOf(courseId));
        java.util.Collections.reverse(ascending);
        PublishedVersion effective = null;
        for (PublishedVersion version : ascending) {
            if (version.version() < pinned) {
                continue;
            }
            if (version.version() == pinned) {
                effective = version;
                continue;
            }
            if (effective != null && version.textOnly()) {
                effective = version;
            } else {
                break;
            }
        }
        return Optional.ofNullable(effective);
    }

    /** What changed between two versions of one course. */
    @Transactional(readOnly = true)
    public CourseDiff diff(UUID courseId, long from, long to) {
        PublishedVersion older = require(courseId, from);
        PublishedVersion newer = require(courseId, to);
        return CourseDiff.between(snapshotOf(older.id()), snapshotOf(newer.id()));
    }

    /**
     * What moving learners from one version to another would cost them, WITHOUT doing it.
     *
     * <p>Its own call rather than a flag on the migration, because "show me first" and "do it" are
     * different decisions and an administrator should be able to make the first without risking the
     * second.
     */
    @Transactional(readOnly = true)
    public List<String> whatMigrationWouldCost(UUID courseId, long from, long to) {
        return diff(courseId, from, to).lostForLearners();
    }

    /**
     * Moves every assignment pinned to {@code from} onto {@code to}.
     *
     * <p>Explicit and never automatic (T-5.7's third criterion). Republishing must not move
     * anybody: a learner half-way through a course that gained a module should finish the course
     * they started, and the decision to interrupt them belongs to a person who has seen
     * {@link #whatMigrationWouldCost}.
     *
     * <p>Completion records are not touched. What somebody finished under version 3 was finished
     * under version 3, and rewriting that to say 4 is the history-rewriting this task exists to
     * prevent — the migration changes what they must do NEXT, not what they already did.
     */
    @Transactional
    public int migrate(UUID courseId, long from, long to) {
        require(courseId, from);
        require(courseId, to);
        if (to <= from) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Migration moves learners forward. Moving them back would make finished work "
                + "unfinished, which no report could then explain.");
        }
        return jdbc.update("""
            UPDATE assignment SET pinned_version = ?
             WHERE tenant_id = ? AND reference_type = 'COURSE' AND reference_id = ?
               AND pinned_version = ? AND revoked_at IS NULL
            """, to, TenantContext.require(), courseId, from);
    }

    /** The snapshot stored on a version row. */
    @Transactional(readOnly = true)
    public CourseSnapshot snapshotOf(UUID versionId) {
        String stored = jdbc.query(
            "SELECT snapshot::text FROM course_version WHERE tenant_id = ? AND id = ?",
            rows -> rows.next() ? rows.getString(1) : null, TenantContext.require(), versionId);
        if (stored == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such version");
        }
        return json.readValue(stored, CourseSnapshot.class);
    }

    /**
     * The live draft, as a snapshot -- what publishing would freeze.
     *
     * <p>Named apart from {@link #snapshotOf} on purpose: one takes a COURSE id and reads the
     * mutable tree, the other takes a VERSION id and reads a frozen document. They are the same
     * shape and opposite in every other way, and an earlier draft of this class had both behind
     * one name -- which compiled, and would have published the wrong thing.
     */
    @Transactional(readOnly = true)
    public CourseSnapshot draftOf(UUID courseId) {
        CourseService.CourseTree tree = courses.tree(courseId);
        Map<UUID, CourseSnapshot.Gate> gatesByTarget = gatesOf(courseId);

        List<CourseSnapshot.Module> modules = new ArrayList<>();
        for (CourseService.ModuleTree module : tree.modules()) {
            List<CourseSnapshot.Node> nodes = new ArrayList<>();
            for (CourseNode node : module.nodes()) {
                nodes.add(new CourseSnapshot.Node(node.getId(), node.getContentItemId(),
                    node.isRequired(), gatesByTarget.get(node.getId())));
            }
            modules.add(new CourseSnapshot.Module(module.module().getId(),
                module.module().getTitle(), nodes));
        }
        return new CourseSnapshot(tree.course().getTitle(), tree.course().getDescription(), modules);
    }

    private Map<UUID, CourseSnapshot.Gate> gatesOf(UUID courseId) {
        List<Gate> courseGates = gates.findByCourseId(courseId);
        if (courseGates.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CourseSnapshot.Requirement>> byGate = new LinkedHashMap<>();
        for (GateRequirement requirement : gateRequirements.findByGateIdIn(
                courseGates.stream().map(Gate::getId).toList())) {
            byGate.computeIfAbsent(requirement.getGateId(), any -> new ArrayList<>())
                .add(new CourseSnapshot.Requirement(requirement.getRequirementType().name(),
                    requirement.getRequirementId(), requirement.getRequiredState().name()));
        }
        Map<UUID, CourseSnapshot.Gate> byTarget = new LinkedHashMap<>();
        for (Gate gate : courseGates) {
            byTarget.put(gate.getTargetId(), new CourseSnapshot.Gate(gate.getCombinator().name(),
                byGate.getOrDefault(gate.getId(), List.of())));
        }
        return byTarget;
    }

    private PublishedVersion require(UUID courseId, long version) {
        return versionNumbered(courseId, version).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This course has no version " + version));
    }
}
