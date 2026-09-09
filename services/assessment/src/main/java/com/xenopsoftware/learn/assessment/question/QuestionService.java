package com.xenopsoftware.learn.assessment.question;

import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypes;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Authoring questions, and the one branch that ADR-0106 turns on (T-6.2).
 *
 * <h2>The branch</h2>
 *
 * <p>Editing a question does one of two entirely different things, and which one is not a choice
 * the caller makes — it is a fact about whether anybody has been served the current version:
 *
 * <ul>
 *   <li><b>Draft</b> ({@code first_served_at} is null): the row is updated. An author fixing a typo
 *       before anyone sits the test sees nothing at all — the same version, corrected. No new row,
 *       no noise, no archaeology of a question that never reached a learner.
 *   <li><b>Served</b>: a new version is inserted and {@code current_version_id} moves. The old row
 *       stays exactly as it was, and every attempt already recorded still renders the sentence its
 *       learner actually read.
 * </ul>
 *
 * <p>That is the ADR's answer to the tension its own acceptance criteria name — always versioning
 * is noise nobody can navigate, never versioning is the bug — and the reason it dissolves is that
 * the rule is tied to <em>service</em> rather than to editing.
 *
 * <h2>What is deliberately not enforced here yet</h2>
 *
 * <p>No {@code @PreAuthorize}, for the reason {@code BankService} states in full: the evaluator and
 * the grants live inside {@code identity}, so a separate process cannot ask it anything until
 * ADR-0109's {@code core} merge. {@code bank:author} already exists in the catalog and is grantable
 * at BANK scope, and questions need no permission of their own — a question is only ever reached
 * through the bank that owns it, which is what T-6.1 built the bank to be.
 *
 * <p>And, as there, no local shortcut in the meantime. A convenience check written here would be
 * the special case the criterion warns about, an endpoint would come to trust it, and removing it
 * later would be harder than never adding it.
 */
@Service
@Transactional
public class QuestionService {

    private final QuestionRepository questions;
    private final QuestionVersionRepository versions;
    private final QuestionBodies bodies;
    private final QuestionTypes types;
    private final BankService banks;
    private final QuestionTags tags;

    public QuestionService(QuestionRepository questions, QuestionVersionRepository versions,
            QuestionBodies bodies, QuestionTypes types, BankService banks, QuestionTags tags) {
        this.questions = questions;
        this.versions = versions;
        this.bodies = bodies;
        this.types = types;
        this.banks = banks;
        this.tags = tags;
    }

    @Transactional(readOnly = true)
    public List<Question> list(UUID bankId) {
        // Through the bank rather than straight to the questions, so a bank id from another
        // company answers 404 instead of an empty list. An empty list is a worse answer: it says
        // the bank exists here and has nothing in it.
        banks.get(bankId);
        return questions.findByBankIdAndRetiredAtIsNullOrderByInternalNameAsc(bankId);
    }

    /** A question an author may work on. A retired one is absent from authoring, as if deleted. */
    @Transactional(readOnly = true)
    public Question get(UUID id) {
        return questions.findByIdAndRetiredAtIsNull(id).orElseThrow(QuestionNotFound::new);
    }

    /** The version this question is currently on, which is what a draw would pick up. */
    @Transactional(readOnly = true)
    public QuestionVersion currentVersionOf(Question question) {
        return versions.findById(question.getCurrentVersionId()).orElseThrow(QuestionNotFound::new);
    }

    /**
     * A new question, and the first version of what it asks.
     *
     * <p>Both rows in one transaction, and the question is flushed before the version is written:
     * {@code question_version.question_id} is a real foreign key, and the deadlock this repository
     * has paid for twice is a second statement referencing a row the first has not committed.
     */
    public Question create(UUID bankId, String internalName, JsonNode body) {
        banks.get(bankId);
        // Before anything is written (T-6.3). The body is a jsonb column, so the database will
        // not catch a key that names a choice the question does not offer -- and nothing else
        // will either, until a learner cannot be scored.
        types.validateAsked(body);

        Question question = questions.saveAndFlush(Question.create(bankId, internalName));
        QuestionVersion first = versions.saveAndFlush(
            QuestionVersion.create(question.getId(), 1, bodies.write(body)));
        question.currentVersionIs(first);
        return questions.save(question);
    }

