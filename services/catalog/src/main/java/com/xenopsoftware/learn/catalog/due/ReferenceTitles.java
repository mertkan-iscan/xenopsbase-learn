package com.xenopsoftware.learn.catalog.due;

import com.xenopsoftware.learn.catalog.assign.ReferenceKind;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What to call the thing somebody has been assigned (T-5.6).
 *
 * <p>Exists so a reminder can say "Fire safety is due on Friday" rather than naming a uuid. A
 * reminder nobody can act on without opening the platform to find out what it is about is a
 * reminder that trains people to ignore reminders.
 *
 * <p>A node has no title of its own -- it is a placement of a content item -- so it borrows the
 * item's, which is what a learner sees on the screen anyway.
 */
@Component
public class ReferenceTitles {

    private final JdbcTemplate jdbc;

    public ReferenceTitles(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The title, or a plain fallback: a missing name must not stop the mail. */
    public String of(String tenantId, ReferenceKind kind, UUID referenceId) {
        String sql = switch (kind) {
            case COURSE -> "SELECT title FROM course WHERE tenant_id = ? AND id = ?";
            case MODULE -> "SELECT title FROM course_module WHERE tenant_id = ? AND id = ?";
            case NODE -> """
                SELECT i.title FROM course_node n JOIN content_item i ON i.id = n.content_item_id
                 WHERE n.tenant_id = ? AND n.id = ?
                """;
            case CONTENT_ITEM -> "SELECT title FROM content_item WHERE tenant_id = ? AND id = ?";
        };
        String title = jdbc.query(sql, rows -> rows.next() ? rows.getString(1) : null,
            tenantId, referenceId);
        return title == null || title.isBlank() ? "your assigned training" : title;
    }
}
