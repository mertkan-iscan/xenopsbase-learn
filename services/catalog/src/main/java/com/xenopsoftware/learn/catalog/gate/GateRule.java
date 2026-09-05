package com.xenopsoftware.learn.catalog.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One gate, in the shape that decides and explains at the same time (T-5.3).
 *
 * <p>This is the class the acceptance criteria really turn on: <b>a human-readable explanation
 * generated from the same rule that evaluates it</b>. {@link #evaluate} walks the requirements
 * once and builds the answer and the sentence out of the same pass, so no future change can move
 * one without the other. A design with {@code boolean isReachable()} beside {@code String
 * explain()} looks tidier and is the one that eventually tells a learner they may proceed while
 * the server refuses.
 *
 * @param requirements in the order an author wrote them, because that is the order the sentence
 *                     should read in — sorting them by id would produce a correct rule and a
 *                     sentence that lists the safety test before the induction video
 */
public record GateRule(StructurePart targetPart, UUID targetId, Combinator combinator,
                       List<Requirement> requirements) {

    /** One thing that must be reached, and what "reached" means for it. */
    public record Requirement(StructurePart part, UUID id, RequiredState state) {}

    public GateRule {
        requirements = List.copyOf(requirements);
    }

    /**
     * Whether this gate opens, and what to tell the learner either way.
     *
     * @param satisfied  what the learner has reached: for each part, the states it is in
     * @param titles     what each referenced part is called, for the sentence
     */
    public Reachability evaluate(Map<UUID, Set<RequiredState>> satisfied, Map<UUID, String> titles) {
        List<Reachability.Unmet> unmet = new ArrayList<>();
        int met = 0;
        for (Requirement requirement : requirements) {
            if (satisfied.getOrDefault(requirement.id(), Set.of()).contains(requirement.state())) {
                met++;
            } else {
                unmet.add(new Reachability.Unmet(requirement.part(), requirement.id(),
                    // A requirement whose target was deleted out from under it still has to be
                    // sayable. Naming it rather than crashing keeps a broken course explaining
                    // itself instead of returning a 500 to a learner.
                    titles.getOrDefault(requirement.id(), "a removed step"),
                    requirement.state()));
            }
        }

        boolean open = requirements.isEmpty()
            || (combinator == Combinator.ALL ? unmet.isEmpty() : met > 0);
        if (open) {
            return new Reachability(targetPart, targetId, true, "Available.", List.of());
        }
        return new Reachability(targetPart, targetId, false, sentence(unmet), unmet);
    }

    /**
     * The rule, read aloud.
     *
     * <p>Built from the SAME list the evaluation just walked. It names only what is still
     * outstanding, because a learner who has done two of three things wants to know about the
     * third — "Complete Module 1, Module 2 and Module 3" when two are done reads as though nothing
     * counted.
     *
     * <p>Under ANY the whole list is outstanding by construction (one met requirement would have
     * opened the gate), so the same phrasing serves both combinators with only the conjunction
     * changing.
     */
    private String sentence(List<Reachability.Unmet> unmet) {
        List<String> phrases = unmet.stream().map(Reachability.Unmet::phrase).toList();
        StringBuilder sentence = new StringBuilder("To unlock this, ");
        for (int i = 0; i < phrases.size(); i++) {
            if (i > 0) {
                // Oxford-comma-free list: "a, b and c" / "a, b or c". The last join takes the
                // combinator's word, every earlier one takes a comma.
                sentence.append(i == phrases.size() - 1 ? " " + combinator.conjunction() + " " : ", ");
            }
            sentence.append(phrases.get(i));
        }
        return sentence.append('.').toString();
    }
}
