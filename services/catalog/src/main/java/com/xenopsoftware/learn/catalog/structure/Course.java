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

    /**
     * Bumped whenever the STRUCTURE changes -- a module or node added, moved, removed, or made
     * required or optional (T-5.5). Deliberately NOT on a rename: a typo fixed in a title is not a
     * different course, and treating it as one would flag every assignment in the tenant as
     * drifted for a change nobody needs to know about.
     */
    @Column(name = "structure_version", nullable = false)
    private long structureVersion;

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
        course.structureVersion = 1;
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

    /** Records that the shape changed, so assignments pinned to the old one can say so. */
    public void structureChanged() {
        this.structureVersion++;
        this.updatedAt = Instant.now();
    }

    public long getStructureVersion() {
        return structureVersion;
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
