package com.xenopsoftware.learn.identity.authz;

import java.util.function.Supplier;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The seam between resolving a permission set and remembering one (T-2.5).
 *
 * <p>One method, and it takes the database resolution as a supplier rather than returning an
 * {@code Optional} the caller must handle: <b>every failure mode of a cache ends in "resolve it
 * from the database"</b>, and a port shaped that way cannot express the alternative. A cache that
 * is empty, stopped, wiped, slow or holding an entry this version cannot read all produce the
 * same behaviour, because the supplier is the only source of an answer and the cache is only ever
 * a shortcut to one.
 *
 * <p>There is no {@code evict}. Invalidation is by key: the key carries the tenant's
 * {@link AuthzVersion}, so a grant change orphans every stale entry at once and the writer never
 * touches this. That is what keeps the fourth acceptance criterion structurally true rather than
 * carefully maintained — an eviction that cannot happen cannot fail a committed write.
 */
public interface CachedPermissions {

    /**
     * The caller's resolved grants, from the cache if it can be trusted and from
     * {@code fromDatabase} otherwise.
     */
    GrantedPermissions resolve(Jwt caller, Supplier<GrantedPermissions> fromDatabase);
}
