package com.xenopsoftware.learn.catalog.gate;

import com.xenopsoftware.learn.catalog.structure.CourseNode;
import com.xenopsoftware.learn.catalog.structure.CourseService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Saving gates, and answering what a learner may reach (T-5.3).
 *
 * <p>Two properties worth reading for. <b>A cycle cannot be saved</b> — it is refused at write
 * time, by walking the graph the save would create, rather than discovered at evaluation as a
 * course where nothing ever unlocks. And <b>the whole course is evaluated in a bounded number of
 * queries</b>, the same count at three nodes and nine hundred, because a gate answered per node is
 * a screen that gets slower every time somebody adds a module.
 */
@Service
public class GateService {

    private final GateRepository gates;
    private final GateRequirementRepository requirements;
    private final NodeCompletionRepository completions;
    private final CourseService courses;
    private final com.xenopsoftware.learn.catalog.content.ContentItemRepository items;

    public GateService(GateRepository gates, GateRequirementRepository requirements,
            NodeCompletionRepository completions, CourseService courses,
            com.xenopsoftware.learn.catalog.content.ContentItemRepository items) {
        this.gates = gates;
        this.requirements = requirements;
        this.completions = completions;
        this.courses = courses;
        this.items = items;
    }

    /** One requirement as a caller states it. */
    public record RequirementSpec(StructurePart part, UUID id, RequiredState state) {}

