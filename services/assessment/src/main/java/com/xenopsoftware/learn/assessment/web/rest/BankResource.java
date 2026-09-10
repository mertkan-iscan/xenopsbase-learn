package com.xenopsoftware.learn.assessment.web.rest;

import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.bank.PlatformBanks;
import com.xenopsoftware.learn.assessment.bank.QuestionBank;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question banks, and the shared library they can be copied from (T-6.1).
 *
 * <h2>Two paths, because they are two different things</h2>
 *
 * <p>{@code /api/v1/banks} is this company's own, readable and writable. {@code /api/v1/shared-banks}
 * is the platform's library: readable, copyable, and with no write verb anywhere on it. A single
 * collection with a {@code shared} flag would have put "may I edit this one" into the body of every
 * request, which is the shape that eventually gets it wrong.
 *
 * <p>Separate literal paths rather than {@code /banks/shared} beside {@code /banks/{id}}, so that
 * nothing depends on Spring preferring a literal segment to a {@code UUID} template. That
 * preference is real and it is also the kind of thing a routing change quietly reverses.
 *
 * <h2>No {@code @PreAuthorize} yet, and that is a decision rather than a gap</h2>
 *
 * <p>T-6.1 wants the authoring boundary enforced through identity's evaluator, which lives in
 * another process along with the grants it resolves (ADR-0109's {@code core} merge is what closes
 * that). The permissions are in the catalog and grantable at BANK scope; nothing checks them here,
 * and nothing here checks anything else instead — see {@link BankService} for why a convenience
 * check would be worse than none.
 *
 * <h2>Every refusal is declared, including the bodiless ones</h2>
 *
 * <p>An {@code @ApiResponse} with no {@code content} falls back to the METHOD'S RETURN TYPE, so a
 * 404 declared bare would be documented as returning a {@link BankView} — which is how a
 * deliberately bodiless 404 was once documented as returning a playback token. The empty
 * {@code @Content} on each 404 here says "no body" and means it; the 409s point at the
 * {@code Problem} schema {@link ProblemDocumentation} registers, so a generated client can type
 * the failure rather than being handed {@code unknown}.
 */
@RestController
@RequestMapping("/api/v1")
public class BankResource {

    private final BankService banks;

    public BankResource(BankService banks) {
        this.banks = banks;
    }

    /** A bank as a client sees it. The entity never crosses the wire. */
    public record BankView(UUID id, String name, String description, UUID copiedFromBankId,
            Instant createdAt, Instant updatedAt) {

        static BankView of(QuestionBank bank) {
            return new BankView(bank.getId(), bank.getName(), bank.getDescription(),
                bank.getCopiedFromBankId(), bank.getCreatedAt(), bank.getUpdatedAt());
        }
    }

    /** What a client sends to create or rename. */
    // QuestionResource has a BankForm too -- {bankId}, for moving a question between banks.
    // springdoc keys components.schemas on the simple name, so the two collided and that one
    // won: the published description of POST /api/v1/banks asked for a bankId to create a
    // bank with. Nothing failed -- the contract gate compares the spec to the service and the
    // service does serve what the spec said for the name that survived. Only a client
    // generated from it was wrong.
    @Schema(name = "NewBankForm")
    public record BankForm(String name, String description) {}

    /** What a client sends to copy: both fields optional, defaulting to the source's. */
    public record CopyForm(String name, String description) {}

    @GetMapping("/banks")
    public List<BankView> list() {
        return banks.list().stream().map(BankView::of).toList();
    }

    @GetMapping("/banks/{id}")
    @ApiResponse(responseCode = "200", description = "The bank")
    @ApiResponse(responseCode = "404", description = "No such bank in this company",
        content = @Content)
    public BankView get(@PathVariable UUID id) {
        return BankView.of(banks.get(id));
    }

    @PostMapping("/banks")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The bank that was created")
    @ApiResponse(responseCode = "409", description = "This company already has a bank with that name",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public BankView create(@RequestBody BankForm form) {
        return BankView.of(banks.create(form.name(), form.description()));
    }

    @PutMapping("/banks/{id}")
    @ApiResponse(responseCode = "200", description = "The bank as it now is")
    @ApiResponse(responseCode = "404", description = "No such bank in this company",
        content = @Content)
    @ApiResponse(responseCode = "409", description = "This company already has a bank with that name",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public BankView rename(@PathVariable UUID id, @RequestBody BankForm form) {
        return BankView.of(banks.rename(id, form.name(), form.description()));
    }

    /**
     * The platform's shared library.
     *
     * <p>No write verb exists on this path and none is coming: offering a bank into the library is
     * a platform act against the platform's own tenant, and it belongs beside
     * {@code tenant:provision} rather than behind an endpoint a customer can reach.
     */
    @GetMapping("/shared-banks")
    public List<PlatformBanks.SharedBank> shared() {
        return banks.sharedLibrary();
    }

    /**
     * Copy a shared bank into this company.
     *
     * <p>201 with the new bank, which is this company's from that moment: later edits to the
     * source do not reach it, and there is no link through which they could.
     */
    @PostMapping("/shared-banks/{id}/copies")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The independent copy, owned by this company")
    @ApiResponse(responseCode = "404", description = "No such bank is offered in the shared library",
        content = @Content)
    @ApiResponse(responseCode = "409", description = "This company already has a bank with that name",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    public BankView copy(@PathVariable UUID id, @RequestBody(required = false) CopyForm form) {
        CopyForm copy = form == null ? new CopyForm(null, null) : form;
        return BankView.of(banks.copy(id, copy.name(), copy.description()));
    }
}
