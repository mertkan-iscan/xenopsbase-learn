package com.xenopsoftware.learn.catalog.structure;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A module: a titled group of nodes inside a course, with a place in the order (T-5.2).
 *
 * <p>The course is held as an id rather than a {@code @ManyToOne}. An object reference would make
 * every module read drag its course in, and the reads this table serves are "the modules of course
 * X" — where the course is already known and loading it again is a query nobody asked for.
 */
@Entity
@Table(name = "course_module")
public class CourseModule extends TenantOwned {

    @Id
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(nullable = false, length = 512)
    private String title;

    /** Rational, so placing one between two others is a single-row write. See {@link Ordinals}. */
    @Column(nullable = false)
    private BigDecimal ordinal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CourseModule() {
        // Hibernate.
    }

    public static CourseModule in(UUID courseId, String title, BigDecimal ordinal) {
        CourseModule module = new CourseModule();
        module.id = UUID.randomUUID();
        module.courseId = courseId;
        module.ordinal = ordinal;
        module.createdAt = Instant.now();
        module.rename(title);
        return module;
    }

    public void rename(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("A module needs a title");
        }
        this.title = newTitle.strip();
        this.updatedAt = Instant.now();
    }

    public void moveTo(BigDecimal newOrdinal) {
        this.ordinal = newOrdinal;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getOrdinal() {
        return ordinal;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
