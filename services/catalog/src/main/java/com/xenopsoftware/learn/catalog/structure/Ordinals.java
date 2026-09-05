package com.xenopsoftware.learn.catalog.structure;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Where a thing goes when it is placed between two others (T-5.2).
 *
 * <p>The whole ordering scheme is one idea: an ordinal is a rational number, and the position
 * between two neighbours is their average. That makes an insert or a move a <b>single-row
 * write</b> at any module size, which is the property the dense-integer alternative cannot have —
 * inserting at position 3 of a 40-node module rewrites 37 rows, and two authors reordering
 * different parts of the same module overlap and one loses work that was never in conflict.
 *
 * <p>{@link BigDecimal}, never {@code double}. A float midpoint stops being a midpoint after about
 * fifty subdivisions at one point, silently: the gap falls below the mantissa, two nodes get the
 * same ordinal, and their order becomes whatever the planner returns that day.
 */
public final class Ordinals {

    /** The gap between consecutive items appended to an empty or growing list. */
    private static final BigDecimal STEP = BigDecimal.valueOf(1000);

    /**
     * Enough precision that a midpoint is always strictly between its neighbours, and a bound so
     * a pathological caller cannot grow one number without limit. 200 significant digits is
     * roughly 660 insertions at the same point before it saturates, which is far past the point
     * where {@link #rebalance} is the right answer anyway.
     */
    private static final MathContext PRECISION = new MathContext(200);

    private Ordinals() {}

    /** The first ordinal in an empty list. */
    public static BigDecimal first() {
        return STEP;
    }

    /**
     * A position between two neighbours, either of which may be absent.
     *
     * @param before the ordinal of the item this goes after, or null to place it first
     * @param after  the ordinal of the item this goes before, or null to place it last
     */
    public static BigDecimal between(BigDecimal before, BigDecimal after) {
        if (before == null && after == null) {
            return first();
        }
        if (before == null) {
            // First. Halving rather than subtracting a fixed step, so this can be done
            // indefinitely without ever reaching zero or needing to know what came before.
            return after.divide(BigDecimal.TWO, PRECISION);
        }
        if (after == null) {
            return before.add(STEP);
        }
        if (before.compareTo(after) >= 0) {
            // The caller named neighbours that are not in order, which means they are working
            // from a stale view of the list. Refusing is the honest answer: computing something
            // from it would put the node somewhere neither the caller nor the author expected.
            throw new IllegalArgumentException(
                "Cannot place between " + before + " and " + after + ": they are out of order, "
                + "which usually means the list moved since it was read");
        }
        return before.add(after).divide(BigDecimal.TWO, PRECISION);
    }

    /**
     * Fresh ordinals for a whole list, 1000 apart — the escape hatch for a module whose ordinals
     * have grown long after many insertions at one point.
     *
     * <p>A deliberate operation and never automatic. Renumbering rewrites every row in the module,
     * which is exactly the cost this scheme exists to avoid paying on an ordinary edit; doing it
     * silently under a user would reintroduce the problem at the least predictable moment.
     */
    public static List<BigDecimal> rebalance(int size) {
        return java.util.stream.IntStream.rangeClosed(1, size)
            .mapToObj(position -> STEP.multiply(BigDecimal.valueOf(position)))
            .toList();
    }
}
