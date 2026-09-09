package com.xenopsoftware.learn.assessment.exam;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
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
 * The two lists a section can carry: its named questions, and its pool's tags (T-6.5).
 *
 * <p>One class for both because they are the same shape and the same rule — an ordered or unordered
 * set of ids, replaced wholesale, refused with a sentence when an id is not this company's. Two
 * classes would be two copies of the refusal.
 *
 * <p>Plain SQL and no entity, the reason {@code QuestionTags} gives: these are read in bulk by the
 * assembler and never navigated from a section, and a mapped collection would invite somebody to
 * load one lazily inside a draw.
 */
@Component
public class SectionMembers {

    private final JdbcTemplate jdbc;

    public SectionMembers(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A fixed section's questions, in the author's order. */
    @Transactional(readOnly = true)
    public List<UUID> questionsOf(UUID sectionId) {
        return jdbc.queryForList("""
            SELECT question_id FROM test_section_question
             WHERE tenant_id = ? AND section_id = ?
             ORDER BY ordinal, question_id
            """, UUID.class, TenantContext.require(), sectionId);
    }

    /**
     * Replaces a fixed section's questions.
     *
     * <p>Ordinals are dense integers here and not the rational midpoints a course uses, because the
     * whole list arrives in every request: there is no insert-between to make cheap, and a
     * fractional index would be machinery serving a case that cannot occur.
     */
    @Transactional
    public void questionsAre(UUID sectionId, List<UUID> questionIds) {
        String tenantId = TenantContext.require();
        Set<UUID> ordered = new LinkedHashSet<>(questionIds == null ? List.of() : questionIds);
        jdbc.update("DELETE FROM test_section_question WHERE tenant_id = ? AND section_id = ?",
            tenantId, sectionId);
        int position = 0;
        for (UUID questionId : ordered) {
            try {
                jdbc.update("""
                    INSERT INTO test_section_question (section_id, question_id, tenant_id, ordinal)
                    VALUES (?, ?, ?, ?)
                    """, sectionId, questionId, tenantId, BigDecimal.valueOf(position++));
            } catch (DataIntegrityViolationException noSuchQuestion) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "There is no question " + questionId + " in this company.", noSuchQuestion);
            }
        }
    }

    /** A pool section's tags. A drawn question must carry all of them. */
    @Transactional(readOnly = true)
    public List<UUID> tagsOf(UUID sectionId) {
        return jdbc.queryForList(
            "SELECT tag_id FROM test_section_tag WHERE tenant_id = ? AND section_id = ?",
            UUID.class, TenantContext.require(), sectionId);
    }

    @Transactional
    public void tagsAre(UUID sectionId, Collection<UUID> tagIds) {
        String tenantId = TenantContext.require();
        Set<UUID> wanted = new LinkedHashSet<>(tagIds == null ? List.of() : tagIds);
        jdbc.update("DELETE FROM test_section_tag WHERE tenant_id = ? AND section_id = ?",
            tenantId, sectionId);
        for (UUID tagId : wanted) {
            try {
                jdbc.update("""
                    INSERT INTO test_section_tag (section_id, tag_id, tenant_id) VALUES (?, ?, ?)
                    """, sectionId, tagId, tenantId);
            } catch (DataIntegrityViolationException notInTheVocabulary) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "There is no tag " + tagId + " in this company's vocabulary, so a section "
                    + "asking for it would draw from an empty population (T-6.1).",
                    notInTheVocabulary);
            }
        }
    }
}
