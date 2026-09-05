package com.xenopsoftware.learn.streaming.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.streaming.progress.Coverage.Fragment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The merge rule on its own, with no database and no clock (T-3.7, ADR-0107).
 *
 * <p>Separate from {@code ProgressTest} because these are the properties the whole design rests
 * on — idempotence, order-independence and the size bound — and a property is worth stating where
 * it can be read in one screen rather than inferred from an HTTP status.
 */
class CoverageTest {

    private static final int GAP = 2;
    private static final int CAP = 64;

    @Test
    void watchingStraightThroughIsOneFragmentHoweverManyHeartbeatsItTook() {
        Coverage coverage = Coverage.empty();
        for (int second = 0; second < 600; second += 10) {
            coverage = coverage.merge(List.of(new Fragment(second, second + 10)), GAP, CAP)
                .coverage();
        }
        assertThat(coverage.fragmentCount())
            .as("sixty heartbeats of continuous playback are one run, not sixty")
            .isEqualTo(1);
        assertThat(coverage.seconds()).isEqualTo(600);
        assertThat(coverage.toMultirange()).isEqualTo("{[0,600)}");
    }

    @Test
    void theSameBatchTwiceCreditsNothingTheSecondTime() {
        List<Fragment> batch = List.of(new Fragment(0, 10), new Fragment(10, 20));
        Coverage once = Coverage.empty().merge(batch, GAP, CAP).coverage();
        Coverage.Merge again = once.merge(batch, GAP, CAP);

        assertThat(again.newlySeconds())
            .as("a redelivered or retried batch is free, which is what lets the rate check "
                + "count credited seconds rather than claimed ones")
            .isZero();
        assertThat(again.coverage().toMultirange()).isEqualTo(once.toMultirange());
    }

    @Test
    void orderDoesNotMatter() {
        List<Fragment> claims = new ArrayList<>(List.of(new Fragment(30, 40),
            new Fragment(0, 10), new Fragment(10, 20), new Fragment(60, 70)));
        Coverage forwards = Coverage.empty().merge(claims, GAP, CAP).coverage();
        Collections.reverse(claims);
        Coverage backwards = Coverage.empty().merge(claims, GAP, CAP).coverage();

        assertThat(backwards.toMultirange()).isEqualTo(forwards.toMultirange());
        // The two touching claims join; the ten-second gaps are seeks and stay gaps.
        assertThat(forwards.toMultirange()).isEqualTo("{[0,20),[30,40),[60,70)}");
    }

    @Test
    void overlappingClaimsAreCountedOnce() {
        Coverage coverage = Coverage.empty()
            .merge(List.of(new Fragment(0, 30), new Fragment(20, 50)), GAP, CAP).coverage();

        assertThat(coverage.seconds())
            .as("fifty seconds of video, not the eighty the two claims add up to")
            .isEqualTo(50);
        assertThat(coverage.fragmentCount()).isEqualTo(1);
    }

    @Test
    void aGapInsideTheSamplingErrorIsClosedAndABiggerOneIsNot() {
        Coverage closed = Coverage.empty()
            .merge(List.of(new Fragment(0, 10), new Fragment(12, 20)), GAP, CAP).coverage();
        assertThat(closed.fragmentCount()).isEqualTo(1);
        assertThat(closed.seconds())
            .as("the two coalesced seconds are credited -- ADR-0107 rounds in the learner's "
                + "favour, by less than one heartbeat")
            .isEqualTo(20);

        Coverage kept = Coverage.empty()
            .merge(List.of(new Fragment(0, 10), new Fragment(13, 20)), GAP, CAP).coverage();
        assertThat(kept.fragmentCount())
            .as("three seconds is a seek, and a seek covers nothing")
            .isEqualTo(2);
        assertThat(kept.seconds()).isEqualTo(17);
    }

    @Test
    void theSetIsBoundedAndSaysWhenItHadToApproximate() {
        List<Fragment> scattered = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            // Ten seconds watched, ten skipped, repeatedly: the deliberate scrubber.
            scattered.add(new Fragment(index * 20, index * 20 + 10));
        }
        Coverage.Merge merge = Coverage.empty().merge(scattered, GAP, 5);

        assertThat(merge.coverage().fragmentCount())
            .as("the cap holds however much somebody scrubs")
            .isEqualTo(5);
        assertThat(merge.approximated())
            .as("and the record says it is now approximate rather than pretending otherwise")
            .isTrue();
        assertThat(merge.coverage().seconds())
            .as("coverage only ever grows when gaps are closed, never shrinks")
            .isGreaterThan(200);
        assertThat(merge.coverage().furthestSecond()).isEqualTo(390);
    }

    @Test
    void underTheCapNothingIsApproximated() {
        List<Fragment> scattered = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            scattered.add(new Fragment(index * 20, index * 20 + 10));
        }
        Coverage.Merge merge = Coverage.empty().merge(scattered, GAP, CAP);

        assertThat(merge.coverage().fragmentCount()).isEqualTo(20);
        assertThat(merge.approximated()).isFalse();
        assertThat(merge.coverage().seconds())
            .as("exactly what was watched, and nothing anybody skipped")
            .isEqualTo(200);
    }

    @Test
    void theContiguousRunIsTheOneThatStartsAtTheBeginning() {
        Coverage fromTheStart = Coverage.empty()
            .merge(List.of(new Fragment(0, 100), new Fragment(200, 300)), GAP, CAP).coverage();
        assertThat(fromTheStart.contiguousEnd()).isEqualTo(100);
        assertThat(fromTheStart.furthestSecond())
            .as("resume goes to where they stopped, which is past what they watched")
            .isEqualTo(300);

        Coverage startedLate = Coverage.empty()
            .merge(List.of(new Fragment(60, 120)), GAP, CAP).coverage();
        assertThat(startedLate.contiguousEnd())
            .as("a learner who skipped the opening minute has watched nothing from the start")
            .isZero();
    }

    @Test
    void whatPostgresStoresIsWhatComesBack() {
        assertThat(Coverage.parse("{}").fragmentCount()).isZero();
        assertThat(Coverage.parse(null).toMultirange()).isEqualTo("{}");

        Coverage parsed = Coverage.parse("{[0,10),[12,30),[100,110)}");
        assertThat(parsed.fragmentCount()).isEqualTo(3);
        assertThat(parsed.seconds()).isEqualTo(38);
        assertThat(parsed.toMultirange()).isEqualTo("{[0,10),[12,30),[100,110)}");
    }
}
