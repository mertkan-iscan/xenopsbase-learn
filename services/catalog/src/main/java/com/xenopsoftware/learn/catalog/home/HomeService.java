package com.xenopsoftware.learn.catalog.home;

import com.xenopsoftware.learn.catalog.assign.AssignmentService;
import com.xenopsoftware.learn.catalog.assign.ReferenceKind;
import com.xenopsoftware.learn.catalog.content.ContentItem;
import com.xenopsoftware.learn.catalog.content.ContentItemRepository;
import com.xenopsoftware.learn.catalog.gate.GateRule;
import com.xenopsoftware.learn.catalog.gate.GateService;
import com.xenopsoftware.learn.catalog.gate.Reachability;
import com.xenopsoftware.learn.catalog.gate.RequiredState;
import com.xenopsoftware.learn.catalog.gate.StructurePart;
import com.xenopsoftware.learn.catalog.structure.CourseModule;
import com.xenopsoftware.learn.catalog.structure.CourseModuleRepository;
import com.xenopsoftware.learn.catalog.structure.CourseNode;
import com.xenopsoftware.learn.catalog.structure.CourseNodeRepository;
import com.xenopsoftware.learn.catalog.structure.CourseService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE LEARNER HOME SCREEN, ASSEMBLED ONCE (T-5.8).
 *
 * <h2>Why this is a service and not five calls from a controller</h2>
 *
 * This is the most-hit authenticated read in the product and it touches assignments, group reach,
 * course structure, gates, completions, progress and due dates at once. Assembled naively it is a
 * query per assignment — and worse, a query per module inside each of those — which is invisible on
 * a demo tenant with three courses and is the first thing to fall over at a customer with five
 * thousand people.
 *
 * <p>So every read here is <b>bulk and bounded</b>: the number of queries is the same for a learner
 * with one assignment and a learner with fifty. {@code HomeQueryBudgetTest} asserts exactly that,
 * because the property is easy to lose in a one-line change and impossible to see in a small test.
 *
 * <h2>Nothing here is a second opinion</h2>
 *
 * Every fact comes from the module that owns it, through the code that already decides it:
 *
 * <ul>
 *   <li><b>Due dates and overdue</b> from {@link AssignmentService#obligationsOf}, which is T-5.6's
 *       implementation of both — including the learner's own timezone.</li>
 *   <li><b>Locked and why</b> from {@link GateService#evaluate}, which is T-5.3's rule, given rows
 *       this class has already loaded rather than loading its own.</li>
 *   <li><b>How far through</b> from {@code node_progress}, which is streaming's derivation
 *       (ADR-0107) arriving by event — the same numbers reporting consumes, not a parallel
 *       calculation over completions.</li>
 * </ul>
 *
 * <p>A screen that recomputed any of those would be a second answer to a question the platform has
 * already answered, and the two would disagree on the day it mattered.
 */
@Service
public class HomeService {

    /** A deadline inside this window is "due soon" — the number a header line warns on. */
    private static final int DUE_SOON_DAYS = 7;

    private final AssignmentService assignments;
    private final CourseService courses;
    private final CourseModuleRepository modules;
    private final CourseNodeRepository nodes;
    private final ContentItemRepository items;
    private final GateService gates;
    private final com.xenopsoftware.learn.catalog.gate.NodeCompletionRepository completions;
    private final NodeProgressProjection progress;

    public HomeService(AssignmentService assignments, CourseService courses,
            CourseModuleRepository modules, CourseNodeRepository nodes, ContentItemRepository items,
            GateService gates,
            com.xenopsoftware.learn.catalog.gate.NodeCompletionRepository completions,
            NodeProgressProjection progress) {
        this.assignments = assignments;
        this.courses = courses;
        this.modules = modules;
        this.nodes = nodes;
        this.items = items;
        this.gates = gates;
        this.completions = completions;
        this.progress = progress;
    }

    /**
     * What this learner sees, as of {@code now}.
     *
     * <p>The clock is an argument so that a test can ask what the screen looks like the day after a
     * deadline without waiting for one, and so every row in one answer is reckoned against the same
     * instant — a screen where one course went overdue between two calls to {@code now()} is a
     * screen nobody can explain.
     */
    @Transactional(readOnly = true)
    public HomeView forLearner(UUID learnerId, Instant now) {
        String tenantId = TenantContext.require();
        List<AssignmentService.Obligation> obligations = assignments.obligationsOf(learnerId, now);
        if (obligations.isEmpty()) {
            // The first-run state, decided here rather than left to a client to infer from an
            // empty list. Somebody whose company has not assigned them anything yet should be told
            // that, not shown the same blank page as somebody whose service is broken.
            return new HomeView(HomeView.NOTHING_ASSIGNED, new HomeView.Summary(0, 0, 0, 0, 0),
                null, List.of(), List.of(), now);
        }

        Structure structure = structureFor(obligations);
        Set<UUID> nodeIds = structure.nodeIds();
        Map<UUID, Set<RequiredState>> satisfied =
            completions.statesOf(tenantId, learnerId, nodeIds);
        Map<UUID, NodeProgressProjection.Progress> howFar = progress.of(tenantId, learnerId);
        Map<UUID, GateRule> rules = gates.rulesFor(structure.trees().keySet());

        List<HomeView.CourseView> courseViews = new ArrayList<>();
        List<HomeView.ItemView> itemViews = new ArrayList<>();
        Map<UUID, HomeView.NodeView> nodeViews = new HashMap<>();
        Map<UUID, UUID> courseOfNode = new HashMap<>();

        for (AssignmentService.Obligation obligation : obligations) {
            if (obligation.referenceType() == ReferenceKind.COURSE) {
                CourseService.CourseTree tree = structure.trees().get(obligation.referenceId());
                if (tree == null) {
                    // Assigned a course that has since been deleted. Skipped rather than rendered
                    // as an error: the row is real, the course is not, and a screen is the wrong
                    // place to find out about it.
                    continue;
                }
                courseViews.add(courseView(obligation, tree, structure, rules, satisfied, howFar,
                    nodeViews, courseOfNode));
            } else {
                itemViews.add(itemView(obligation, structure, howFar, satisfied));
            }
        }

        HomeView.NextUp next = nextUp(courseViews, nodeViews);
        return new HomeView(stateOf(courseViews, itemViews, next), summaryOf(courseViews, itemViews,
            now), next, List.copyOf(courseViews), List.copyOf(itemViews), now);
    }

    // ------------------------------------------------------------------ the bulk reads

    /**
     * Every structural row the screen needs, in a fixed number of queries.
     *
     * <p>Assignments can point at a course, a module, a node or a content item (T-5.5). The first
     * three live inside a course, and a node needs its course to be gated at all — so this resolves
     * whichever were assigned back to their courses first, and then loads those courses whole.
     */
    private Structure structureFor(List<AssignmentService.Obligation> obligations) {
        Set<UUID> courseIds = new LinkedHashSet<>();
        Set<UUID> moduleRefs = new LinkedHashSet<>();
        Set<UUID> nodeRefs = new LinkedHashSet<>();
        Set<UUID> itemRefs = new LinkedHashSet<>();
        for (AssignmentService.Obligation obligation : obligations) {
            switch (obligation.referenceType()) {
                case COURSE -> courseIds.add(obligation.referenceId());
                case MODULE -> moduleRefs.add(obligation.referenceId());
                case NODE -> nodeRefs.add(obligation.referenceId());
                case CONTENT_ITEM -> itemRefs.add(obligation.referenceId());
            }
        }

        Map<UUID, CourseNode> assignedNodes = new LinkedHashMap<>();
        if (!nodeRefs.isEmpty()) {
            nodes.findAllById(nodeRefs).forEach(node -> assignedNodes.put(node.getId(), node));
        }
        Set<UUID> moduleIds = new LinkedHashSet<>(moduleRefs);
        assignedNodes.values().forEach(node -> moduleIds.add(node.getModuleId()));

        Map<UUID, CourseModule> assignedModules = new LinkedHashMap<>();
        if (!moduleIds.isEmpty()) {
            modules.findAllById(moduleIds)
                .forEach(module -> assignedModules.put(module.getId(), module));
        }
        assignedModules.values().forEach(module -> courseIds.add(module.getCourseId()));

        Map<UUID, CourseService.CourseTree> trees = courses.trees(courseIds);

        // Titles: a node has none of its own -- it is a placement of a content item -- so every
        // item referenced anywhere on this screen is fetched in ONE query, and the per-node lookup
        // that would otherwise creep back in is never written.
        Set<UUID> itemIds = new LinkedHashSet<>(itemRefs);
        for (CourseService.CourseTree tree : trees.values()) {
            for (CourseService.ModuleTree module : tree.modules()) {
                module.nodes().forEach(node -> itemIds.add(node.getContentItemId()));
            }
        }
        Map<UUID, ContentItem> itemsById = new LinkedHashMap<>();
        if (!itemIds.isEmpty()) {
            items.findAllById(itemIds).forEach(item -> itemsById.put(item.getId(), item));
        }
        return new Structure(trees, assignedModules, assignedNodes, itemsById);
    }

    /** Everything loaded once, held together so the assembly below can be pure. */
    private record Structure(Map<UUID, CourseService.CourseTree> trees,
                             Map<UUID, CourseModule> assignedModules,
                             Map<UUID, CourseNode> assignedNodes,
                             Map<UUID, ContentItem> items) {

        Set<UUID> nodeIds() {
            Set<UUID> ids = new LinkedHashSet<>(assignedNodes.keySet());
            for (CourseService.CourseTree tree : trees.values()) {
                for (CourseService.ModuleTree module : tree.modules()) {
                    module.nodes().forEach(node -> ids.add(node.getId()));
                }
            }
            return ids;
        }

        String titleOf(CourseNode node) {
            ContentItem item = items.get(node.getContentItemId());
            return item == null ? "a step" : item.getTitle();
        }

        String typeOf(CourseNode node) {
            ContentItem item = items.get(node.getContentItemId());
            return item == null ? null : item.getType();
        }

        /** The titles the gate rule needs to say "complete Week one" (T-5.3). */
        Map<UUID, String> titlesIn(CourseService.CourseTree tree) {
            Map<UUID, String> titles = new LinkedHashMap<>();
            for (CourseService.ModuleTree module : tree.modules()) {
                titles.put(module.module().getId(), module.module().getTitle());
                module.nodes().forEach(node -> titles.put(node.getId(), titleOf(node)));
            }
            return titles;
        }
    }

    // ------------------------------------------------------------------ the assembly

    private HomeView.CourseView courseView(AssignmentService.Obligation obligation,
            CourseService.CourseTree tree, Structure structure, Map<UUID, GateRule> rules,
            Map<UUID, Set<RequiredState>> satisfied,
            Map<UUID, NodeProgressProjection.Progress> howFar,
            Map<UUID, HomeView.NodeView> nodeViews, Map<UUID, UUID> courseOfNode) {
        // T-5.3's rule, over rows already in memory. No query, and no second idea of what "locked"
        // means: this is the same method the course screen calls.
        Map<UUID, Reachability> reachable = new HashMap<>();
        for (Reachability answer : gates.evaluate(tree, rules, structure.titlesIn(tree), satisfied)) {
            reachable.put(answer.id(), answer);
        }

        List<HomeView.ModuleView> moduleViews = new ArrayList<>();
        int required = 0;
        int done = 0;
        for (CourseService.ModuleTree module : tree.modules()) {
            Reachability moduleAnswer = reachable.get(module.module().getId());
            boolean moduleLocked = moduleAnswer != null && !moduleAnswer.reachable();
            List<HomeView.NodeView> nodeList = new ArrayList<>();
            for (CourseNode node : module.nodes()) {
                HomeView.NodeView view = nodeView(node, structure, reachable, satisfied, howFar);
                nodeList.add(view);
                nodeViews.put(node.getId(), view);
                courseOfNode.put(node.getId(), tree.course().getId());
                if (node.isRequired()) {
                    required++;
                    if ("COMPLETE".equals(view.state())) {
                        done++;
                    }
                }
            }
            moduleViews.add(new HomeView.ModuleView(module.module().getId(),
                module.module().getTitle(), moduleLocked,
                moduleLocked ? moduleAnswer.explanation() : null, List.copyOf(nodeList)));
        }

        // Structural, not an average of how far into each video somebody is: what a course is
        // "done" means the same thing here as it does to a gate (T-5.3), which is the only way a
        // learner reading 100% and a gate opening can be the same event.
        int percent = required == 0 ? 0 : (int) Math.floor(done * 100.0 / required);
        return new HomeView.CourseView(tree.course().getId(), tree.course().getTitle(),
            obligation.dueOn(), obligation.overdue(), obligation.cycleNumber(), percent,
            required > 0 && done == required, obligation.sources(), List.copyOf(moduleViews));
    }

    /**
     * Is this node finished, as a learner would say it?
     *
     * <p><b>PASSED counts, and it did not.</b> Both places above asked only for
     * {@code COMPLETED}, so somebody who sat a test and passed it saw the node still AVAILABLE,
     * the course at 0%, and their home screen reporting nothing completed. The row was in
     * {@code node_completion} the whole time, written by {@code AttemptGradedHandler}, saying
     * PASSED.
     *
     * <p>The two states are genuinely different TO A GATE — "complete Module 1" and "pass the
     * safety test" are different requirements, which is why {@link RequiredState} has both and why
     * the gate evaluator must keep asking for exactly the one it was given. They are not different
     * to the question this screen asks, which is only ever "is there anything left for me to do
     * here". Passing a test is finishing the node that holds it.
     *
     * <p>Found by sitting a test on the cluster: everything up to the verdict worked, the verdict
     * reached the learner, and their home screen went on saying they had done nothing.
     */
    private static boolean isDone(Set<RequiredState> satisfied) {
        return satisfied != null
            && (satisfied.contains(RequiredState.COMPLETED)
                || satisfied.contains(RequiredState.PASSED));
    }

    private HomeView.NodeView nodeView(CourseNode node, Structure structure,
            Map<UUID, Reachability> reachable, Map<UUID, Set<RequiredState>> satisfied,
            Map<UUID, NodeProgressProjection.Progress> howFar) {
        NodeProgressProjection.Progress made = howFar.get(node.getId());
        Reachability answer = reachable.get(node.getId());
        boolean locked = answer != null && !answer.reachable();
        boolean complete = isDone(satisfied.get(node.getId()));
        int percent = made == null ? 0 : made.percent();
        String state = complete ? "COMPLETE"
            : locked ? "LOCKED"
            : percent > 0 ? "IN_PROGRESS"
            : "AVAILABLE";
        return new HomeView.NodeView(node.getId(), structure.titleOf(node), structure.typeOf(node),
            node.isRequired(), state, locked ? answer.explanation() : null, percent,
            made == null ? 0 : made.resumeSecond());
    }

    private HomeView.ItemView itemView(AssignmentService.Obligation obligation,
            Structure structure, Map<UUID, NodeProgressProjection.Progress> howFar,
            Map<UUID, Set<RequiredState>> satisfied) {
        UUID id = obligation.referenceId();
        String title = switch (obligation.referenceType()) {
            case MODULE -> structure.assignedModules().containsKey(id)
                ? structure.assignedModules().get(id).getTitle() : "a module";
            case NODE -> structure.assignedNodes().containsKey(id)
                ? structure.titleOf(structure.assignedNodes().get(id)) : "a step";
            case CONTENT_ITEM -> structure.items().containsKey(id)
                ? structure.items().get(id).getTitle() : "an item";
            case COURSE -> "a course";
        };
        NodeProgressProjection.Progress made =
            obligation.referenceType() == ReferenceKind.NODE ? howFar.get(id) : null;
        boolean complete = obligation.referenceType() == ReferenceKind.NODE
            && isDone(satisfied.get(id));
        int percent = made == null ? 0 : made.percent();
        String state = complete ? "COMPLETE" : percent > 0 ? "IN_PROGRESS" : "AVAILABLE";
        return new HomeView.ItemView(obligation.referenceType().name(), id, title, state,
            obligation.dueOn(), obligation.overdue(), obligation.cycleNumber(), percent,
            made == null ? 0 : made.resumeSecond(), obligation.sources());
    }

    /**
     * The one thing to put behind "continue".
     *
     * <p>Decided here rather than in a client, because the rule uses everything this answer knows.
     * The most urgent course first — overdue, then soonest due, then oldest assigned — and within
     * it whatever the learner already started, because being returned to the middle of a video is
     * what makes a screen feel like it remembers you. Failing that, the first available step.
     */
    private HomeView.NextUp nextUp(List<HomeView.CourseView> courseViews,
            Map<UUID, HomeView.NodeView> nodeViews) {
        List<HomeView.CourseView> byUrgency = new ArrayList<>(courseViews);
        byUrgency.sort(Comparator
            .comparing(HomeView.CourseView::overdue, Comparator.reverseOrder())
            .thenComparing(course -> course.dueOn() == null ? LocalDate.MAX : course.dueOn()));

        for (HomeView.CourseView course : byUrgency) {
            HomeView.NodeView started = null;
            HomeView.NodeView available = null;
            for (HomeView.ModuleView module : course.modules()) {
                for (HomeView.NodeView node : module.nodes()) {
                    if ("IN_PROGRESS".equals(node.state()) && started == null) {
                        started = node;
                    }
                    if ("AVAILABLE".equals(node.state()) && available == null) {
                        available = node;
                    }
                }
            }
            HomeView.NodeView chosen = started != null ? started : available;
            if (chosen != null) {
                return new HomeView.NextUp(course.courseId(), course.title(), chosen.nodeId(),
                    chosen.title(), chosen.percent(), chosen.resumeSecond(), course.dueOn(),
                    course.overdue());
            }
        }
        return null;
    }

    private String stateOf(List<HomeView.CourseView> courseViews, List<HomeView.ItemView> itemViews,
            HomeView.NextUp next) {
        if (courseViews.isEmpty() && itemViews.isEmpty()) {
            return HomeView.NOTHING_ASSIGNED;
        }
        boolean everythingDone = courseViews.stream().allMatch(HomeView.CourseView::completed)
            && itemViews.stream().allMatch(item -> "COMPLETE".equals(item.state()));
        // "Finished everything" and "there is nothing you can start" are different sentences, and
        // only the first is worth celebrating: a learner whose whole list is locked behind a gate
        // has not finished anything.
        return everythingDone && next == null ? HomeView.ALL_DONE : HomeView.READY;
    }

    private HomeView.Summary summaryOf(List<HomeView.CourseView> courseViews,
            List<HomeView.ItemView> itemViews, Instant now) {
        int assigned = courseViews.size() + itemViews.size();
        int completed = (int) courseViews.stream().filter(HomeView.CourseView::completed).count()
            + (int) itemViews.stream().filter(item -> "COMPLETE".equals(item.state())).count();
        // Started and not finished, where "started" includes being part-way through one video:
        // percentComplete counts finished steps, so a learner forty per cent into their first one
        // is at zero per cent of the course and is plainly in progress.
        int inProgress = (int) courseViews.stream()
            .filter(course -> !course.completed()
                && (course.percentComplete() > 0 || course.modules().stream()
                    .flatMap(module -> module.nodes().stream())
                    .anyMatch(node -> "IN_PROGRESS".equals(node.state()))))
            .count()
            + (int) itemViews.stream().filter(item -> "IN_PROGRESS".equals(item.state())).count();
        int overdue = (int) courseViews.stream().filter(HomeView.CourseView::overdue).count()
            + (int) itemViews.stream().filter(HomeView.ItemView::overdue).count();

        LocalDate today = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        int dueSoon = (int) courseViews.stream()
            .filter(course -> dueSoon(course.dueOn(), course.overdue(), today)).count()
            + (int) itemViews.stream()
            .filter(item -> dueSoon(item.dueOn(), item.overdue(), today)).count();
        return new HomeView.Summary(assigned, completed, inProgress, overdue, dueSoon);
    }

    /**
     * Whether a deadline is close enough to warn about.
     *
     * <p>Counted in whole days against UTC rather than the learner's zone, deliberately: this is a
     * badge on a header, not the deadline itself. Whether somebody is <em>overdue</em> is reckoned
     * in their own timezone by T-5.6, where being a day out would be a false accusation; whether a
     * count says "3 due soon" or "2 due soon" at midnight is not.
     */
    private boolean dueSoon(LocalDate dueOn, boolean overdue, LocalDate today) {
        return dueOn != null && !overdue
            && ChronoUnit.DAYS.between(today, dueOn) <= DUE_SOON_DAYS;
    }
}
