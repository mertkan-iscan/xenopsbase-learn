package com.xenopsoftware.learn.streaming.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.streaming.progress.Coverage.Fragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * THE NUMBER ADR-0107 IS OWED (T-3.7): how many fragments real watching produces, and therefore
 * what the cap should be.
 *
 * <p>ADR-0107 marks the fragment cap and the fragment distribution as <b>unmeasured</b>, because
 * there are no learners yet. This is the honest substitute and it says so in its name: a
 * simulation, seeded so it is the same every run, of behaviours chosen to bracket what a learner
 * can do to a thirty-minute video. It is not evidence about people. It is evidence about the
 * structure — that the set stays small for anything resembling watching, and that the cap is far
 * enough above ordinary behaviour to never touch it.
 *
 * <p>The figures it prints are the ones written into {@code docs/slos.md}. When there are real
 * learners, {@code progress.coverage.fragments} (already published as a percentile summary)
 * replaces this, and the cap should be revisited against it rather than against this.
 */
class FragmentDistributionTest {

    private static final int VIDEO_SECONDS = 1800;
    private static final int HEARTBEAT_SECONDS = 10;
    private static final int GAP = 2;
    private static final int CAP = 64;
    private static final int LEARNERS = 1_000;

    @Test
    void ordinaryWatchingStaysFarBelowTheCap() {
        Random random = new Random(20260907L);
        List<Integer> fragments = new ArrayList<>();

        for (int learner = 0; learner < LEARNERS; learner++) {
            int roll = random.nextInt(100);
            int seeks = roll < 70 ? 0            // watched it through, which is what most people do
                : roll < 90 ? 3 + random.nextInt(6)   // a few rewinds: the bit they missed
                : 20 + random.nextInt(41);            // a deliberate scrubber, hunting for one part
            fragments.add(watch(random, seeks).fragmentCount());
        }
        fragments.sort(Integer::compareTo);

        int median = fragments.get(LEARNERS / 2);
        int p95 = fragments.get((int) (LEARNERS * 0.95));
        int p99 = fragments.get((int) (LEARNERS * 0.99));
        int worst = fragments.getLast();
        System.out.printf("fragments over %d simulated viewings of a %ds video: "
            + "p50=%d p95=%d p99=%d max=%d (cap %d)%n",
            LEARNERS, VIDEO_SECONDS, median, p95, p99, worst, CAP);

        assertThat(median)
            .as("most people watch a video, and watching a video is one run")
            .isEqualTo(1);
        assertThat(worst)
            .as("even the deliberate scrubbers stay well inside the cap, which is what makes the "
                + "cap a bound rather than a routine approximation")
            .isLessThan(CAP);
    }

    /**
     * The pathological case, which is the one the cap exists for: somebody dragging the scrubber
     * across a four-hour recording for an afternoon, landing somewhere new every time. The set
     * stays at the cap, the record says it is approximate, and the work per heartbeat stays the
     * size it was on the first one.
     *
     * <p>Four hours and three-second landings rather than the thirty-minute video above, because
     * a scrubber on a short video simply ends up having seen all of it — which is a completion,
     * not a pathology. The structure only strains when there is far more video than the scrubbing
     * covers.
     */
    @Test
    void aPathologicalScrubberIsBoundedAndSaysSo() {
        int recordingSeconds = 4 * 60 * 60;
        Coverage coverage = Coverage.empty();
        Random random = new Random(20260907L);
        boolean everApproximated = false;

        long startedAt = System.nanoTime();
        for (int seek = 0; seek < 2_000; seek++) {
            int at = random.nextInt(recordingSeconds - 3);
            Coverage.Merge merge = coverage.merge(List.of(new Fragment(at, at + 3)), GAP, CAP);
            coverage = merge.coverage();
            everApproximated |= merge.approximated();
            assertThat(coverage.fragmentCount()).isLessThanOrEqualTo(CAP);
        }
        long micros = (System.nanoTime() - startedAt) / 1_000 / 2_000;
        System.out.printf("2000 merges against a capped set: %dus each, ending at %d fragments%n",
            micros, coverage.fragmentCount());

        assertThat(everApproximated)
            .as("the record admits the approximation rather than quietly inflating coverage")
            .isTrue();
        assertThat(micros)
            .as("a heartbeat's merge is bounded work, not work proportional to a watching history")
            .isLessThan(1_000);
    }

    /** One viewing: continuous playback in ten-second heartbeats, interrupted by seeks. */
    private static Coverage watch(Random random, int seeks) {
        List<Integer> seekPoints = new ArrayList<>();
        for (int index = 0; index < seeks; index++) {
            seekPoints.add(random.nextInt(VIDEO_SECONDS));
        }
        seekPoints.sort(Integer::compareTo);

        Coverage coverage = Coverage.empty();
        int at = 0;
        int nextSeek = 0;
        // A run of continuous playback per segment between seeks, which is what the player
        // actually reports: a seek covers nothing, and everything either side of it is one run.
        for (int watched = 0; watched < VIDEO_SECONDS; watched += HEARTBEAT_SECONDS) {
            coverage = coverage.merge(
                List.of(new Fragment(at, at + HEARTBEAT_SECONDS)), GAP, CAP).coverage();
            at += HEARTBEAT_SECONDS;
            if (nextSeek < seekPoints.size() && watched >= seekPoints.get(nextSeek)) {
                at = seekPoints.get(nextSeek);
                nextSeek++;
            }
            if (at + HEARTBEAT_SECONDS >= VIDEO_SECONDS) {
                at = 0;
            }
        }
        return coverage;
    }
}
