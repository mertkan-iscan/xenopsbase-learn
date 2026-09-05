package com.xenopsoftware.learn.catalog.structure;

import com.xenopsoftware.learn.catalog.content.ContentItem;
import com.xenopsoftware.learn.catalog.content.ContentItemRepository;
import java.math.BigDecimal;
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
 * Building and reordering a course (T-5.2).
 *
 * <p>Two things are worth reading for before the code: <b>every placement is a single-row write</b>
 * (see {@link Ordinals}), and <b>a node may only point at a PUBLISHED content item</b>, which is
 * T-5.1's reference rule asked rather than re-derived.
 */
@Service
public class CourseService {

    private final CourseRepository courses;
    private final CourseModuleRepository modules;
    private final CourseNodeRepository nodes;
    private final ContentItemRepository items;

    public CourseService(CourseRepository courses, CourseModuleRepository modules,
            CourseNodeRepository nodes, ContentItemRepository items) {
        this.courses = courses;
        this.modules = modules;
        this.nodes = nodes;
        this.items = items;
    }

    /** A whole course in one shape, assembled from two queries however deep it is. */
    public record CourseTree(Course course, List<ModuleTree> modules) {}

    public record ModuleTree(CourseModule module, List<CourseNode> nodes) {}

    @Transactional
    public Course create(String title, String description) {
        return courses.save(Course.named(title, description));
    }

    @Transactional(readOnly = true)
    public List<Course> all() {
        return courses.findAllByOrderByUpdatedAtDesc();
    }

    /**
     * The whole tree, in <b>two queries regardless of depth</b>: one for the modules, one for
     * every node in the course. Assembling in Java rather than letting JPA walk the graph is what
     * keeps a forty-module course from costing forty-one round trips — the N+1 that a test with
     * two modules cannot see.
     */
    @Transactional(readOnly = true)
    public CourseTree tree(UUID courseId) {
        Course course = courses.findById(courseId).orElseThrow(CourseService::notFound);
        List<CourseModule> ordered = modules.findByCourseIdOrderByOrdinalAscIdAsc(courseId);

        Map<UUID, List<CourseNode>> byModule = new LinkedHashMap<>();
        for (CourseModule module : ordered) {
            byModule.put(module.getId(), new ArrayList<>());
        }
        for (CourseNode node : nodes.findWholeCourse(courseId)) {
            // Already ordered by the query; grouping preserves it.
            byModule.computeIfAbsent(node.getModuleId(), any -> new ArrayList<>()).add(node);
        }
        return new CourseTree(course, ordered.stream()
            .map(module -> new ModuleTree(module, byModule.get(module.getId())))
            .toList());
    }

    @Transactional
    public CourseModule addModule(UUID courseId, String title, UUID afterModuleId) {
        courses.findById(courseId).orElseThrow(CourseService::notFound);
        List<CourseModule> siblings = modules.findByCourseIdOrderByOrdinalAscIdAsc(courseId);
        return modules.save(CourseModule.in(courseId, title,
            placeAfter(siblings, CourseModule::getId, CourseModule::getOrdinal, afterModuleId)));
    }

