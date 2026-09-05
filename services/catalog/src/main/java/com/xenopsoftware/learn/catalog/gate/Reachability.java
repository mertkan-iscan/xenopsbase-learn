package com.xenopsoftware.learn.catalog.gate;

import java.util.List;
import java.util.UUID;

/**
 * Whether one part of a course is reachable, and the sentence that says why (T-5.3).
 *
 * <p><b>Both fields are produced by one walk of the requirements, and that is the whole point of
 * this type.</b> The obvious shape is a boolean from an evaluator and a string from a formatter,
 * and they drift: the day somebody adds a requirement kind to one and forgets the other, a learner
 * is told they may proceed by a screen while the server says no. Here the sentence is a by-product
 * of the same loop that produced the answer, so it cannot describe a different rule.
 *
 * @param unmet the requirements not yet satisfied, so a UI can render them as a checklist rather
 *              than re-parsing the sentence
 */
public record Reachability(StructurePart part, UUID id, boolean reachable, String explanation,
                           List<Unmet> unmet) {

    /** One requirement a learner has not met, named the way they would recognise it. */
    public record Unmet(StructurePart part, UUID id, String title, RequiredState state) {

        /** "complete Week one", "pass Safety test". */
        public String phrase() {
            return state.verb() + " " + title;
        }
    }

    public Reachability {
        unmet = List.copyOf(unmet);
    }

    /** Nothing guards this. */
    static Reachability open(StructurePart part, UUID id) {
        return new Reachability(part, id, true, "Available.", List.of());
    }

    /**
     * Reachable because the learner already finished it.
     *
     * <p>The case T-5.3's fifth criterion is about: a gate added to something somebody has already
     * completed must not retroactively lock it. Checked before the gate is even read, so no
     * combination of requirements can produce a different answer.
     */
    static Reachability alreadyDone(StructurePart part, UUID id) {
        return new Reachability(part, id, true, "You have already completed this.", List.of());
    }
}
