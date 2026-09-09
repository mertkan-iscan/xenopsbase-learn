package com.xenopsoftware.learn.assessment.question;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The moment a version stops being a draft and becomes history (T-6.2, ADR-0106).
 *
 * <h2>Why this exists before anything calls it</h2>
 *
 * <p>Serving a question belongs to delivery — T-6.5 draws the form, T-6.6 runs the attempt — and
 * neither exists. This is here anyway for two reasons, and the second is the one that matters.
 *
 * <p>The first: the rule the whole issue is about is unexercisable without it. "Editing a served
 * version creates a new version" cannot be tested, or even reached, if nothing can make a version
 * served.
 *
 * <p>The second: <b>this is the shape delivery has to use, and it is easy to get wrong.</b> The
 * obvious implementation — read the version, check the timestamp, set it, save — is a race. Two
 * learners can be handed the same question in the same millisecond; both read null, both write,
 * and the loser's UPDATE meets a row that is now history and is refused by the trigger. A learner
 * who did nothing wrong gets an error opening a test. Leaving delivery to discover that is leaving
 * it to discover it in production, so the statement lives here, once, and
 * {@link QuestionVersionRepository#markServed} is a single conditional UPDATE.
 *
 * <h2>Two steps, and both are load-bearing</h2>
 *
 * <p>The read is what proves the version belongs to the calling tenant — {@code @TenantId} filters
 * it, so another company's version is simply absent. The conditional update is what makes the
 * write first-wins. Doing only the second would work and would also let a version id from another
 * company be stamped by a caller who guessed it.
 */
@Service
public class ServedVersions {

    private final QuestionVersionRepository versions;

    public ServedVersions(QuestionVersionRepository versions) {
        this.versions = versions;
    }

    /**
     * Record that this version has been put in front of a learner.
     *
     * <p>Idempotent, deliberately: delivery may call it for every learner who is handed the
     * question, and only the first call changes anything. The timestamp answers "when did this
     * version stop being editable", not "how often was it used" — that is item statistics, and
     * T-7.7 counts responses rather than reading this column.
     *
     * @return true if this call is what made the version history; false if it already was
     * @throws QuestionNotFound if no such version belongs to the calling tenant
     */
    @Transactional
    public boolean markServed(UUID versionId) {
        QuestionVersion version = versions.findById(versionId).orElseThrow(QuestionNotFound::new);
        return versions.markServed(version.getId(), Instant.now()) == 1;
    }
}
