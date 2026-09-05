package com.xenopsoftware.learn.catalog.content;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads of {@link ContentItem}. Every one of them is tenant-filtered by the discriminator (T-1.1)
 * rather than by a clause written here, so a query that forgets the tenant is a query that was
 * already filtered.
 */
public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {

    /**
     * Search, with every filter optional and a null meaning "do not filter".
     *
     * <p>One query rather than a handful of derived methods, because the screen offers the
     * filters in combination and the alternative is a method per combination -- eight of them,
     * seven of which are wrong the day a ninth filter appears.
     *
     * <p>{@code LOWER(...) LIKE} rather than {@code ILIKE} keeps this JPQL rather than native,
     * and the trigram index answers it either way.
     *
     * <p><b>{@code :text} is EMPTY for "do not filter", never null</b>, and that is not a style
     * choice. A null bound to a parameter that only ever appears beside {@code LIKE} gives
     * Postgres nothing to infer a type from, so it guesses {@code bytea} and the query dies with
     * {@code operator does not exist: text ~~ bytea} — at runtime, on the search path, naming a
     * type nothing in this file mentions. The {@code IS NULL} guard does not help, because the
     * parameter still has to be typed before the guard can be evaluated. {@code :type} is
     * comparably null-able and safe, because {@code =} against a text column resolves the type.
     *
     * <p>{@code COALESCE} on the description for a plainer reason: a null description makes the
     * whole {@code LIKE} null rather than false, which silently drops rows whose TITLE matched.
     */
    @Query("""
        SELECT i FROM ContentItem i
         WHERE (:type IS NULL OR i.type = :type)
           AND (:state IS NULL OR i.state = :state)
           AND (:text = '' OR LOWER(i.title) LIKE CONCAT('%', :text, '%')
                           OR LOWER(COALESCE(i.description, '')) LIKE CONCAT('%', :text, '%'))
         ORDER BY i.updatedAt DESC
        """)
    List<ContentItem> search(@Param("type") String type,
                             @Param("state") ContentState state,
                             @Param("text") String text);
}
