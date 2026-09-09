package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.question.Question;
import com.xenopsoftware.learn.assessment.question.QuestionBodies;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.assessment.question.QuestionVersion;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;

/**
 * Authoring questions and reading their history (T-6.2).
 *
 * <h2>Two paths, because a question is two things</h2>
 *
 * <p>{@code /banks/{bankId}/questions} is the authoring list: a question is created in a bank and
 * only ever found through one, which is what T-6.1 built the bank to be. Everything after that is
 * {@code /questions/{id}}, because a question outlives its membership — it can be moved, and a
 * client holding a bank-shaped URL would be holding one that stops being true.
 *
 * <h2>What the versions endpoints are for</h2>
 *
 * <p>They are not a convenience. ADR-0106's whole property is that "what exactly did this person
 * see" is answerable three months later, and an id recorded against an attempt has to be
 * dereferenceable by something. {@code GET /questions/{id}/versions/{versionId}} is that
 * something, and it answers for retired questions too.
 *
 * <p>There is no write verb on a version anywhere, and there is not going to be one. A served
 * version is refused by the database; a draft is edited through its question, because "edit the
 * draft" and "create a new version" are the same author action and only the data decides which
 * happens.
 *
 * <h2>No {@code @PreAuthorize} yet</h2>
 *
 * <p>Same position as {@code BankResource}, for the same reason and with the same refusal to
 * substitute a local check: {@code bank:author} exists in the catalog and is grantable at BANK
 * scope, and nothing can evaluate it from this process until ADR-0109's {@code core} merge.
 */
@RestController
@RequestMapping("/api/v1")
public class QuestionResource {

    private final QuestionService questions;
    private final QuestionBodies bodies;

    public QuestionResource(QuestionService questions, QuestionBodies bodies) {
        this.questions = questions;
        this.bodies = bodies;
    }

    /**
     * A version as a client sees it.
     *
     * @param body returned as JSON rather than as a string, so a client parses one document
     *             rather than two
     * @param firstServedAt null while this version is still a draft, which is also the client's
     *                      answer to "will editing this produce a new version"
     */
    public record VersionView(UUID id, int version, JsonNode body, Instant firstServedAt,
            Instant createdAt) {}

    /** A question and the version it is currently on. The entity never crosses the wire. */
    public record QuestionView(UUID id, UUID bankId, String internalName, VersionView currentVersion,
            Instant retiredAt, Instant createdAt, Instant updatedAt) {}

    /** What a client sends to create. */
    public record CreateForm(String internalName, JsonNode body) {}

    /**
     * What a client sends to edit. Both optional: a name-only edit never touches a version, and a
     * body-only edit leaves the name alone.
     */
    public record EditForm(String internalName, JsonNode body) {}

    /** What a client sends to move a question to another bank. */
    public record BankForm(UUID bankId) {}

    /**
     * How hard it is and what it is about -- what a draw filters on (T-6.5).
     *
     * <p>Both together, because they are one thought: an author classifying a question does both at
     * once, and a section reads them together. Neither is versioned (ADR-0106), so sending this
     * creates no version and disturbs no attempt.
     *
     * @param difficultyId one of this company's levels, or null to clear it
     * @param tagIds       replaces the whole set. Each must be in this company's vocabulary
     *                     (T-6.1) -- a tag nobody defined is a question no section can reliably
     *                     draw, which is the failure the vocabulary exists to prevent
     */
    public record DescriptionForm(UUID difficultyId, List<UUID> tagIds) {}

    /** A question's draw attributes, read back. */
    public record DescriptionView(UUID difficultyId, List<UUID> tagIds) {}

    /**
     * What "delete" did.
     *
     * @param retired true when the question was retired because it has been served, false when it
     *                was deleted outright. The client is told which rather than left to infer it
     *                from a later 404 that only happens in one of the two cases.
     */
    public record DeletionView(boolean retired, String explanation) {}

    @GetMapping("/banks/{bankId}/questions")
    @ApiResponse(responseCode = "200", description = "The bank's questions, retired ones excluded")
    @ApiResponse(responseCode = "404", description = "No such bank in this company",
        content = @Content)
    public List<QuestionView> list(@PathVariable UUID bankId) {
        return questions.list(bankId).stream().map(this::view).toList();
    }

