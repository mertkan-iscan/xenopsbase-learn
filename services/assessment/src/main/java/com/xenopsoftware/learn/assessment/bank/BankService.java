package com.xenopsoftware.learn.assessment.bank;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Banks, and what a customer may do with the shared library (T-6.1).
 *
 * <h2>What is deliberately not enforced here yet</h2>
 *
 * <p>T-6.1 asks for an authoring permission scoped to a bank, "enforced through the existing
 * evaluator rather than a special case". That evaluator lives inside {@code identity}, and so do
 * the grants it resolves, so a separate process cannot ask it anything — which is what ADR-0109's
 * {@code core} merge is for. The permissions exist in the catalog ({@code bank:read},
 * {@code bank:author}, {@code bank:manage}) and are grantable at BANK scope; nothing checks them.
 *
 * <p>The important part is what this class does <b>not</b> do in the meantime. There is no local
 * "is this person an author" shortcut — no role name read off the token, no owner column on the
 * bank. A convenience check written here would be exactly the special case the criterion names, it
 * would be the thing an endpoint later trusts, and removing it would be harder than never adding
 * it. Every authenticated caller who reaches these methods is currently allowed to.
 */
@Service
@Transactional
public class BankService {

    private final QuestionBankRepository banks;
    private final PlatformBanks platformBanks;

    public BankService(QuestionBankRepository banks, PlatformBanks platformBanks) {
        this.banks = banks;
        this.platformBanks = platformBanks;
    }

    @Transactional(readOnly = true)
    public List<QuestionBank> list() {
        return banks.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public QuestionBank get(UUID id) {
        return banks.findById(id).orElseThrow(BankNotFound::new);
    }

    public QuestionBank create(String name, String description) {
        refuseDuplicateName(name, null);
        return banks.save(QuestionBank.create(name, description));
    }

    public QuestionBank rename(UUID id, String name, String description) {
        QuestionBank bank = get(id);
        refuseDuplicateName(name, bank.getId());
        bank.rename(name, description);
        return banks.save(bank);
    }

    /** The shared library, as a customer sees it: readable, copyable, never editable. */
    @Transactional(readOnly = true)
    public List<PlatformBanks.SharedBank> sharedLibrary() {
        return platformBanks.all();
    }

    /**
     * Copy a shared bank into this tenant.
     *
     * <p>The copy is independent from the moment it exists: it records where it came from and
     * nothing joins on that, so a later edit, archive or withdrawal upstream cannot reach it. That
     * independence is a property of the schema rather than of this method — there is no link to
     * break — which is why it holds for copies made before anybody thought to test it.
     *
     * <p><b>The questions are not copied, because questions do not exist yet (T-6.2).</b> This
     * copies the bank and its provenance; the loop that copies contents belongs with the type it
     * would be copying. Said out loud rather than left to be discovered: a copy taken today is an
     * empty bank with an origin, not a populated one.
     */
    public QuestionBank copy(UUID sharedBankId, String name, String description) {
        PlatformBanks.SharedBank source =
            platformBanks.find(sharedBankId).orElseThrow(BankNotFound::new);

        String copyName = name == null || name.isBlank() ? source.name() : name;
        refuseDuplicateName(copyName, null);

        QuestionBank copy = QuestionBank.create(copyName,
            description == null || description.isBlank() ? source.description() : description);
        copy.recordCopiedFrom(source.id());
        return banks.save(copy);
    }

    /**
     * A name collision is a 409 with a sentence, not a constraint violation.
     *
     * <p>The unique index is what makes the rule true under concurrency; this is what makes it
     * actionable. Without it an author renaming a bank gets a 500 out of a driver exception, which
     * says nothing about what to do next — and the 500 would be indistinguishable from a service
     * that is actually broken.
     */
    private void refuseDuplicateName(String name, UUID allowedId) {
        if (name == null || name.isBlank()) {
            // The entity refuses a blank name with its own message; this method has no better
            // one to offer, and two validations of the same thing is one that goes stale.
            return;
        }
        banks.findByNameIgnoreCase(name.strip()).ifPresent(existing -> {
            if (!existing.getId().equals(allowedId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This company already has a bank called '" + existing.getName()
                        + "'. Two banks with one name are two populations a section could draw "
                        + "from, with no way for an author to tell which one they picked.");
            }
        });
    }
}
