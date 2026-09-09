package com.xenopsoftware.learn.assessment.form;

import com.xenopsoftware.learn.assessment.scoring.QuestionScoring;
import com.xenopsoftware.learn.assessment.scoring.ScoringMode;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading and writing the record (T-6.5).
 *
 * <p>Plain SQL and no entity, and here that is more than a preference. <b>A form is written once
 * and never updated</b>; the database refuses an UPDATE or a DELETE outright (see
 * {@code V4__section_and_form.sql}). A JPA entity would give every caller a managed object whose
 * setters compile, whose dirty checking fires on a stray field write, and whose failure would then
 * arrive as a constraint violation from a flush nobody asked for. There is nothing here to manage.
 */
@Component
public class Forms {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public Forms(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Writes a form and its items.
     *
     * @throws ResponseStatusException 409 when this attempt already has one. <b>Reassembly is
     *         impossible</b> and the unique key is what makes that true — a check-then-insert has a
     *         window where two starts both find nothing, and the database is the only thing that
     *         can arbitrate that without one
     */
    @Transactional
    public Form write(UUID testId, UUID attemptId, long seed, List<FormItem> items) {
        String tenantId = TenantContext.require();
        UUID formId = UUID.randomUUID();
        Instant now = Instant.now();

        try {
            jdbc.update("""
                INSERT INTO test_form (id, tenant_id, test_id, attempt_id, seed, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, formId, tenantId, testId, attemptId, seed, Timestamp.from(now));
        } catch (DuplicateKeyException alreadyAssembled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This attempt already has a form. Reassembling it would change what the learner "
                + "was asked after they were asked it.", alreadyAssembled);
        }

        for (FormItem item : items) {
            jdbc.update("""
                INSERT INTO test_form_item (id, tenant_id, form_id, section_id, position,
                        question_version_id, option_order, points, mode)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, item.id(), tenantId, formId, item.sectionId(), item.position(),
                item.questionVersionId(),
                item.optionOrder().isEmpty() ? null : JSON.writeValueAsString(item.optionOrder()),
                item.scoring().points(), item.scoring().mode().name());
        }

        return new Form(formId, testId, attemptId, seed, now, items);
    }

    /** What one learner was asked, in served order. */
    @Transactional(readOnly = true)
    public Form ofAttempt(UUID attemptId) {
        String tenantId = TenantContext.require();
        List<Map<String, Object>> header = jdbc.queryForList("""
            SELECT id, test_id, seed, created_at FROM test_form
             WHERE tenant_id = ? AND attempt_id = ?
            """, tenantId, attemptId);
        if (header.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No form for this attempt: nothing has been assembled for it.");
        }
        Map<String, Object> row = header.getFirst();
        UUID formId = (UUID) row.get("id");

        List<FormItem> items = jdbc.query("""
            SELECT id, position, section_id, question_version_id, option_order::text AS option_order,
                   points, mode
              FROM test_form_item
             WHERE tenant_id = ? AND form_id = ?
             ORDER BY position
            """, (rows, index) -> new FormItem(
                rows.getObject("id", UUID.class),
                rows.getInt("position"),
                rows.getObject("section_id", UUID.class),
                rows.getObject("question_version_id", UUID.class),
                orderFrom(rows.getString("option_order")),
                // Zero penalty, because a penalty is the TEST's and not the item's (T-6.4): it
                // is read from the test at grading time along with whether negative marking is on
                // at all. Storing it per item would be a third copy of one number.
                new QuestionScoring(rows.getBigDecimal("points"),
                    ScoringMode.valueOf(rows.getString("mode")), BigDecimal.ZERO)),
            tenantId, formId);

        return new Form(formId, (UUID) row.get("test_id"), attemptId, (Long) row.get("seed"),
            ((Timestamp) row.get("created_at")).toInstant(), items);
    }

    private static Map<String, List<String>> orderFrom(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> order = new LinkedHashMap<>();
        JSON.readTree(json).properties().forEach(entry -> {
            List<String> ids = new ArrayList<>();
            entry.getValue().valueStream().forEach(id -> ids.add(id.asString()));
            order.put(entry.getKey(), List.copyOf(ids));
        });
        return order;
    }
}
