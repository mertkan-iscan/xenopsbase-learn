package com.xenopsoftware.learn.streaming.progress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The set of whole seconds a learner has actually been shown, as a value (T-3.7, ADR-0107).
 *
 * <h2>Why this is a value type and not one SQL statement</h2>
 *
 * The union itself could be one statement — {@code covered = covered + int4multirange(...)} — and
 * the column is an {@code int4multirange} precisely so that it can be. What cannot be written that
 * way is the rest of the rule: coalescing gaps of two seconds or less, capping the number of
 * fragments, and reporting how many seconds were <em>newly</em> covered so that the rate check has
 * something to check. Those need the before and the after in the same place.
 *
 * <p>So the merge happens here, in memory, over a set whose size is capped — and the result is
 * still written as a multirange literal, which Postgres normalises on the way in. The database
 * stays the thing that cannot be talked into storing an overlapping fragment; this class is the
 * thing that decides which fragments there are.
 *
 * <h2>The bound, which is the criterion this class exists to satisfy</h2>
 *
 * Work per heartbeat is proportional to the cap plus the batch, never to how long the learner has
 * been watching or how much they have already covered. A learner who scrubs a two-hour video all
 * afternoon costs the same per heartbeat as one who started a minute ago, which is what
 * "amortised" has to mean for a write that happens every ten seconds per learner.
 *
 * <p>Immutable, because a merge that mutated its input would make the "newly covered" answer
 * depend on the order the caller happened to read things in.
 */
public final class Coverage {

    /** One watched run: {@code [from, to)}, in whole seconds of the video's own timeline. */
    public record Fragment(int from, int to) {

        public Fragment {
            if (to <= from || from < 0) {
                throw new IllegalArgumentException("not an interval: [" + from + ", " + to + ")");
            }
        }

        int seconds() {
            return to - from;
        }
    }

    /**
     * What a merge produced, together with the two numbers only the merge knows.
     *
     * @param coverage     the new set
     * @param newlySeconds how much of it was not there before — what the rate check counts, and
     *                     deliberately not what the batch <em>claimed</em>: a duplicate batch
     *                     claims plenty and adds nothing, which is how re-delivery and an
     *                     over-eager retry both become free rather than suspicious
     * @param approximated whether the cap had to merge across a real gap this time, meaning the
     *                     set now credits seconds nobody was shown
     */
    public record Merge(Coverage coverage, int newlySeconds, boolean approximated) {}

    private static final Coverage EMPTY = new Coverage(List.of());

    private final List<Fragment> fragments;

    private Coverage(List<Fragment> fragments) {
        this.fragments = fragments;
    }

    public static Coverage empty() {
        return EMPTY;
    }

    /**
     * Read back what Postgres stored: <code>{[0,10),[12,30)}</code>, or <code>{}</code> for
     * nothing.
     *
     * <p>Parsed rather than mapped by a driver type, because the driver hands this over as an
     * opaque {@code PGobject} whose value is this string — and a small total parser of a format
     * the database itself normalises is less machinery than a custom Hibernate type for one column
     * in one table.
     */
    public static Coverage parse(String multirange) {
        if (multirange == null || multirange.isBlank() || multirange.equals("{}")) {
            return EMPTY;
        }
        List<Fragment> parsed = new ArrayList<>();
        // Postgres canonicalises every int4range to [inclusive, exclusive), so the brackets are
        // known and only the numbers vary. Anything else in this column was not written by us.
        for (String range : multirange.replace("{", "").replace("}", "").split("\\),")) {
            String body = range.replace("[", "").replace(")", "").trim();
            if (body.isEmpty()) {
                continue;
            }
            String[] bounds = body.split(",");
            parsed.add(new Fragment(Integer.parseInt(bounds[0].trim()),
                Integer.parseInt(bounds[1].trim())));
        }
        return new Coverage(List.copyOf(parsed));
    }

