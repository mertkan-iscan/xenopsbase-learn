package com.xenopsoftware.learn.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.api.StreamConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The topology, checked against what actually subscribes to it.
 *
 * <p>THIS TEST EXISTS BECAUSE THE OMISSION IT CATCHES ALREADY HAPPENED. Assessment began
 * publishing {@code assessment.attempt.graded} at T-6.7 and this list did not move with it, so on
 * the cluster the publisher wrote into a stream that did not exist — silently, exactly as the
 * class comment warns — and catalog's subscriber crash-looped with {@code [SUB-90007] No matching
 * streams for subject}. Every unit test in the repository was green throughout, because nothing
 * compared the two lists.
 *
 * <p>What it cannot do is discover the subjects itself: platform-common is the dependency, so it
 * cannot see the modules that use it. {@link Streams#SUBSCRIBED_SUBJECTS} is therefore a list
 * somebody has to update. The difference from before is where the omission surfaces — a red build
 * here rather than a pod on a cluster.
 */
class StreamsTest {

    @Nested
    @DisplayName("every subject something subscribes to")
    class EverySubscribedSubject {

        @Test
        @DisplayName("falls inside a declared stream")
        void fallsInsideADeclaredStream() {
            for (String subject : Streams.SUBSCRIBED_SUBJECTS) {
                assertThat(streamCovering(subject))
                    .as("no declared stream covers %s", subject)
                    .isNotEmpty();
            }
        }

        /**
         * The whole failure, restated as the assertion that would have caught it.
         *
         * <p>Kept separate from the loop above so a regression names the subject in the test name
         * rather than only in a message.
         */
        @Test
        @DisplayName("includes the one that reached the cluster without a stream")
        void includesTheOneThatReachedTheCluster() {
            assertThat(streamCovering("assessment.attempt.graded")).contains("assessment");
        }
    }

    @Nested
    @DisplayName("the topology itself")
    class TheTopologyItself {

        @Test
        @DisplayName("gives every stream one module-wide subject filter")
        void givesEveryStreamOneModuleWideFilter() {
            // A stream named for a module and a filter that is not that module's whole space is
            // the same bug in a narrower form: the next subject the module publishes lands
            // nowhere, and nothing says so.
            for (StreamConfiguration stream : topology()) {
                assertThat(stream.getSubjects()).containsExactly(stream.getName() + ".>");
            }
        }

        @Test
        @DisplayName("names each stream once")
        void namesEachStreamOnce() {
            List<String> names = topology().stream().map(StreamConfiguration::getName).toList();

            assertThat(names).doesNotHaveDuplicates();
        }
    }

    private static List<String> streamCovering(String subject) {
        String module = subject.substring(0, subject.indexOf('.'));
        return topology().stream()
            .map(StreamConfiguration::getName)
            .filter(module::equals)
            .toList();
    }

    private static List<StreamConfiguration> topology() {
        return Streams.declared();
    }
}
