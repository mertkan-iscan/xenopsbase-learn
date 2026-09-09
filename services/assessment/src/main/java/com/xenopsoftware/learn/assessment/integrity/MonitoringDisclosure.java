package com.xenopsoftware.learn.assessment.integrity;

import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * What the learner is told, before they start (T-6.8).
 *
 * <p>The criterion is "what is collected is disclosed to the learner before the attempt starts",
 * and this is the server's half of it: one plain list, generated from the same enum the recorder
 * accepts, so <b>a signal cannot be collected without appearing here</b>. A disclosure written as
 * prose in a template is one that stops matching the code the first time somebody adds a kind.
 *
 * <h2>The half this cannot do</h2>
 *
 * <p>A server cannot make a client display anything. What it can do is make the disclosure
 * impossible to miss: it is returned by its own endpoint <em>and</em> included in the response that
 * starts an attempt, so a player has been handed it before it can render a single question. A
 * client that throws it away is a client that lied to its user, and no API shape prevents that.
 *
 * <h2>What is deliberately not said</h2>
 *
 * <p>Nothing here promises the platform will notice anything. It will not: these signals are
 * self-reported by the learner's own browser, and a learner who wants to hide a focus loss simply
 * does not report one. Writing "we monitor your screen" would be false, and it is the exact
 * sentence somebody would put on a sales page.
 */
@Component
public class MonitoringDisclosure {

    /**
     * @param collects  one line per signal, in the learner's terms
     * @param usedFor   what a person may do with them
     * @param neverUsedFor what nothing does with them, stated because it is the promise
     * @param keptForDays how long they are kept — separately from the result itself
     */
    public record Disclosure(List<String> collects, String usedFor, String neverUsedFor,
                             long keptForDays) {}

    private final IntegrityProperties properties;

    public MonitoringDisclosure(IntegrityProperties properties) {
        this.properties = properties;
    }

    public Disclosure forLearner() {
        return new Disclosure(
            java.util.Arrays.stream(IntegritySignal.values())
                .map(IntegritySignal::disclosure)
                .toList(),
            "A person reviewing a result may look at them alongside your answers.",
            "Nothing decides your score from them. No signal here can fail you, reduce your mark, "
                + "or end your attempt.",
            Duration.ofSeconds(properties.retention().toSeconds()).toDays());
    }
}
