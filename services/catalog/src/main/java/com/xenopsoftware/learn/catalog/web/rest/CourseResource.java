package com.xenopsoftware.learn.catalog.web.rest;

import com.xenopsoftware.learn.catalog.structure.Course;
import com.xenopsoftware.learn.catalog.structure.CourseModule;
import com.xenopsoftware.learn.catalog.structure.CourseNode;
import com.xenopsoftware.learn.catalog.structure.CourseService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The course tree over HTTP (T-5.2).
 *
 * <p><b>Reordering is one request against one node</b>, not a rewrite of the list. A client that
 * had to PUT the whole ordered array would be a client whose every drag conflicts with any other
 * author touching the same module, and whose payload grows with the course.
 *
 * <p>Authentication-only, like the rest of this service; {@code CatalogCoverageTest} carries the
 * reason.
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseResource {

    private final CourseService courses;

    public CourseResource(CourseService courses) {
        this.courses = courses;
    }

    public record CourseRequest(String title, String description) {}

    public record ModuleRequest(String title, UUID afterModuleId) {}

    public record NodeRequest(UUID contentItemId, Boolean required, UUID afterNodeId) {}

    /**
     * @param afterModuleId the module this one follows now; null means "make it first"
     */
    public record MoveModuleRequest(UUID afterModuleId) {}

    /**
     * @param moduleId    where the node lands; null keeps it in the module it is in
     * @param afterNodeId the node it follows there; null means "make it first"
     */
    public record MoveNodeRequest(UUID moduleId, UUID afterNodeId) {}

    public record RequiredRequest(boolean required) {}

    public record CourseView(UUID id, String title, String description) {}

    /**
     * @param ordinal exposed as a string, because it is an arbitrary-precision rational and a
     *                client that parsed it as a JSON number would round it. Nothing outside this
     *                service should do arithmetic on it: a client asks to move a node AFTER
     *                another one, and the ordinal is ours to compute
     */
    public record NodeView(UUID id, UUID contentItemId, boolean required, String ordinal) {}

    public record ModuleView(UUID id, String title, String ordinal, List<NodeView> nodes) {}

    public record TreeView(CourseView course, List<ModuleView> modules) {}

    @GetMapping
    public List<CourseView> all() {
        return courses.all().stream().map(CourseResource::view).toList();
    }

    @PostMapping
    public CourseView create(@RequestBody CourseRequest request) {
        return view(courses.create(request.title(), request.description()));
    }

    /** The whole tree, however deep, in two queries. */
    @GetMapping("/{courseId}")
    public TreeView tree(@PathVariable UUID courseId) {
        CourseService.CourseTree tree = courses.tree(courseId);
        return new TreeView(view(tree.course()), tree.modules().stream()
            .map(module -> new ModuleView(module.module().getId(), module.module().getTitle(),
                module.module().getOrdinal().toPlainString(),
                module.nodes().stream().map(CourseResource::view).toList()))
            .toList());
    }

    @PostMapping("/{courseId}/modules")
    public ModuleView addModule(@PathVariable UUID courseId, @RequestBody ModuleRequest request) {
        CourseModule module = courses.addModule(courseId, request.title(), request.afterModuleId());
        return new ModuleView(module.getId(), module.getTitle(),
            module.getOrdinal().toPlainString(), List.of());
    }

    @PutMapping("/modules/{moduleId}/position")
    public ModuleView moveModule(@PathVariable UUID moduleId,
            @RequestBody MoveModuleRequest request) {
        CourseModule module = courses.moveModule(moduleId, request.afterModuleId());
        return new ModuleView(module.getId(), module.getTitle(),
            module.getOrdinal().toPlainString(), List.of());
    }

    @PostMapping("/modules/{moduleId}/nodes")
    public NodeView addNode(@PathVariable UUID moduleId, @RequestBody NodeRequest request) {
        // Required unless the author said otherwise: adding something to a course means it
        // counts, and optional is the deliberate exception (T-5.2).
        boolean required = request.required() == null || request.required();
        return view(courses.addNode(moduleId, request.contentItemId(), required,
            request.afterNodeId()));
    }

    @PutMapping("/nodes/{nodeId}/position")
    public NodeView moveNode(@PathVariable UUID nodeId, @RequestBody MoveNodeRequest request) {
        return view(courses.moveNode(nodeId, request.moduleId(), request.afterNodeId()));
    }

    @PutMapping("/nodes/{nodeId}/required")
    public NodeView setRequired(@PathVariable UUID nodeId, @RequestBody RequiredRequest request) {
        return view(courses.setRequired(nodeId, request.required()));
    }

    /**
     * Renumbers one module's nodes 1000 apart. The one operation here that rewrites every row in
     * a list, which is why it is a request somebody makes rather than something that happens.
     */
    @PostMapping("/modules/{moduleId}/rebalance")
    public java.util.Map<String, Integer> rebalance(@PathVariable UUID moduleId) {
        return java.util.Map.of("renumbered", courses.rebalance(moduleId));
    }

    private static CourseView view(Course course) {
        return new CourseView(course.getId(), course.getTitle(), course.getDescription());
    }

    private static NodeView view(CourseNode node) {
        return new NodeView(node.getId(), node.getContentItemId(), node.isRequired(),
            node.getOrdinal().toPlainString());
    }
}
