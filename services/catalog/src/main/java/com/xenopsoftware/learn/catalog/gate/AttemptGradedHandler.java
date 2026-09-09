package com.xenopsoftware.learn.catalog.gate;

import com.xenopsoftware.learn.catalog.assign.LearnerProfiles;
import com.xenopsoftware.learn.catalog.home.HomeVersions;
import com.xenopsoftware.learn.common.mail.Letter;
import com.xenopsoftware.learn.common.mail.MailNotSent;
import com.xenopsoftware.learn.common.mail.Mailer;
import com.xenopsoftware.learn.common.messaging.MessageHandler;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A passed test opens the gate behind it, and tells the learner (T-6.7, T-5.3).
 *
 * <p>The other half of what {@code NodeCompletionHandler} does for streaming's completions, and the
 * one {@link RequiredState#PASSED} was written for — "pass the safety test" has been sayable since
 * T-5.3 and unreachable until now.
 *
 * <h2>The walk from a test to a node</h2>
 *
 * <p>Assessment names a test; catalog knows which content items point at it ({@code type = 'test'},
 * payload {@code testId}) and which nodes point at those. Every such node is completed, which is
 * the same content item appearing in the onboarding course and the annual refresher being finished
 * in both — the reference model T-5.2 chose, showing through.
 *
 * <h2>Only a settled, passing attempt records anything</h2>
 *
 * <p>Assessment only announces settled attempts, and this records only passing ones. The two
 * absences carry the whole of T-6.7's second criterion:
 *
 * <ul>
 *   <li><b>An attempt awaiting a person records nothing</b>, so a gate stays shut rather than
 *       opening on a provisional score.
 *   <li><b>A failed attempt records nothing either</b>, which is not the same thing — and neither
 *       is a fail, because the learner may sit it again. A gate reads the absence of a PASSED row,
 *       and the absence never has to be distinguished from "not yet" because nothing here can
 *       write a negative.
 * </ul>
 *
 * <p>That is why "every gate handles AWAITING_GRADING explicitly" needs no code in the evaluator: a
 * gate cannot read a null score as a fail if there is no score for it to read.
 *
 * <h2>The letter is sent from here, not from assessment</h2>
 *
 * <p>Catalog already holds the learner's address (a projection of identity's, T-5.6) and already
 * owns telling people about their training. Sending it from assessment would put a third copy of
 * every learner's email address in a third database, fed by the same event, to say one sentence.
 *
 * <p>It is sent only when somebody was actually waiting — {@code wasAwaitingAPerson}. An exam marked
 * the instant it was submitted needs no letter; the learner is looking at the result.
 */
@Component
public class AttemptGradedHandler implements MessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AttemptGradedHandler.class);

    private final JdbcTemplate jdbc;
    private final HomeVersions versions;
    private final LearnerProfiles profiles;
    private final Mailer mailer;
    private final JsonMapper json = JsonMapper.builder().build();

    public AttemptGradedHandler(DataSource dataSource, HomeVersions versions,
            LearnerProfiles profiles, Mailer mailer) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.versions = versions;
        this.profiles = profiles;
        this.mailer = mailer;
    }

    @Override
    public String subject() {
        return "assessment.attempt.graded";
    }

    @Override
    public void handle(OutboxMessage message) {
        JsonNode body = json.readTree(message.payload());
        String tenantId = body.get("tenantId").asString();
        UUID learnerId = UUID.fromString(body.get("learnerId").asString());
        UUID testId = UUID.fromString(body.get("testId").asString());
        boolean passed = body.get("passed").asBoolean();
        Instant gradedAt = Instant.parse(body.get("gradedAt").asString());
        boolean wasWaiting = body.has("wasAwaitingAPerson")
            && body.get("wasAwaitingAPerson").asBoolean();

        if (passed) {
            recordPass(tenantId, learnerId, testId, gradedAt);
        }
        // Their home screen said this was still to do, or still to hear about (T-5.8).
        versions.bumpLearner(tenantId, learnerId);

        if (wasWaiting) {
            tell(tenantId, learnerId, body, passed);
        }
    }

    /**
     * Records a PASSED against every node pointing at this test.
     *
     * <p>Conditional on the unique key rather than guarded by a read, for
     * {@code NodeCompletionHandler}'s reason: the bus is at-least-once, and checking "have I
     * recorded this" and then recording it has a window where two deliveries both find nothing.
     *
     * <p>A pass for a test no node references writes nothing and is not an error — the course was
     * edited between the learner sitting it and this arriving, which is ordinary, and a failing
     * insert would put a poison message at the head of the queue.
     */
    private void recordPass(String tenantId, UUID learnerId, UUID testId, Instant gradedAt) {
        List<UUID> nodes = jdbc.queryForList("""
            SELECT n.id
              FROM course_node n
              JOIN content_item i ON i.id = n.content_item_id
             WHERE n.tenant_id = ? AND i.type = 'test' AND i.payload ->> 'testId' = ?
            """, UUID.class, tenantId, testId.toString());

        for (UUID nodeId : nodes) {
            jdbc.update("""
                INSERT INTO node_completion (id, tenant_id, learner_id, node_id, state, recorded_at)
                VALUES (?, ?, ?, ?, 'PASSED', ?)
                ON CONFLICT ON CONSTRAINT uq_node_completion DO NOTHING
                """, UUID.randomUUID(), tenantId, learnerId, nodeId, Timestamp.from(gradedAt));
        }
        if (nodes.isEmpty()) {
            LOG.debug("A pass at test {} in {} reaches no node here", testId, tenantId);
        }
    }

    /**
     * Tells the learner their pending attempt has been marked.
     *
     * <p>A mail failure is logged and swallowed, which is T-5.6's rule and matters more here: a
     * mail server being down must never roll back a verdict. The result is recorded either way, and
     * the learner sees it the next time they look.
     */
    private void tell(String tenantId, UUID learnerId, JsonNode body, boolean passed) {
        Optional<LearnerProfiles.Profile> profile = profiles.of(tenantId, learnerId);
        if (profile.isEmpty() || profile.get().email() == null || profile.get().email().isBlank()) {
            LOG.info("Attempt marked for {} in {}, but we hold no address for them", learnerId,
                tenantId);
            return;
        }
        // The verdict and the score, and nothing about the questions: an email is the least private
        // place this platform writes, and "you scored 64%" needs no help from the paper.
        String subject = passed ? "Your test has been marked" : "Your test has been marked";
        String outcome = passed ? "You passed." : "You did not pass this time.";
        String letter = """
            Your test has been marked.

            %s
            Score: %s%%

            Sign in to see the result in full.
            """.formatted(outcome, body.get("scorePercent").asString());
        try {
            mailer.send(new Letter(profile.get().email(), subject, letter));
        } catch (MailNotSent notSent) {
            // The mark stands whatever the mail server did. An exam result does not stop existing
            // because a letter did not arrive.
            LOG.warn("Could not tell {} in {} that their attempt was marked", learnerId, tenantId,
                notSent);
        }
    }
}
