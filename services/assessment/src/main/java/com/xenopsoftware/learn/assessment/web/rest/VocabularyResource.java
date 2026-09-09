package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.vocabulary.BankDifficulty;
import com.xenopsoftware.learn.assessment.vocabulary.BankTag;
import com.xenopsoftware.learn.assessment.vocabulary.VocabularyService;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vocabularies a company describes its questions with (T-6.1).
 *
 * <p>Two of them, and they are not the same kind of thing. Tags are an unordered set; difficulty
 * is an ordered scale, because a draw says "medium or harder" (T-6.5) and a report asks whether a
 * question was harder than the one it replaced (T-7.7). One endpoint returning both would have to
 * carry a rank that is meaningless for half its rows.
 *
 * <p><b>Append-only, deliberately.</b> There is no DELETE here until something can answer whether
 * a question still uses a term, which arrives with T-6.2 — see {@link VocabularyService}.
 */
@RestController
@RequestMapping("/api/v1/vocabulary")
public class VocabularyResource {

    private final VocabularyService vocabulary;

    public VocabularyResource(VocabularyService vocabulary) {
        this.vocabulary = vocabulary;
    }

    public record TagView(String tag) {}

    public record DifficultyView(String code, short rank) {}

    public record TagForm(String tag) {}

    public record DifficultyForm(String code, int rank) {}

    @GetMapping("/tags")
    public List<TagView> tags() {
        return vocabulary.tags().stream().map(tag -> new TagView(tag.getTag())).toList();
    }

    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The tag that was added")
    @ApiResponse(responseCode = "409", description = "This company already has that tag",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public TagView addTag(@RequestBody TagForm form) {
        BankTag added = vocabulary.addTag(form.tag());
        return new TagView(added.getTag());
    }

    /** In rank order, hardest last — which is the only order this list is useful in. */
    @GetMapping("/difficulties")
    public List<DifficultyView> difficulties() {
        return vocabulary.difficulties().stream()
            .map(level -> new DifficultyView(level.getCode(), level.getRank()))
            .toList();
    }

    @PostMapping("/difficulties")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The difficulty level that was added")
    @ApiResponse(responseCode = "409",
        description = "This company already has that code, or already has a level at that rank",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public DifficultyView addDifficulty(@RequestBody DifficultyForm form) {
        BankDifficulty added = vocabulary.addDifficulty(form.code(), form.rank());
        return new DifficultyView(added.getCode(), added.getRank());
    }
}
