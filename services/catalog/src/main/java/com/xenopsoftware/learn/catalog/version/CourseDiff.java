package com.xenopsoftware.learn.catalog.version;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What changed between two versions (T-5.7's last criterion).
 *
 * <p>"What changed" is the first question anyone asks, and it is asked in two different moods: an
 * author wants to see their own edit, and an administrator about to move five thousand learners
 * forward wants to know <b>what those learners will lose</b>. This answers both, which is why
 * {@link #lostForLearners()} exists beside the plain lists — a node that was removed is the only
 * kind of change that can take somebody's finished work with it.
 */
public record CourseDiff(List<String> addedNodes, List<String> removedNodes,
                         List<String> reorderedModules, List<String> requirementChanges,
                         List<String> gateChanges, List<String> textChanges) {

    public CourseDiff {
        addedNodes = List.copyOf(addedNodes);
        removedNodes = List.copyOf(removedNodes);
        reorderedModules = List.copyOf(reorderedModules);
        requirementChanges = List.copyOf(requirementChanges);
        gateChanges = List.copyOf(gateChanges);
        textChanges = List.copyOf(textChanges);
    }

    /** Nothing a learner must do is different -- only wording. */
    public boolean isTextOnly() {
        return addedNodes.isEmpty() && removedNodes.isEmpty() && reorderedModules.isEmpty()
            && requirementChanges.isEmpty() && gateChanges.isEmpty();
    }

    public boolean isEmpty() {
        return isTextOnly() && textChanges.isEmpty();
    }

    /**
     * What a learner moved onto the newer version stands to lose, in words an administrator can act
     * on.
     *
     * <p>Only removals and new requirements cost anybody anything: a node that no longer exists
     * takes any completion of it out of the count, and a node newly made required adds work
     * somebody had legitimately skipped. Additions and reordering are shown in the diff but are not
     * losses, and calling them losses would train people to ignore the warning.
     */
    public List<String> lostForLearners() {
        List<String> losses = new ArrayList<>(removedNodes.stream()
            .map(node -> "Completion of " + node + " will no longer count: the step is gone.")
            .toList());
        losses.addAll(requirementChanges.stream()
            .filter(change -> change.contains("now required"))
            .map(change -> change + " Learners who skipped it will be incomplete again.")
            .toList());
        return List.copyOf(losses);
    }

    /** Compares two snapshots. Order matters: {@code from} is the older one. */
    public static CourseDiff between(CourseSnapshot from, CourseSnapshot to) {
        Map<UUID, CourseSnapshot.Node> before = nodesById(from);
        Map<UUID, CourseSnapshot.Node> after = nodesById(to);

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> requirements = new ArrayList<>();
        List<String> gates = new ArrayList<>();
        for (UUID id : after.keySet()) {
            if (!before.containsKey(id)) {
                added.add("step " + shortId(id));
                continue;
            }
            CourseSnapshot.Node was = before.get(id);
            CourseSnapshot.Node now = after.get(id);
            if (was.required() != now.required()) {
                requirements.add("step " + shortId(id)
                    + (now.required() ? " is now required." : " is now optional."));
            }
            if (!java.util.Objects.equals(was.gate(), now.gate())) {
                gates.add("the rule unlocking step " + shortId(id) + " changed.");
            }
        }
        for (UUID id : before.keySet()) {
            if (!after.containsKey(id)) {
                removed.add("step " + shortId(id));
            }
        }

        List<String> reordered = new ArrayList<>();
        if (!orderOf(from).equals(orderOf(to))) {
            reordered.add("the order of steps changed.");
        }

        List<String> text = new ArrayList<>();
        if (!java.util.Objects.equals(from.title(), to.title())) {
            text.add("the course title changed.");
        }
        if (!java.util.Objects.equals(from.description(), to.description())) {
            text.add("the course description changed.");
        }
        Map<UUID, String> beforeTitles = moduleTitles(from);
        Map<UUID, String> afterTitles = moduleTitles(to);
        for (Map.Entry<UUID, String> entry : afterTitles.entrySet()) {
            String was = beforeTitles.get(entry.getKey());
            if (was != null && !was.equals(entry.getValue())) {
                text.add("a module was renamed from \"" + was + "\" to \"" + entry.getValue() + "\".");
            }
        }
        return new CourseDiff(added, removed, reordered, requirements, gates, text);
    }

    private static Map<UUID, CourseSnapshot.Node> nodesById(CourseSnapshot snapshot) {
        Map<UUID, CourseSnapshot.Node> nodes = new LinkedHashMap<>();
        for (CourseSnapshot.Module module : snapshot.modules()) {
            for (CourseSnapshot.Node node : module.nodes()) {
                nodes.put(node.id(), node);
            }
        }
        return nodes;
    }

    /** The sequence a learner walks, which is what reordering changes and renaming does not. */
    private static List<UUID> orderOf(CourseSnapshot snapshot) {
        List<UUID> order = new ArrayList<>();
        for (CourseSnapshot.Module module : snapshot.modules()) {
            order.add(module.id());
            module.nodes().forEach(node -> order.add(node.id()));
        }
        return order;
    }

    private static Map<UUID, String> moduleTitles(CourseSnapshot snapshot) {
        Map<UUID, String> titles = new LinkedHashMap<>();
        snapshot.modules().forEach(module -> titles.put(module.id(), module.title()));
        return titles;
    }

    /** Enough of an id for a human to match it against a screen, without the noise of all 36. */
    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /** Every node id mentioned by either side, for callers that want to resolve titles. */
    public static Set<UUID> nodesMentioned(CourseSnapshot from, CourseSnapshot to) {
        Set<UUID> all = new LinkedHashSet<>(nodesById(from).keySet());
        all.addAll(nodesById(to).keySet());
        return all;
    }
}
