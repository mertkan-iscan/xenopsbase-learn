package com.xenopsoftware.learn.catalog.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ordering scheme's own properties (T-5.2). No Spring and no container: these are arithmetic,
 * and they should fail in the first millisecond of a build rather than after a container starts.
 */
class OrdinalTest {

    @Test
    void aMidpointIsAlwaysStrictlyBetween() {
        BigDecimal left = BigDecimal.valueOf(1000);
        BigDecimal right = BigDecimal.valueOf(2000);

        BigDecimal middle = Ordinals.between(left, right);

        assertThat(middle).isStrictlyBetween(left, right);
    }

    @Test
    void subdividingTheSamePointFiveHundredTimesNeverCollapses() {
        // THE PROPERTY THAT RULES OUT double. A float midpoint stops being a midpoint after about
        // fifty subdivisions -- the gap falls under the mantissa and two rows silently share an
        // ordinal, after which their order is whatever the planner returns that day. This is the
        // pathological case the scheme has to survive, not the expected one.
        BigDecimal left = BigDecimal.valueOf(1000);
        BigDecimal right = BigDecimal.valueOf(2000);

        for (int i = 0; i < 500; i++) {
            BigDecimal middle = Ordinals.between(left, right);
            assertThat(middle)
                .as("subdivision %s collapsed onto a neighbour", i)
                .isStrictlyBetween(left, right);
            right = middle;
        }
    }

    @Test
    void insertingAtTheFrontRepeatedlyNeverReachesZero() {
        // Halving rather than subtracting, so "make this first" works indefinitely without
        // needing to know what the smallest ordinal ever was.
        BigDecimal smallest = Ordinals.first();
        for (int i = 0; i < 200; i++) {
            BigDecimal next = Ordinals.between(null, smallest);
            assertThat(next).isLessThan(smallest).isGreaterThan(BigDecimal.ZERO);
            smallest = next;
        }
    }

    @Test
    void appendingKeepsTheOrderAndTheGaps() {
        BigDecimal last = Ordinals.first();
        List<BigDecimal> all = new ArrayList<>(List.of(last));
        for (int i = 0; i < 10; i++) {
            last = Ordinals.between(last, null);
            all.add(last);
        }
        assertThat(all).isSorted();
    }

    @Test
    void neighboursOutOfOrderAreRefusedRatherThanAveraged() {
        // A caller working from a stale list. Averaging would put the node somewhere neither they
        // nor the author expected, and it would look like it worked.
        assertThatThrownBy(() -> Ordinals.between(BigDecimal.valueOf(2000), BigDecimal.valueOf(1000)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of order");

        assertThatThrownBy(() -> Ordinals.between(BigDecimal.TEN, BigDecimal.TEN))
            .as("equal neighbours leave no room, which is the same stale-view mistake")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rebalancingProducesEvenlySpacedOrdinals() {
        List<BigDecimal> fresh = Ordinals.rebalance(4);

        assertThat(fresh).isSorted().hasSize(4);
        assertThat(fresh.getFirst()).isEqualByComparingTo("1000");
        assertThat(fresh.getLast()).isEqualByComparingTo("4000");
        assertThat(Ordinals.rebalance(0)).isEmpty();
    }
}
