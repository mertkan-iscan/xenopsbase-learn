package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.identity.group.GroupHierarchy;
import com.xenopsoftware.learn.identity.user.AppUser;
import com.xenopsoftware.learn.identity.user.AppUserRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * "Which groups can this person exercise this permission over" — implemented once (T-2.3's
 * second criterion), so authorization, assignment targeting and reporting cannot drift into
 * three subtly different answers to the same question.
 *
 * <p>A GROUP-scoped grant reaches that group's whole subtree, which is the same containment rule
 * assignments themselves follow and the same query {@link GroupHierarchy} already implements
 * once. A COURSE-scoped grant reaches no groups at all — it is a grant over content, and
 * answering "which groups" with the course's id would be a category error that reads as a
 * working answer.
 */
@Component
public class ScopeResolver {

    private final RequestPermissions requestPermissions;
    private final GroupHierarchy hierarchy;
    private final AppUserRepository users;

    public ScopeResolver(RequestPermissions requestPermissions, GroupHierarchy hierarchy,
            AppUserRepository users) {
        this.requestPermissions = requestPermissions;
        this.hierarchy = hierarchy;
        this.users = users;
    }

    /** The caller's reach for one permission. */
    public Reach reachFor(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return Reach.nothing();
        }
        Set<ScopeGrant> grants = requestPermissions.forCaller(token.getToken()).scopesFor(permission);
        if (grants.isEmpty()) {
            return Reach.nothing();
        }
        if (grants.stream().anyMatch(grant -> grant.type().isUnbounded())) {
            // The widest grant wins outright, and short-circuits: no subtree needs walking to
            // answer a question whose answer is "all of it".
            return new Reach(true, Set.of(), Set.of());
        }
        Set<UUID> groups = new LinkedHashSet<>();
        Set<UUID> courses = new LinkedHashSet<>();
        for (ScopeGrant grant : grants) {
            switch (grant.type()) {
                case GROUP -> groups.addAll(hierarchy.subtreeIds(grant.targetId()));
                case COURSE -> courses.add(grant.targetId());
                default -> throw new IllegalStateException("Unbounded scope already handled");
            }
        }
        return new Reach(false, Set.copyOf(groups), Set.copyOf(courses));
    }

    /** Whether the caller may exercise this permission over this group. */
    public boolean canReachGroup(Permission permission, UUID groupId) {
        return reachFor(permission).includesGroup(groupId);
    }

    /**
     * The people the caller may exercise this permission over.
     *
     * <p>Tenant-wide reach means every person in the company — including the ones in no group at
     * all, which is why this reads {@code app_user} rather than unioning the tree. Prefer
     * {@link Reach#wholeTenant()} where a boolean will do: materialising a five-thousand-person
     * list to answer "may they?" is work nobody asked for.
     */
    public Set<UUID> reachableUsers(Permission permission) {
        Reach reach = reachFor(permission);
        if (reach.wholeTenant()) {
            return users.findAll().stream().map(AppUser::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return hierarchy.reachableUserIds(reach.groupIds());
    }
}
