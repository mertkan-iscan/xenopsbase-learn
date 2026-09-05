package com.xenopsoftware.learn.catalog.version;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A course's structure, frozen (T-5.7).
 *
 * <p>What a learner must do, and nothing else. Titles are here because a learner reads them;
 * content item titles are NOT, because an item's title belongs to the item and copying it would be
 * the drift ADR-0109 warns about — with the twist that nobody could ever correct it, since the
 * snapshot cannot be edited.
 *
 * <p>Ordinals are deliberately absent. The snapshot records the ORDER, as a list, and not the
 * numbers that produced it: rebalancing a module (T-5.2) renumbers every row without changing what
 * a learner does, and a snapshot holding the numbers would call that a new version.
 */
public record CourseSnapshot(String title, String description, List<Module> modules) {

    public record Module(UUID id, String title, List<Node> nodes) {}

    /**
     * @param gate the rule guarding this node, or null. Part of the structure because a gate
     *             decides whether a learner can reach the node at all — a version that ignored
     *             gates would call two genuinely different courses the same
     */
    public record Node(UUID id, UUID contentItemId, boolean required, Gate gate) {}

    public record Gate(String combinator, List<Requirement> requirements) {}

    public record Requirement(String part, UUID id, String state) {}

    public CourseSnapshot {
        modules = List.copyOf(modules);
    }

    /**
     * What a learner must DO, with every piece of wording removed.
     *
     * <p>The whole of the text-only decision rests on this: two snapshots whose {@code shape} is
     * equal differ only in what somebody reads, so publishing the second disturbs nobody. Computed
     * by stripping rather than by comparing field-by-field, because a field added to this record
     * later is then part of the shape by default — the safe direction. Forgetting to add a new
     * field to a hand-written comparison would silently classify a real change as a typo fix.
     */
    public Map<String, Object> shape() {
        List<Object> shapedModules = new ArrayList<>();
        for (Module module : modules) {
            List<Object> shapedNodes = new ArrayList<>();
            for (Node node : module.nodes()) {
                Map<String, Object> shapedNode = new LinkedHashMap<>();
                shapedNode.put("id", node.id());
                shapedNode.put("contentItemId", node.contentItemId());
                shapedNode.put("required", node.required());
                shapedNode.put("gate", node.gate());
                shapedNodes.add(shapedNode);
            }
            Map<String, Object> shapedModule = new LinkedHashMap<>();
            shapedModule.put("id", module.id());
            shapedModule.put("nodes", shapedNodes);
            shapedModules.add(shapedModule);
        }
        return Map.of("modules", shapedModules);
    }
}