    /**
     * Edit a question, which may or may not produce a version.
     *
     * <p>Three outcomes, and only the third is a version:
     *
     * <ol>
     *   <li>the name changed and the body did not — no version, because the internal name is not
     *       what anybody was asked;
     *   <li>the body changed and the current version is a draft — the same row, corrected;
     *   <li>the body changed and the current version has been served — a new version.
     * </ol>
     *
     * <p>A body that is equal to the one already stored counts as unchanged even when the client
     * sent its fields in a different order ({@link QuestionBodies#equal}). Without that, an
     * authoring screen that round-trips the document would mint a permanent version, visible to
     * every analyst reading item statistics, for a change nobody made.
     */
    public Question edit(UUID id, String internalName, JsonNode body) {
        Question question = get(id);
        QuestionVersion current = currentVersionOf(question);

        if (internalName != null && !internalName.isBlank()) {
            question.rename(internalName);
        }

        if (body != null && !body.isNull() && !bodies.equal(current.getBody(), body)) {
            // Validated on every edit, not only on create: a type's rules can tighten, and a
            // version saved under the old ones must not be re-saved under them.
            types.validateAsked(body);
            if (current.isDraft()) {
                current.editDraft(bodies.write(body));
                versions.save(current);
            } else {
                QuestionVersion next = versions.saveAndFlush(QuestionVersion.create(
                    question.getId(), current.getVersion() + 1, bodies.write(body)));
                question.currentVersionIs(next);
            }
        }

        return questions.save(question);
    }

    /** Every version of this question, newest first. The old ones stay reachable forever. */
    @Transactional(readOnly = true)
    public List<QuestionVersion> history(UUID id) {
        Question question = questions.findById(id).orElseThrow(QuestionNotFound::new);
        return versions.findByQuestionIdOrderByVersionDesc(question.getId());
    }

    /**
     * One version, exactly as it was served.
     *
     * <p>Reachable for a retired question too, and that is the point of retiring rather than
     * deleting: a result disputed next year is answerable about a question nobody may author any
     * more.
     */
    @Transactional(readOnly = true)
    public QuestionVersion version(UUID id, UUID versionId) {
        Question question = questions.findById(id).orElseThrow(QuestionNotFound::new);
        return versions.findByQuestionIdAndId(question.getId(), versionId)
            .orElseThrow(QuestionNotFound::new);
    }

    /**
     * "Delete", which is two operations that an author experiences as one.
     *
     * <p>If any version has ever been served the question is <b>retired</b>: gone from authoring
     * and from every future draw, with every attempt still rendering and every report still
     * correct. If none has, the rows are <b>deleted outright</b> — a mistake being cleaned up
     * before it reached anybody, which costs nothing to allow because by construction nothing
     * references it.
     *
     * <p>One endpoint rather than two, because the author's intent is one thing. Which of the two
     * happens is a fact about the data, and a caller that had to choose would eventually choose
     * wrong — the interesting direction being a hard delete of something that had been served.
     *
     * @return true if the question was retired, false if it was deleted outright
     */
    public boolean delete(UUID id) {
        Question question = get(id);

        if (versions.existsByQuestionIdAndFirstServedAtIsNotNull(question.getId())) {
            question.retire();
            questions.save(question);
            return true;
        }

        // The foreign key from question to its current version has to be released first, and the
        // flush is what makes the order real rather than whatever Hibernate decides at commit.
        question.clearCurrentVersion();
        questions.saveAndFlush(question);
        versions.deleteByQuestionId(question.getId());
        questions.delete(question);
        return false;
    }

    /**
     * Move a question to another bank, which ADR-0106 says is not an edit to what was asked.
     *
     * <p>So it produces no version and disturbs no attempt. It is here rather than folded into
     * {@link #edit} because the two have nothing in common: this one cannot ever create a version,
     * and a caller passing a bank id to an edit endpoint would make that look like a possibility.
     */
    public Question moveToBank(UUID id, UUID bankId) {
        Question question = get(id);
        banks.get(bankId);
        question.moveTo(bankId);
        return questions.save(question);
    }

    /**
     * How hard it is and what it is about -- the two things a draw filters on (T-6.5).
     *
     * <p>Neither is versioned, which ADR-0106 puts on the non-versioned side and V2 deferred to
     * this issue: an author deciding a question is harder than they first thought, or adding a tag
     * they forgot, has not changed what anybody was asked. So this produces no version and
     * disturbs no attempt, exactly like {@link #moveToBank}.
     *
     * <p>Both together rather than two methods, because they are one thought -- an author
     * classifying a question does both at once -- and because a draw reads them together.
     */
    public Question describe(UUID id, UUID difficultyId, java.util.Collection<UUID> tagIds) {
        Question question = get(id);
        question.difficultyIs(difficultyId);
        Question saved = questions.save(question);
        // After the save, so a difficulty id from another company is refused by the foreign key
        // before any tag is written -- one failed request rather than a half-applied one.
        tags.set(id, tagIds);
        return saved;
    }

    /** What it is about, for the authoring screen that renders it. */
    @Transactional(readOnly = true)
    public java.util.List<UUID> tagsOf(UUID id) {
        return tags.of(get(id).getId());
    }
}
