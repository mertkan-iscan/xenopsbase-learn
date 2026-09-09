package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.exam.SectionMembers;
import com.xenopsoftware.learn.assessment.exam.SectionService;
import com.xenopsoftware.learn.assessment.exam.TestSection;
import com.xenopsoftware.learn.assessment.scoring.ScoringMode;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sections a test is assembled from (T-6.5).
 *
 * <h2>The pool size is a read of its own, and an editing screen should use it</h2>
 *
 * <p>{@code GET /sections/{id}/pool} answers how many questions the section could draw from right
 * now. It is the same predicate the draw uses, so the number an author sees is the number a learner
 * will get — and it is a separate read because an author narrowing a filter wants to watch the
 * count fall before they save, not to be refused after.
 *
 * <p>Saving a pool that cannot be filled is refused with a 409 that carries both numbers. That is
 * the authoring half of this task's central rule; the other half runs at attempt start, because a
 * pool that was large enough in March is not large enough in June if somebody retired half the
 * bank.
 *
 * <p>The permission story is {@code BankResource}'s, unchanged (T-9.11, ADR-0109).
 */
@RestController
@RequestMapping("/api/v1")
public class SectionResource {

    private final SectionService sections;
    private final SectionMembers members;

    public SectionResource(SectionService sections, SectionMembers members) {
        this.sections = sections;
        this.members = members;
    }

    /**
     * @param drawCount required for a pool section, absent for a fixed one
     */
    public record NewSectionForm(String title, String selection, Integer drawCount) {}

    /**
     * The population a pool section draws from. Every filter is optional and every one narrows.
     *
     * @param minDifficultyRank the easiest level allowed, by rank — "medium or harder" is a range
     *                          and not a set of levels, so a company adding a level in the middle
     *                          of its scale does not silently narrow this
     * @param tagIds            a drawn question must carry <b>all</b> of them
     */
    public record PoolForm(UUID bankId, Integer minDifficultyRank, Integer maxDifficultyRank,
                           Integer drawCount, List<UUID> tagIds) {}

    public record QuestionsForm(List<UUID> questionIds) {}

    public record WeightForm(Integer weight) {}

    public record ScoringForm(BigDecimal points, String mode) {}

    public record ShuffleForm(Boolean questions, Boolean options) {}

    public record MoveForm(UUID afterSectionId) {}

    public record SectionView(UUID id, UUID testId, String title, int weight, String selection,
                              Integer drawCount, UUID bankId, Integer minDifficultyRank,
                              Integer maxDifficultyRank, List<UUID> tagIds, List<UUID> questionIds,
                              BigDecimal points, String mode, boolean shuffleQuestions,
                              boolean shuffleOptions) {}

    public record PoolView(int available, int wanted, boolean enough) {}

    @GetMapping("/tests/{testId}/sections")
    public List<SectionView> of(@PathVariable UUID testId) {
        return sections.of(testId).stream().map(this::view).toList();
    }

    @PostMapping("/tests/{testId}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Created, at the end of the test.")
    @ApiResponse(responseCode = "400",
        description = "An unknown selection, or a pool section with no draw count.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public SectionView add(@PathVariable UUID testId, @RequestBody NewSectionForm form) {
        TestSection.Selection selection = selection(form.selection());
        if (selection == TestSection.Selection.FIXED) {
            return view(sections.addFixed(testId, form.title()));
        }
        if (form.drawCount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A pool section says how many questions to draw. A section that draws nothing "
                + "serves an empty form, which is the one outcome nobody is told about.");
        }
        return view(sections.addPool(testId, form.title(), form.drawCount()));
    }

    @GetMapping("/sections/{id}/pool")
    @ApiResponse(responseCode = "200",
        description = "How many questions this section could draw from right now, against what it "
            + "asks for. The same predicate the draw uses.")
    @ApiResponse(responseCode = "400", description = "A fixed section has no pool.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public PoolView pool(@PathVariable UUID id) {
        TestSection section = sections.get(id);
        int available = sections.poolSize(id);
        int wanted = section.getDrawCount() == null ? 0 : section.getDrawCount();
        return new PoolView(available, wanted, available >= wanted);
    }

    @PutMapping("/sections/{id}/pool")
    @ApiResponse(responseCode = "200", description = "The section as it now stands.")
    @ApiResponse(responseCode = "409",
        description = "The pool holds fewer questions than the section asks for, with both "
            + "numbers. Serving a short test would score this learner out of a different total "
            + "from everybody else and nothing in the result would say so.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public SectionView pool(@PathVariable UUID id, @RequestBody PoolForm form) {
        if (form.drawCount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A pool section says how many questions to draw.");
        }
        return view(sections.drawsFrom(id, form.bankId(), form.minDifficultyRank(),
            form.maxDifficultyRank(), form.drawCount(),
            form.tagIds() == null ? List.of() : form.tagIds()));
    }

    @PutMapping("/sections/{id}/questions")
    @ApiResponse(responseCode = "200", description = "The section as it now stands.")
    @ApiResponse(responseCode = "400",
        description = "A pool section draws its questions, or the list was empty, or one of the "
            + "questions is not this company's.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public SectionView questions(@PathVariable UUID id, @RequestBody QuestionsForm form) {
        return view(sections.questionsAre(id, form.questionIds()));
    }

    @PutMapping("/sections/{id}/weight")
    public SectionView weight(@PathVariable UUID id, @RequestBody WeightForm form) {
        return view(sections.countsFor(id, form.weight() == null ? 1 : form.weight()));
    }

    /** Two nulls clear the override, which is not the same as copying what the test says today. */
    @PutMapping("/sections/{id}/scoring")
    public SectionView scoring(@PathVariable UUID id, @RequestBody ScoringForm form) {
        return view(sections.scoredAs(id, form.points(), mode(form.mode())));
    }

    @PutMapping("/sections/{id}/shuffle")
    public SectionView shuffle(@PathVariable UUID id, @RequestBody ShuffleForm form) {
        return view(sections.shuffles(id, form.questions() != null && form.questions(),
            form.options() != null && form.options()));
    }

    @PutMapping("/sections/{id}/position")
    public SectionView move(@PathVariable UUID id, @RequestBody MoveForm form) {
        return view(sections.moveAfter(id, form.afterSectionId()));
    }

    @DeleteMapping("/sections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Removed.")
    @ApiResponse(responseCode = "409",
        description = "Somebody has already sat a test containing this section, and their form "
            + "points at it.",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public void remove(@PathVariable UUID id) {
        sections.remove(id);
    }

    private SectionView view(TestSection section) {
        boolean pool = section.getSelection() == TestSection.Selection.POOL;
        return new SectionView(section.getId(), section.getTestId(), section.getTitle(),
            section.getWeight(), section.getSelection().name(), section.getDrawCount(),
            section.getBankId(), section.getMinDifficultyRank(), section.getMaxDifficultyRank(),
            pool ? members.tagsOf(section.getId()) : List.of(),
            pool ? List.of() : members.questionsOf(section.getId()),
            section.getPoints(), section.getMode() == null ? null : section.getMode().name(),
            section.isShuffleQuestions(), section.isShuffleOptions());
    }

    private static TestSection.Selection selection(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A section is FIXED (the author names the questions) or POOL (it draws them).");
        }
        try {
            return TestSection.Selection.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No selection '" + name + "'. There are two: FIXED and POOL.", unknown);
        }
    }

    private static ScoringMode mode(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ScoringMode.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No scoring mode '" + name + "'. There are two: ALL_OR_NOTHING and "
                + "PARTIAL_CREDIT.", unknown);
        }
    }
}
