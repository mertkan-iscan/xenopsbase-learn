package com.xenopsoftware.learn.assessment.exam;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The population a pool section draws from, counted and drawn (T-6.5).
 *
 * <h2>Counting and drawing are the same query</h2>
 *
 * <p>Deliberately, and it is the criterion about short forms. "An insufficient pool fails at
 * authoring time with a count, and again at attempt start rather than serving a short form" is only
 * true if the number an author was shown and the rows a learner is handed come from one predicate.
 * Two queries that were meant to agree are two queries that eventually do not, and the day they
 * disagree is the day somebody sits a shorter exam and is scored out of a different total.
 *
 * <p>So there is one {@code WHERE} clause, built once in {@link #where}, used by {@link #count} and
 * by {@link #draw}.
 *
 * <h2>What is excluded, and why each one is not optional</h2>
 *
 * <ul>
 *   <li><b>Retired questions.</b> T-6.2 made retiring the "delete" an author sees for anything ever
 *       served: it stays for existing attempts and leaves every future draw.
 *   <li><b>Questions with no current version.</b> A question exists for the width of one
 *       transaction before its first version does. Drawing one would put a null version id in a
 *       form, which is the record that has to be readable in three months.
 *   <li><b>Tags this company did not define.</b> Not excluded here — they cannot exist, because
 *       {@code question_tag} has a foreign key into the vocabulary (T-6.1's whole purpose).
 * </ul>
 *
 * <h2>ORDER BY random(), and the size it is right for</h2>
 *
 * <p>Postgres sorts the matching set and takes the first n. On a pool of a few thousand questions —
 * which is a large bank — that is a sort of a few thousand narrow rows per attempt start, and it is
 * simple, uniform and correct. It stops being right at a scale where a bank has hundreds of
 * thousands of questions, and the replacement then is a keyset sample rather than a cleverer sort.
 * Recording the boundary is the point: the naive version is chosen, not stumbled into.
 */
@Component
public class QuestionPool {

    private final JdbcTemplate jdbc;

    public QuestionPool(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** How many questions this section could draw from right now. */
    public int count(TestSection section, List<UUID> tagIds) {
        List<Object> arguments = new ArrayList<>();
        String where = where(section, tagIds, arguments);
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM question q " + where, Integer.class, arguments.toArray());
        return count == null ? 0 : count;
    }

    /**
     * {@code drawCount} of them, at random, as question ids.
     *
     * <p>Returns fewer than asked for only when the pool is smaller than the draw, and the caller
     * refuses that rather than serving it — see {@link FormAssembler}. Silently serving a short
     * test is the worst outcome this issue names: the learner's score is out of a different total
     * and nobody is told.
     */
    public List<UUID> draw(TestSection section, List<UUID> tagIds) {
        List<Object> arguments = new ArrayList<>();
        String where = where(section, tagIds, arguments);
        arguments.add(section.getDrawCount());
        return jdbc.queryForList(
            "SELECT q.id FROM question q " + where + " ORDER BY random() LIMIT ?",
            UUID.class, arguments.toArray());
    }

    /**
     * The one predicate, built once.
     *
     * <p>Native SQL rather than a repository method, so that counting and drawing cannot drift —
     * and because the tag filter is "carries all of these", which is a correlated count rather than
     * anything a derived query name can express.
     */
    private String where(TestSection section, List<UUID> tagIds, List<Object> arguments) {
        StringBuilder sql = new StringBuilder("""
             WHERE q.tenant_id = ?
               AND q.retired_at IS NULL
               AND q.current_version_id IS NOT NULL
            """);
        arguments.add(TenantContext.require());

        if (section.getBankId() != null) {
            sql.append(" AND q.bank_id = ?\n");
            arguments.add(section.getBankId());
        }
        if (section.getMinDifficultyRank() != null || section.getMaxDifficultyRank() != null) {
            // A question with no difficulty is excluded the moment a section asks about it, which
            // is the correct answer and an unwelcome one: "medium or harder" cannot include a
            // question nobody graded. The count an author is shown says so before they publish.
            sql.append(" AND EXISTS (SELECT 1 FROM bank_difficulty d"
                + " WHERE d.id = q.difficulty_id AND d.tenant_id = q.tenant_id");
            if (section.getMinDifficultyRank() != null) {
                sql.append(" AND d.rank >= ?");
                arguments.add(section.getMinDifficultyRank());
            }
            if (section.getMaxDifficultyRank() != null) {
                sql.append(" AND d.rank <= ?");
                arguments.add(section.getMaxDifficultyRank());
            }
            sql.append(")\n");
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            // ALL of them, not any: two tags means questions that are both. That can only ever
            // make a pool smaller, and a pool that is too small is refused loudly -- where "any of
            // these" widens silently and is expressible as two sections anyway.
            sql.append(" AND (SELECT count(*) FROM question_tag t"
                + " WHERE t.question_id = q.id AND t.tag_id IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(tagIds.size(), "?")));
            sql.append(")) = ?\n");
            arguments.addAll(tagIds);
            arguments.add(tagIds.size());
        }
        return sql.toString();
    }
}
