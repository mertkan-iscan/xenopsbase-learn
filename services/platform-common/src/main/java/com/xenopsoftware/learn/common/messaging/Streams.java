package com.xenopsoftware.learn.common.messaging;

import io.nats.client.JetStreamManagement;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bus topology, declared here rather than created by hand (T-9.8's first criterion).
 *
 * <p>A stream somebody made with a CLI on a laptop is a stream that exists in exactly one
 * environment, and the first anybody knows is a consumer in another one that receives nothing —
 * silently, because a subject with no stream behind it is not an error to NATS, it is a message
 * nobody stored. Declaring them in code makes the topology reviewable, diffable, and identical
 * everywhere it is applied.
 *
 * <p>Applied at startup and idempotent: creating what is missing and updating what has drifted, so
 * running it twice is running it once.
 */
public final class Streams {

    private static final Logger LOG = LoggerFactory.getLogger(Streams.class);

    /**
     * One stream per publishing module, named for it and covering its whole subject space.
     *
     * <p>Per module rather than one stream for everything: retention, storage and limits are then
     * chosen per kind of traffic, and a module that starts producing a great deal of something
     * cannot push another module's events out of a shared stream.
     */
    private static final List<StreamConfiguration> TOPOLOGY = List.of(
        stream("identity", "identity.>"),
        stream("catalog", "catalog.>"),
        stream("streaming", "streaming.>"),
        stream("reporting", "reporting.>"),
        // Added when assessment started publishing, which is later than it should have been: it
        // began publishing at T-6.7 and this list did not move, so `assessment.attempt.graded`
        // went into no stream at all. See StreamsTest, which now fails the build for the next one.
        stream("assessment", "assessment.>"));

    /**
     * Every subject a {@code MessageHandler} in this repository subscribes to.
     *
     * <p>Here rather than discovered by scanning, because platform-common cannot see the modules
     * that depend on it. That makes this a list somebody has to remember to update -- but it fails
     * a build when it is wrong, which is the whole difference from the situation it was written
     * for, where the same omission failed a pod on a cluster.
     */
    static final List<String> SUBSCRIBED_SUBJECTS = List.of(
        "identity.group.reach",
        "identity.user.profile",
        "streaming.node.completed",
        "streaming.node.progress",
        "assessment.attempt.graded",
        "assessment.interstitial.answered");

    private Streams() {}

    /**
     * The declared topology, for the test that checks it against what subscribes.
     *
     * <p>Package-private and read-only. {@code TOPOLOGY} is already immutable, so this hands out
     * the list itself rather than a copy.
     */
    static List<StreamConfiguration> declared() {
        return TOPOLOGY;
    }

    /**
     * Makes the broker match this file.
     *
     * @param management the JetStream management interface for the connection
     */
    public static void apply(JetStreamManagement management) {
        for (StreamConfiguration wanted : TOPOLOGY) {
            try {
                if (management.getStreamNames().contains(wanted.getName())) {
                    management.updateStream(wanted);
                } else {
                    management.addStream(wanted);
                }
            } catch (Exception e) {
                // Loud and fatal: a service whose stream is missing publishes into nothing, and
                // the failure it produces later looks like a consumer bug.
                throw new IllegalStateException(
                    "Could not declare stream " + wanted.getName() + ". A subject with no stream "
                    + "behind it silently stores nothing.", e);
            }
        }
        LOG.info("Bus topology applied: {} streams", TOPOLOGY.size());
    }

    private static StreamConfiguration stream(String name, String subjects) {
        return StreamConfiguration.builder()
            .name(name)
            .subjects(subjects)
            // FILE, not memory. The outbox is the record and the broker is transport -- but
            // transport that forgets on every restart turns every deploy into a replay storm.
            .storageType(StorageType.File)
            // LIMITS rather than WorkQueue: several modules may care about the same event, and a
            // work queue would give it to exactly one of them. Which consumer has read what is
            // the consumer's business, not the stream's.
            .retentionPolicy(RetentionPolicy.Limits)
            // A week. Long enough for a consumer to be down for a weekend and catch up; short
            // enough that the broker is not a second database. Anything older is re-derivable
            // from the outbox, which is the actual record.
            .maxAge(Duration.ofDays(7))
            // Duplicate suppression by the message id the outbox generated, over the window a
            // relay retry could plausibly span. Belt to the consumer's braces: it makes the
            // COMMON duplicate free, and ConsumedMessages still handles the rest, because a
            // window is not a guarantee.
            .duplicateWindow(Duration.ofMinutes(10))
            .build();
    }
}
