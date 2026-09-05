package com.xenopsoftware.learn.catalog.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rule's own behaviour: the decision and the sentence, from one walk (T-5.3).
 *
 * <p>No Spring and no container. This is where the criterion that matters most lives — <b>a
 * human-readable explanation generated from the same rule that evaluates it</b> — and it should
 * fail in the first millisecond of a build rather than after a container starts.
 */
class GateRuleTest {

    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID MODULE_ONE = UUID.randomUUID();
    private static final UUID SAFETY_TEST = UUID.randomUUID();
    private static final UUID INDUCTION = UUID.randomUUID();

    private static final Map<UUID, String> TITLES = Map.of(
        MODULE_ONE, "Module 1",
        SAFETY_TEST, "the safety test",
        INDUCTION, "the induction video");

    @Test
    void theSentenceIsTheOneTheIssueAsksFor() {
        GateRule rule = new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, List.of(
            new GateRule.Requirement(StructurePart.MODULE, MODULE_ONE, RequiredState.COMPLETED),
            new GateRule.Requirement(StructurePart.NODE, SAFETY_TEST, RequiredState.PASSED)));

        Reachability answer = rule.evaluate(Map.of(), TITLES);

        assertThat(answer.reachable()).isFalse();
        assertThat(answer.explanation())
            .as("\"Locked\" generates support tickets; this does not")
            .isEqualTo("To unlock this, complete Module 1 and pass the safety test.");
    }

    @Test
    void anyReadsAsOrAndAllReadsAsAnd() {
        List<GateRule.Requirement> two = List.of(
            new GateRule.Requirement(StructurePart.NODE, SAFETY_TEST, RequiredState.PASSED),
            new GateRule.Requirement(StructurePart.NODE, INDUCTION, RequiredState.COMPLETED));

        assertThat(new GateRule(StructurePart.NODE, TARGET, Combinator.ANY, two)
            .evaluate(Map.of(), TITLES).explanation())
            .isEqualTo("To unlock this, pass the safety test or complete the induction video.");
        assertThat(new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, two)
            .evaluate(Map.of(), TITLES).explanation())
            .isEqualTo("To unlock this, pass the safety test and complete the induction video.");
    }

    @Test
    void threeRequirementsReadAsAListRatherThanAChain() {
        GateRule rule = new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, List.of(
            new GateRule.Requirement(StructurePart.MODULE, MODULE_ONE, RequiredState.COMPLETED),
            new GateRule.Requirement(StructurePart.NODE, INDUCTION, RequiredState.COMPLETED),
            new GateRule.Requirement(StructurePart.NODE, SAFETY_TEST, RequiredState.PASSED)));

        assertThat(rule.evaluate(Map.of(), TITLES).explanation()).isEqualTo(
            "To unlock this, complete Module 1, complete the induction video "
            + "and pass the safety test.");
    }

    @Test
    void theSentenceNamesOnlyWhatIsStillOutstanding() {
        GateRule rule = new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, List.of(
            new GateRule.Requirement(StructurePart.MODULE, MODULE_ONE, RequiredState.COMPLETED),
            new GateRule.Requirement(StructurePart.NODE, SAFETY_TEST, RequiredState.PASSED)));

        Reachability answer = rule.evaluate(
            Map.of(MODULE_ONE, Set.of(RequiredState.COMPLETED)), TITLES);

        // A learner who has done one of two wants to know about the other. Listing both reads as
        // though the first did not count.
        assertThat(answer.explanation()).isEqualTo("To unlock this, pass the safety test.");
        assertThat(answer.unmet()).hasSize(1);
        assertThat(answer.unmet().getFirst().phrase()).isEqualTo("pass the safety test");
    }

    @Test
    void aMetRequirementUnderAllIsNotEnoughAndUnderAnyItIs() {
        List<GateRule.Requirement> two = List.of(
            new GateRule.Requirement(StructurePart.MODULE, MODULE_ONE, RequiredState.COMPLETED),
            new GateRule.Requirement(StructurePart.NODE, SAFETY_TEST, RequiredState.PASSED));
        Map<UUID, Set<RequiredState>> oneDone = Map.of(MODULE_ONE, Set.of(RequiredState.COMPLETED));

        assertThat(new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, two)
            .evaluate(oneDone, TITLES).reachable()).isFalse();
        assertThat(new GateRule(StructurePart.NODE, TARGET, Combinator.ANY, two)
            .evaluate(oneDone, TITLES).reachable()).isTrue();
    }

    @Test
    void completingSomethingIsNotPassingIt() {
        GateRule rule = new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, List.of(
            new GateRule.Requirement(StructurePart.NODE, SAFETY_TEST, RequiredState.PASSED)));

        // Sitting the test is not passing it, and a gate that could not tell them apart would let
        // somebody through a compliance requirement by opening the page.
        assertThat(rule.evaluate(Map.of(SAFETY_TEST, Set.of(RequiredState.COMPLETED)), TITLES)
            .reachable()).isFalse();
        assertThat(rule.evaluate(Map.of(SAFETY_TEST, Set.of(RequiredState.PASSED)), TITLES)
            .reachable()).isTrue();
    }

    @Test
    void aGateWithNoRequirementsIsOpen() {
        // An author who removed the last requirement meant "no longer locked", not "locked
        // forever" -- which is what ALL over an empty list would otherwise mean.
        assertThat(new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, List.of())
            .evaluate(Map.of(), TITLES).reachable()).isTrue();
    }

    @Test
    void aRequirementWhoseTargetVanishedIsStillSayable() {
        GateRule rule = new GateRule(StructurePart.NODE, TARGET, Combinator.ALL, List.of(
            new GateRule.Requirement(StructurePart.NODE, UUID.randomUUID(), RequiredState.COMPLETED)));

        // A broken course explains itself instead of returning a 500 to a learner.
        assertThat(rule.evaluate(Map.of(), TITLES).explanation())
            .isEqualTo("To unlock this, complete a removed step.");
    }
}