    @PostMapping("/banks/{bankId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The question, on its first version")
    @ApiResponse(responseCode = "404", description = "No such bank in this company",
        content = @Content)
    public QuestionView create(@PathVariable UUID bankId, @RequestBody CreateForm form) {
        return view(questions.create(bankId, form.internalName(), form.body()));
    }

    @GetMapping("/questions/{id}")
    @ApiResponse(responseCode = "200", description = "The question and its current version")
    @ApiResponse(responseCode = "404", description = "No such question, or it has been retired",
        content = @Content)
    public QuestionView get(@PathVariable UUID id) {
        return view(questions.get(id));
    }

    /**
     * Edit, which produces a new version only if the body changed and the current version has been
     * served. The response carries the current version either way, so a client can see which
     * happened without asking.
     */
    @PutMapping("/questions/{id}")
    @ApiResponse(responseCode = "200", description = "The question as it now is")
    @ApiResponse(responseCode = "404", description = "No such question, or it has been retired",
        content = @Content)
    public QuestionView edit(@PathVariable UUID id, @RequestBody EditForm form) {
        return view(questions.edit(id, form.internalName(), form.body()));
    }

    @PutMapping("/questions/{id}/bank")
    @ApiResponse(responseCode = "200", description = "The question, now in the other bank")
    @ApiResponse(responseCode = "404", description = "No such question or no such bank",
        content = @Content)
    public QuestionView move(@PathVariable UUID id, @RequestBody BankForm form) {
        return view(questions.moveToBank(id, form.bankId()));
    }

    @GetMapping("/questions/{id}/description")
    @ApiResponse(responseCode = "200", description = "What a draw filters this question on")
    @ApiResponse(responseCode = "404", description = "No such question", content = @Content)
    public DescriptionView description(@PathVariable UUID id) {
        return new DescriptionView(questions.get(id).getDifficultyId(), questions.tagsOf(id));
    }

    @PutMapping("/questions/{id}/description")
    @ApiResponse(responseCode = "200", description = "The question's draw attributes as they now are")
    @ApiResponse(responseCode = "400",
        description = "A tag that is not in this company's vocabulary (T-6.1)", content = @Content)
    @ApiResponse(responseCode = "404", description = "No such question", content = @Content)
    public DescriptionView describe(@PathVariable UUID id, @RequestBody DescriptionForm form) {
        questions.describe(id, form.difficultyId(),
            form.tagIds() == null ? List.of() : form.tagIds());
        return new DescriptionView(questions.get(id).getDifficultyId(), questions.tagsOf(id));
    }

    @DeleteMapping("/questions/{id}")
    @ApiResponse(responseCode = "200", description = "Whether the question was retired or deleted")
    @ApiResponse(responseCode = "404", description = "No such question, or it was already retired",
        content = @Content)
    public DeletionView delete(@PathVariable UUID id) {
        boolean retired = questions.delete(id);
        return new DeletionView(retired, retired
            ? "This question has been served, so it was retired rather than deleted: it leaves "
                + "authoring and every future draw, and every attempt already recorded still renders."
            : "No version of this question was ever served, so nothing referenced it and it was "
                + "deleted outright.");
    }

    @GetMapping("/questions/{id}/versions")
    @ApiResponse(responseCode = "200", description = "Every version, newest first")
    @ApiResponse(responseCode = "404", description = "No such question", content = @Content)
    public List<VersionView> history(@PathVariable UUID id) {
        return questions.history(id).stream().map(this::view).toList();
    }

    /** One version, exactly as it was served. Answers for retired questions too. */
    @GetMapping("/questions/{id}/versions/{versionId}")
    @ApiResponse(responseCode = "200", description = "The version, as it was served")
    @ApiResponse(responseCode = "404", description = "No such question or no such version of it",
        content = @Content)
    public VersionView version(@PathVariable UUID id, @PathVariable UUID versionId) {
        return view(questions.version(id, versionId));
    }

    private QuestionView view(Question question) {
        return new QuestionView(question.getId(), question.getBankId(), question.getInternalName(),
            view(questions.currentVersionOf(question)), question.getRetiredAt(),
            question.getCreatedAt(), question.getUpdatedAt());
    }

    private VersionView view(QuestionVersion asked) {
        return new VersionView(asked.getId(), asked.getVersion(), bodies.read(asked.getBody()),
            asked.getFirstServedAt(), asked.getCreatedAt());
    }
}
