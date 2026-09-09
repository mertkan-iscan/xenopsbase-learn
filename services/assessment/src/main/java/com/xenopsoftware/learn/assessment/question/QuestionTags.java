package com.xenopsoftware.learn.assessment.question;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Which vocabulary tags a question carries (T-6.5).
 *
 * <p><b>A join table, and the foreign key is the whole reason.</b> T-6.1 built {@code bank_tag}
 * because a mistyped tag does not produce an error, it produces a shorter exam — silently, and only
 * after it has been sat. A {@code text[]} column cannot have a foreign key, so nothing would catch
 * the typo and the vocabulary would be a list nobody had to use. This is what turns "checked at
 * authoring time" from a convention into a constraint the database keeps.
 *
 * <p>Plain SQL rather than a {@code @ManyToMany}, for the reason {@code NodeCompletionRepository}
 * gives in catalog: this is a set read and written in bulk and never navigated from an entity, and
 * a mapped collection would invite somebody to lazily load it inside a draw.
 */
@Component
public class QuestionTags {

    private final JdbcTemplate jdbc;

    public QuestionTags(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** This question's tags, in no particular order — a set is what it is. */
    @Transactional(readOnly = true)
    public List<UUID> of(UUID questionId) {
        return jdbc.queryForList(
            "SELECT tag_id FROM question_tag WHERE tenant_id = ? AND question_id = ?",
            UUID.class, TenantContext.require(), questionId);
    }

    /**
     * Replaces the whole set.
     *
     * <p>Replace rather than add-and-remove, because a client editing tags has the list in front of
     * it and sending the list is one request that cannot half-apply. Duplicates in the input are
     * the caller being untidy rather than wrong, so they are collapsed.
     *
     * @throws ResponseStatusException 400, naming the tag, when one is not in this company's
     *         vocabulary. The database refuses it either way; this is what turns a constraint
     *         violation into a sentence an author can act on
     */
    @Transactional
    public void set(UUID questionId, Collection<UUID> tagIds) {
        String tenantId = TenantContext.require();
        Set<UUID> wanted = new LinkedHashSet<>(tagIds == null ? List.of() : tagIds);
        jdbc.update("DELETE FROM question_tag WHERE tenant_id = ? AND question_id = ?",
            tenantId, questionId);
        for (UUID tagId : wanted) {
            try {
                jdbc.update("""
                    INSERT INTO question_tag (question_id, tag_id, tenant_id) VALUES (?, ?, ?)
                    """, questionId, tagId, tenantId);
            } catch (DataIntegrityViolationException notInTheVocabulary) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "There is no tag " + tagId + " in this company's vocabulary. A question tagged "
                    + "with something nobody defined is a question no section can reliably draw, "
                    + "which is the failure the vocabulary exists to prevent (T-6.1).",
                    notInTheVocabulary);
            }
        }
    }
}