    /**
     * Moves a module. One row changes, whatever the course's size.
     *
     * @param afterModuleId the module this one now follows, or null to move it to the front
     */
    @Transactional
    public CourseModule moveModule(UUID moduleId, UUID afterModuleId) {
        CourseModule module = modules.findById(moduleId).orElseThrow(CourseService::notFound);
        if (moduleId.equals(afterModuleId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A module cannot be placed after itself");
        }
        List<CourseModule> siblings = modules.findByCourseIdOrderByOrdinalAscIdAsc(module.getCourseId())
            .stream().filter(sibling -> !sibling.getId().equals(moduleId)).toList();
        module.moveTo(placeAfter(siblings, CourseModule::getId, CourseModule::getOrdinal, afterModuleId));
        return modules.save(module);
    }

    /**
     * Adds a node pointing at a content item.
     *
     * <p>The item must be PUBLISHED, which is {@link com.xenopsoftware.learn.catalog.content
     * .ContentState#acceptsNewReferences()} asked rather than re-implemented. A draft is unfinished
     * by definition and an archived item has been withdrawn — putting either into a course means a
     * learner reaching content nobody meant to ship.
     */
    @Transactional
    public CourseNode addNode(UUID moduleId, UUID contentItemId, boolean required, UUID afterNodeId) {
        modules.findById(moduleId).orElseThrow(CourseService::notFound);
        ContentItem item = items.findById(contentItemId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No such content item"));
        if (!item.getState().acceptsNewReferences()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "\"" + item.getTitle() + "\" is " + item.getState()
                + " and cannot be added to a course. Only PUBLISHED items accept new references "
                + "(T-5.1); publish it first, or pick another.");
        }
        List<CourseNode> siblings = nodes.findByModuleIdOrderByOrdinalAscIdAsc(moduleId);
        return nodes.save(CourseNode.in(moduleId, contentItemId,
            placeAfter(siblings, CourseNode::getId, CourseNode::getOrdinal, afterNodeId), required));
    }

    /**
     * Moves a node, possibly into another module. Still one row.
     *
     * @param moduleId    where it lands; null keeps it where it is
     * @param afterNodeId the node it now follows within that module, or null for the front
     */
    @Transactional
    public CourseNode moveNode(UUID nodeId, UUID moduleId, UUID afterNodeId) {
        CourseNode node = nodes.findById(nodeId).orElseThrow(CourseService::notFound);
        if (nodeId.equals(afterNodeId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A node cannot be placed after itself");
        }
        UUID target = moduleId == null ? node.getModuleId() : moduleId;
        if (moduleId != null) {
            modules.findById(moduleId).orElseThrow(CourseService::notFound);
        }
        List<CourseNode> siblings = nodes.findByModuleIdOrderByOrdinalAscIdAsc(target).stream()
            .filter(sibling -> !sibling.getId().equals(nodeId)).toList();
        node.moveTo(target, placeAfter(siblings, CourseNode::getId, CourseNode::getOrdinal, afterNodeId));
        return nodes.save(node);
    }

    @Transactional
    public CourseNode setRequired(UUID nodeId, boolean required) {
        CourseNode node = nodes.findById(nodeId).orElseThrow(CourseService::notFound);
        node.setRequired(required);
        return nodes.save(node);
    }

    /**
     * The nodes a gate must wait for (T-5.3), which is deliberately not "the nodes".
     *
     * <p>Published here so that gating asks rather than filtering on {@code required} itself.
     * Three callers each writing {@code .filter(CourseNode::isRequired)} is three places for the
     * rule to drift the day "required" acquires a nuance.
     */
    @Transactional(readOnly = true)
    public List<CourseNode> requiredNodes(UUID courseId) {
        return nodes.findWholeCourse(courseId).stream().filter(CourseNode::isRequired).toList();
    }

    /** Which courses point at an item. Asked before withdrawing one. */
    @Transactional(readOnly = true)
    public long referencesTo(UUID contentItemId) {
        return nodes.countByContentItemId(contentItemId);
    }

    /**
     * Fresh ordinals for one module, 1000 apart (T-5.2's stated escape hatch).
     *
     * <p>This is the one operation that rewrites every row in a module, which is what the whole
     * rational-ordinal scheme exists to avoid doing by accident. It exists because repeatedly
     * inserting at the SAME point grows the stored number about a digit each time, and a
     * hundred-digit ordinal is correct but unreadable. Deliberate, never automatic: renumbering
     * under a user is the cost this design refuses to pay silently.
     */
    @Transactional
    public int rebalance(UUID moduleId) {
        List<CourseNode> ordered = nodes.findByModuleIdOrderByOrdinalAscIdAsc(moduleId);
        List<BigDecimal> fresh = Ordinals.rebalance(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).moveTo(fresh.get(i));
        }
        nodes.saveAll(ordered);
        return ordered.size();
    }

    /**
     * The ordinal for something placed after {@code afterId} in {@code siblings}, or at the front
     * when it is null.
     *
     * <p>One helper for modules and nodes because the rule is identical and two copies of it is
     * one that drifts.
     */
    private static <T> BigDecimal placeAfter(List<T> siblings, java.util.function.Function<T, UUID> id,
            java.util.function.Function<T, BigDecimal> ordinal, UUID afterId) {
        if (afterId == null) {
            BigDecimal firstOrdinal = siblings.isEmpty() ? null : ordinal.apply(siblings.getFirst());
            return Ordinals.between(null, firstOrdinal);
        }
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (id.apply(siblings.get(i)).equals(afterId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // Named a neighbour that is not in this list: a stale client view, or a neighbour in
            // a different module. Refusing beats appending somewhere the caller did not choose.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The item this should follow is not in that list any more");
        }
        BigDecimal before = ordinal.apply(siblings.get(index));
        BigDecimal after = index + 1 < siblings.size() ? ordinal.apply(siblings.get(index + 1)) : null;
        return Ordinals.between(before, after);
    }

    private static ResponseStatusException notFound() {
        // 404 for another company's id too: the discriminator resolved it to nothing rather than
        // a check refusing it, which is the shape ADR-0102 promises.
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course, module or node");
    }

    /** Whether an item exists here at all, for callers that only need to know that. */
    @Transactional(readOnly = true)
    public Optional<ContentItem> itemOf(UUID contentItemId) {
        return items.findById(contentItemId);
    }
}
