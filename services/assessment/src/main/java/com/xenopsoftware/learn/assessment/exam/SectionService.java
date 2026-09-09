package com.xenopsoftware.learn.assessment.exam;

import com.xenopsoftware.learn.assessment.scoring.ScoringMode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Building the instruction a test is assembled from (T-6.5).
 *
 * <h2>The pool is checked here, and that is the first of the two checks the issue asks for</h2>
 *
 * <p>"An insufficient pool fails at <b>authoring time</b> with a count, and again at attempt start
 * rather than serving a short form." This is the authoring half — {@link #poolSize} is what an
 * editing screen shows, and {@link #drawsFrom} refuses a section it cannot fill. The second half is
 * {@code FormAssembler}, and both call the same {@link QuestionPool}, so the number an author was
 * shown and the rows a learner is handed can never come from two different predicates.
 *
 * <p><b>Authoring-time is necessary and not sufficient</b>, which is why there are two. A pool that
 * was large enough in March is not large enough in June if somebody retired half the bank, and
 * nothing about editing the section would notice. Refusing at assembly is what stops that becoming
 * a short exam.
 */
@Service
@Transactional
public class SectionService {

    /**
     * The rational-midpoint ordinal scheme, which catalog's {@code Ordinals} owns and documents.
     *
     * <p>Copied rather than shared, because the two live in different services and a shared class
     * would be a dependency between them (ADR-0109). It is nine lines and one decision — placing a
     * section is a single-row write whatever the test's size — and the reasoning, including what it
     * costs, is in {@code catalog/.../V2__course_structure.sql}.
     */
    private static final MathContext PRECISION = new MathContext(40);
    private static final BigDecimal STEP = BigDecimal.valueOf(1000);

    private final TestSectionRepository sections;
    private final TestService tests;
    private final SectionMembers members;
    private final QuestionPool pool;

    public SectionService(TestSectionRepository sections, TestService tests,
            SectionMembers members, QuestionPool pool) {
        this.sections = sections;
        this.tests = tests;
        this.members = members;
        this.pool = pool;
    }

    @Transactional(readOnly = true)
    public List<TestSection> of(UUID testId) {
        return sections.findByTestIdOrderByOrdinalAscIdAsc(tests.get(testId).getId());
    }

    @Transactional(readOnly = true)
    public TestSection get(UUID id) {
        return sections.findById(id).orElseThrow(SectionNotFound::new);
    }

    /** A section whose questions the author names, added at the end. */
    public TestSection addFixed(UUID testId, String title) {
        return sections.save(TestSection.fixed(tests.get(testId).getId(), title, atTheEnd(testId)));
    }

    /** A section that draws, added at the end. Its filters are set with {@link #drawsFrom}. */
    public TestSection addPool(UUID testId, String title, int drawCount) {
        try {
            return sections.save(
                TestSection.pool(tests.get(testId).getId(), title, atTheEnd(testId), drawCount));
        } catch (IllegalArgumentException refused) {
            throw badRequest(refused);
        }
    }

    /**
     * Narrows a pool section, and refuses one that cannot be filled.
     *
     * <p>The count is in the message, because "not enough questions" without one leaves an author
     * guessing whether they are two short or two hundred.
     */
    public TestSection drawsFrom(UUID id, UUID bankId, Integer minRank, Integer maxRank,
            int drawCount, List<UUID> tagIds) {
        TestSection section = get(id);
        try {
            section.drawsFrom(bankId, minRank, maxRank, drawCount);
        } catch (IllegalArgumentException refused) {
            throw badRequest(refused);
        }
        members.tagsAre(id, tagIds);

        int available = pool.count(section, members.tagsOf(id));
        if (available < drawCount) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This section asks for " + drawCount + " questions and its pool has " + available
                + ". Serving a short test is the one outcome nobody is told about: the learner's "
                + "score comes out of a different total and the report does not say so. Widen the "
                + "filters, add questions, or draw fewer.");
        }
        return sections.save(section);
    }

    /** How many questions this section could draw from right now — what an editing screen shows. */
    @Transactional(readOnly = true)
    public int poolSize(UUID id) {
        TestSection section = get(id);
        if (section.getSelection() != TestSection.Selection.POOL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A fixed section holds the questions the author named; it has no pool.");
        }
        return pool.count(section, members.tagsOf(id));
    }

    /** Replaces a fixed section's questions, in the author's order. */
    public TestSection questionsAre(UUID id, List<UUID> questionIds) {
        TestSection section = get(id);
        if (section.getSelection() != TestSection.Selection.FIXED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A pool section draws its questions; it does not hold a list.");
        }
        if (questionIds == null || questionIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A section with no questions serves an empty form.");
        }
        members.questionsAre(id, questionIds);
        return section;
    }

    public TestSection rename(UUID id, String title) {
        TestSection section = get(id);
        section.rename(title);
        return sections.save(section);
    }

    public TestSection countsFor(UUID id, int weight) {
        TestSection section = get(id);
        try {
            section.countsFor(weight);
        } catch (IllegalArgumentException refused) {
            throw badRequest(refused);
        }
        return sections.save(section);
    }

    public TestSection scoredAs(UUID id, BigDecimal points, ScoringMode mode) {
        TestSection section = get(id);
        try {
            section.scoredAs(points, mode);
        } catch (IllegalArgumentException refused) {
            throw badRequest(refused);
        }
        return sections.save(section);
    }

    public TestSection shuffles(UUID id, boolean questions, boolean options) {
        TestSection section = get(id);
        section.shuffles(questions, options);
        return sections.save(section);
    }

    /**
     * Moves a section. One row changes, whatever the test's size.
     *
     * @param afterSectionId the section this one now follows, or null to move it to the front
     */
    public TestSection moveAfter(UUID id, UUID afterSectionId) {
        TestSection section = get(id);
        if (id.equals(afterSectionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A section cannot be placed after itself");
        }
        List<TestSection> siblings = of(section.getTestId());
        section.moveTo(placeAfter(siblings, afterSectionId));
        return sections.save(section);
    }

    /**
     * Removes a section.
     *
     * <p>Refused once anybody has been served from it: {@code test_form_item.section_id} is
     * {@code ON DELETE NO ACTION} deliberately, because deleting a section must not delete the
     * record of what somebody was asked (T-6.5's last criterion, from the other direction).
     */
    public void remove(UUID id) {
        TestSection section = get(id);
        try {
            sections.delete(section);
            sections.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException served) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Somebody has already sat a test containing this section, and their form points at "
                + "it. Removing it would delete the record of what they were asked.", served);
        }
    }

    // ---------------------------------------------------------------- ordinals

    private BigDecimal atTheEnd(UUID testId) {
        List<TestSection> siblings = of(testId);
        return siblings.isEmpty() ? STEP
            : siblings.getLast().getOrdinal().add(STEP);
    }

    private static BigDecimal placeAfter(List<TestSection> siblings, UUID afterId) {
        if (afterId == null) {
            BigDecimal first = siblings.isEmpty() ? null : siblings.getFirst().getOrdinal();
            return first == null ? STEP : first.divide(BigDecimal.TWO, PRECISION);
        }
        for (int index = 0; index < siblings.size(); index++) {
            if (siblings.get(index).getId().equals(afterId)) {
                BigDecimal before = siblings.get(index).getOrdinal();
                if (index + 1 == siblings.size()) {
                    return before.add(STEP);
                }
                return before.add(siblings.get(index + 1).getOrdinal())
                    .divide(BigDecimal.TWO, PRECISION);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "There is no section " + afterId + " in this test to place it after");
    }

    private static ResponseStatusException badRequest(IllegalArgumentException refused) {
        // The entity speaks to an author; this turns that into a status code without rewording it.
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage());
    }
}