    /** The literal to store. The caller's SQL casts it to {@code int4multirange}. */
    public String toMultirange() {
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < fragments.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('[').append(fragments.get(index).from()).append(',')
                .append(fragments.get(index).to()).append(')');
        }
        return literal.append('}').toString();
    }

    /**
     * Add what a batch reported, and normalise the result.
     *
     * <p>Order-independent and duplicate-proof by construction: everything is sorted and unioned,
     * so the same claims arriving twice, backwards, or interleaved with another session's produce
     * the same set. That is not an optimisation — it is the idempotence criterion, met by the
     * shape of the operation rather than by a sequence number somebody has to remember to check.
     *
     * @param claims       the intervals reported, in any order, possibly overlapping each other
     * @param coalesceGap  gaps of this many seconds or fewer are closed. Two, by ADR-0107: it is
     *                     inside the sampling error of a ten-second heartbeat, and it is also the
     *                     largest inflation closing it can add — the rounding goes in the
     *                     learner's favour, by less than one heartbeat
     * @param maxFragments the cap. At it, the two fragments with the smallest gap between them are
     *                     merged until the count fits, and the result is flagged approximate
     */
    public Merge merge(List<Fragment> claims, int coalesceGap, int maxFragments) {
        if (maxFragments < 1) {
            throw new IllegalArgumentException("a coverage set holds at least one fragment");
        }
        List<Fragment> all = new ArrayList<>(fragments);
        all.addAll(claims);
        all.sort(Comparator.comparingInt(Fragment::from).thenComparingInt(Fragment::to));

        List<Fragment> merged = new ArrayList<>();
        for (Fragment fragment : all) {
            if (merged.isEmpty()) {
                merged.add(fragment);
                continue;
            }
            Fragment last = merged.getLast();
            if (fragment.from() - last.to() <= coalesceGap) {
                // Overlapping, touching, or separated by less than the sampling error: one run.
                merged.set(merged.size() - 1,
                    new Fragment(last.from(), Math.max(last.to(), fragment.to())));
            } else {
                merged.add(fragment);
            }
        }

        boolean approximated = false;
        while (merged.size() > maxFragments) {
            // The smallest gap is the least wrong thing to lose: it credits the fewest seconds
            // nobody watched. Linear per removal, over a list already at the cap, so the loop is
            // bounded by the cap rather than by how much the learner has scrubbed.
            int smallestAt = 1;
            int smallestGap = Integer.MAX_VALUE;
            for (int index = 1; index < merged.size(); index++) {
                int gap = merged.get(index).from() - merged.get(index - 1).to();
                if (gap < smallestGap) {
                    smallestGap = gap;
                    smallestAt = index;
                }
            }
            Fragment before = merged.get(smallestAt - 1);
            Fragment after = merged.remove(smallestAt);
            merged.set(smallestAt - 1,
                new Fragment(before.from(), Math.max(before.to(), after.to())));
            approximated = true;
        }

        Coverage result = new Coverage(List.copyOf(merged));
        return new Merge(result, result.seconds() - seconds(), approximated);
    }

    /** How many distinct seconds are covered. */
    public int seconds() {
        int total = 0;
        for (Fragment fragment : fragments) {
            total += fragment.seconds();
        }
        return total;
    }

    public int fragmentCount() {
        return fragments.size();
    }

    public List<Fragment> fragments() {
        return fragments;
    }

    /** The furthest second reached — where a resume goes, watched or skipped past. */
    public int furthestSecond() {
        return fragments.isEmpty() ? 0 : fragments.getLast().to();
    }

    /**
     * The end of the run that starts at the beginning, or zero when the beginning is unwatched.
     *
     * <p>This is the boundary an item that forbids seeking forward enforces: everything before it
     * has been presented and the first second after it has not. A learner who skipped the opening
     * minute has no such run and their ceiling is zero — which is the correct answer, however
     * unwelcome.
     */
    public int contiguousEnd() {
        if (fragments.isEmpty() || fragments.getFirst().from() > 0) {
            return 0;
        }
        return fragments.getFirst().to();
    }
}
