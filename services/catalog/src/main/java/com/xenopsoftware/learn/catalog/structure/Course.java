package com.xenopsoftware.learn.catalog.structure;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A course: a title and an ordered set of modules (T-5.2).
 *
 * <p><b>No lifecycle state, deliberately.</b> A course's publishing story is T-5.7's, and it is
 * not a content item's: publishing a new VERSION must not rewrite the history of learners who
 * finished the old one, which is a versioning design rather than a column. A DRAFT/PUBLISHED
 * field added here now is the thing that task would have to undo.
 */
@Entity
@Table(name = "course")
public class Course extends TenantOwned {

    @Id
    private UUID id;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Course() {
        // Hibernate.
    }

    public static Course named(String title, String description) {
        Course course = new Course();
        course.id = UUID.randomUUID();
        course.createdAt = Instant.now();
        course.rename(title, description);
        return course;
    }

    public void rename(String newTitle, String newDescription) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("A course needs a title");
        }
        this.title = newTitle.strip();
        this.description = newDescription == null || newDescription.isBlank()
            ? null : newDescription.strip();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