    /**
     * Puts a gate on a target, replacing whatever was there.
     *
     * <p>Replacing wholesale rather than adding and removing one requirement at a time, for the
     * reason {@code RoleService.setPermissions} gives: a gate's rule is the set, and two authors
     * each adding one requirement to a stale view would produce a rule neither of them chose.
     */
    @Transactional
    public GateRule save(UUID courseId, StructurePart targetPart, UUID targetId,
            Combinator combinator, List<RequirementSpec> specs) {
        CourseService.CourseTree tree = courses.tree(courseId);
        Map<UUID, StructurePart> parts = partsOf(tree);
        Map<UUID, UUID> containingModule = containingModules(tree);

        if (!parts.containsKey(targetId) || parts.get(targetId) != targetPart) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "That is not a " + targetPart + " in this course");
        }
        for (RequirementSpec spec : specs) {
            validate(spec, targetPart, targetId, parts, containingModule);
        }
        refuseCycles(courseId, targetId, specs, containingModule);

        Gate gate = gates.findByTargetId(targetId).orElseGet(() ->
            Gate.on(courseId, targetPart, targetId, combinator));
        gate.recombine(combinator);
        gates.save(gate);
        requirements.deleteByGateId(gate.getId());
        // Flush before inserting, or the unique constraint on (gate, requirement, state) fires
        // against rows Hibernate has not removed yet for a requirement being kept -- the same
        // trap RoleService.setPermissions documents.
        requirements.flush();

        List<GateRule.Requirement> saved = new ArrayList<>();
        for (RequirementSpec spec : specs) {
            requirements.save(GateRequirement.of(gate.getId(), spec.part(), spec.id(), spec.state()));
            saved.add(new GateRule.Requirement(spec.part(), spec.id(), spec.state()));
        }
        return new GateRule(targetPart, targetId, combinator, saved);
    }

    @Transactional
    public void remove(UUID targetId) {
        gates.findByTargetId(targetId).ifPresent(gate -> {
            requirements.deleteByGateId(gate.getId());
            gates.delete(gate);
        });
    }

    /**
     * What this learner may reach in this course, and why not where they may not.
     *
     * <p><b>A fixed number of queries, whatever the depth.</b> The course tree is three, the gates
     * and their requirements are one each, the learner's completions are one, and the titles for
     * the sentences are one — seven, and not one of them per node or per gate. Everything after
     * that is in memory. {@code GateReachabilityTest} asserts the count on a deep course, because
     * a per-node evaluation is indistinguishable from correct on a course with three nodes and is
     * what makes the learner's first screen slower every time somebody adds a module.
     */
    @Transactional(readOnly = true)
    public List<Reachability> reachability(UUID courseId, UUID learnerId) {
        CourseService.CourseTree tree = courses.tree(courseId);
        List<Gate> courseGates = gates.findByCourseId(courseId);
        Map<UUID, GateRule> rules = rulesOf(courseGates);
        Map<UUID, String> titles = titlesOf(tree);

        List<UUID> nodeIds = tree.modules().stream()
            .flatMap(module -> module.nodes().stream())
            .map(CourseNode::getId).toList();
        Map<UUID, Set<RequiredState>> satisfied = new HashMap<>(
            completions.statesOf(TenantContext.require(), learnerId, nodeIds));

        // A module counts as COMPLETED when every REQUIRED node in it is -- optional nodes never
        // block a gate (T-5.2), which is the whole reason that flag exists. Derived here rather
        // than stored, because a stored module completion is a second copy of the same fact that
        // goes stale the moment a node is added to the module.
        for (CourseService.ModuleTree module : tree.modules()) {
            List<CourseNode> required = module.nodes().stream()
                .filter(CourseNode::isRequired).toList();
            boolean done = !required.isEmpty() && required.stream().allMatch(node ->
                satisfied.getOrDefault(node.getId(), Set.of()).contains(RequiredState.COMPLETED));
            if (done) {
                satisfied.put(module.module().getId(), EnumSet.of(RequiredState.COMPLETED));
            }
        }

        List<Reachability> answers = new ArrayList<>();
        for (CourseService.ModuleTree module : tree.modules()) {
            UUID moduleId = module.module().getId();
            answers.add(answerFor(StructurePart.MODULE, moduleId, rules, satisfied, titles));
            boolean moduleOpen = answers.getLast().reachable();
            for (CourseNode node : module.nodes()) {
                Reachability answer = answerFor(StructurePart.NODE, node.getId(), rules, satisfied,
                    titles);
                // A node inside a locked module is locked, and is told the MODULE's reason rather
                // than its own: "complete Week one" is what the learner can act on, and repeating
                // the node's own unmet requirements underneath a module they cannot open yet is
                // noise about a decision that is not theirs yet.
                answers.add(moduleOpen || !answer.reachable() ? answer
                    : new Reachability(StructurePart.NODE, node.getId(), false,
                        module.module().getTitle() + " is not available yet.", List.of()));
            }
        }
        return answers;
    }

    /** The rule on one target, if there is one. Used by the API and by the explanation screen. */
    @Transactional(readOnly = true)
    public java.util.Optional<GateRule> ruleOn(UUID targetId) {
        return gates.findByTargetId(targetId)
            .map(gate -> rulesOf(List.of(gate)).get(gate.getTargetId()));
    }

    private Reachability answerFor(StructurePart part, UUID id, Map<UUID, GateRule> rules,
            Map<UUID, Set<RequiredState>> satisfied, Map<UUID, String> titles) {
        // ALREADY DONE WINS OVER EVERY GATE (T-5.3's fifth criterion). Checked before the rule is
        // read, so no combination of requirements can retroactively lock something a learner has
        // already finished -- an author adding a prerequisite must not strand somebody mid-course.
        if (satisfied.getOrDefault(id, Set.of()).contains(RequiredState.COMPLETED)) {
            return Reachability.alreadyDone(part, id);
        }
        GateRule rule = rules.get(id);
        return rule == null ? Reachability.open(part, id) : rule.evaluate(satisfied, titles);
    }

    private Map<UUID, GateRule> rulesOf(List<Gate> courseGates) {
        if (courseGates.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<GateRule.Requirement>> byGate = new LinkedHashMap<>();
        for (GateRequirement requirement : requirements.findByGateIdIn(
                courseGates.stream().map(Gate::getId).toList())) {
            byGate.computeIfAbsent(requirement.getGateId(), any -> new ArrayList<>())
                .add(new GateRule.Requirement(requirement.getRequirementType(),
                    requirement.getRequirementId(), requirement.getRequiredState()));
        }
        Map<UUID, GateRule> rules = new LinkedHashMap<>();
        for (Gate gate : courseGates) {
            rules.put(gate.getTargetId(), new GateRule(gate.getTargetType(), gate.getTargetId(),
                gate.getCombinator(), byGate.getOrDefault(gate.getId(), List.of())));
        }
        return rules;
    }

    private void validate(RequirementSpec spec, StructurePart targetPart, UUID targetId,
            Map<UUID, StructurePart> parts, Map<UUID, UUID> containingModule) {
        if (spec.id().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Something cannot require itself");
        }
        if (!parts.containsKey(spec.id()) || parts.get(spec.id()) != spec.part()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A gate can only require a " + spec.part() + " in the same course");
        }
        // A node requiring the module that contains it is a cycle wearing containment's clothes:
        // the module cannot complete until the node does, and the node cannot start until the
        // module has. It would evaluate cleanly and lock forever.
        if (targetPart == StructurePart.NODE
            && spec.part() == StructurePart.MODULE
            && spec.id().equals(containingModule.get(targetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A step cannot require the module it is in -- the module is not complete until "
                + "that step is, so nothing would ever unlock");
        }
    }

    /**
     * Refuses a save that would put a cycle in the graph (T-5.3's fourth criterion).
     *
     * <p>Checked at WRITE time by walking the graph the save would create, not at evaluation.
     * A cycle discovered at evaluation is a course where a learner is simply stuck, with every
     * gate correctly reporting a requirement that will never be met — nothing errors, and the
     * support ticket says "the course is broken".
     *
     * <p>A module requirement is expanded to the nodes inside it, so
     * {@code node A -> module M -> node B -> node A} is caught: without that expansion the two
     * halves look unrelated because they are edges between different kinds of thing.
     */
    private void refuseCycles(UUID courseId, UUID targetId, List<RequirementSpec> specs,
            Map<UUID, UUID> containingModule) {
        Map<UUID, Set<UUID>> edges = new HashMap<>();
        List<Gate> existing = gates.findByCourseId(courseId);
        Map<UUID, GateRule> rules = rulesOf(existing);
        rules.forEach((from, rule) -> {
            if (!from.equals(targetId)) {
                edges.put(from, new LinkedHashSet<>(
                    rule.requirements().stream().map(GateRule.Requirement::id).toList()));
            }
        });
        edges.put(targetId, new LinkedHashSet<>(specs.stream().map(RequirementSpec::id).toList()));

        // Containment: a module is not finished until its nodes are, so a module depends on them.
        containingModule.forEach((nodeId, moduleId) ->
            edges.computeIfAbsent(moduleId, any -> new LinkedHashSet<>()).add(nodeId));

        Set<UUID> visiting = new LinkedHashSet<>();
        Set<UUID> settled = new HashSet<>();
        for (UUID start : edges.keySet()) {
            List<UUID> cycle = walk(start, edges, visiting, settled);
            if (cycle != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That would make a loop: nothing in it could ever unlock. "
                    + cycle.size() + " steps depend on each other in a circle.");
            }
        }
    }

    /** Depth-first, returning the cycle it found or null. Iterative depth is bounded by course size. */
    private static List<UUID> walk(UUID at, Map<UUID, Set<UUID>> edges, Set<UUID> visiting,
            Set<UUID> settled) {
        if (settled.contains(at)) {
            return null;
        }
        if (!visiting.add(at)) {
            return List.copyOf(visiting);
        }
        for (UUID next : edges.getOrDefault(at, Set.of())) {
            List<UUID> cycle = walk(next, edges, visiting, settled);
            if (cycle != null) {
                return cycle;
            }
        }
        visiting.remove(at);
        settled.add(at);
        return null;
    }

    private static Map<UUID, StructurePart> partsOf(CourseService.CourseTree tree) {
        Map<UUID, StructurePart> parts = new LinkedHashMap<>();
        for (CourseService.ModuleTree module : tree.modules()) {
            parts.put(module.module().getId(), StructurePart.MODULE);
            for (CourseNode node : module.nodes()) {
                parts.put(node.getId(), StructurePart.NODE);
            }
        }
        return parts;
    }

    private static Map<UUID, UUID> containingModules(CourseService.CourseTree tree) {
        Map<UUID, UUID> containing = new LinkedHashMap<>();
        for (CourseService.ModuleTree module : tree.modules()) {
            for (CourseNode node : module.nodes()) {
                containing.put(node.getId(), module.module().getId());
            }
        }
        return containing;
    }

    /**
     * What each part is called, for the sentence.
     *
     * <p>A node is named by its content item's title, which is what a learner sees on the screen —
     * "pass Safety test", not "pass node 4f2a". Catalog owns {@code content_item}, so this is its
     * own data rather than a fact copied from another module.
     */
    private Map<UUID, String> titlesOf(CourseService.CourseTree tree) {
        Map<UUID, String> titles = new LinkedHashMap<>();
        // findAllById, not a lookup per node. The per-node version is the N+1 this method would
        // otherwise smuggle back in after the reads above were carefully bulk.
        Map<UUID, String> itemTitles = new HashMap<>();
        List<UUID> itemIds = tree.modules().stream().flatMap(module -> module.nodes().stream())
            .map(CourseNode::getContentItemId).distinct().toList();
        if (!itemIds.isEmpty()) {
            items.findAllById(itemIds)
                .forEach(item -> itemTitles.put(item.getId(), item.getTitle()));
        }
        for (CourseService.ModuleTree module : tree.modules()) {
            titles.put(module.module().getId(), module.module().getTitle());
            for (CourseNode node : module.nodes()) {
                titles.put(node.getId(),
                    itemTitles.getOrDefault(node.getContentItemId(), "a step"));
            }
        }
        return titles;
    }
}
