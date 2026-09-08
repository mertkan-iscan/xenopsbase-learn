package com.xenopsoftware.learn.identity.authz;

/**
 * The permission catalog (T-2.1, ADR-0103). This enum IS the catalog; the {@code permission}
 * table is its projection, written by {@link PermissionCatalogSeeder} and nobody else.
 *
 * <p>Code, not data, because a permission is only real if some code path checks it. If the
 * catalog were an admin screen, someone could create {@code report:export}, grant it, and be
 * certain they had restricted something — while every request still passed. Here a permission
 * exists exactly when a constant exists, and {@code CatalogCoverageTest} holds both directions
 * of that honest: every secured endpoint names a catalog entry, and every catalog entry is
 * either checked somewhere or explicitly listed as not-yet-enforced with the task that will
 * enforce it.
 *
 * <p>Codes are {@code resource:action}, stable forever once shipped — roles reference them as
 * rows. Retiring a constant orphans its row (visible, grantable-no-longer, never deleted);
 * renaming one is a retirement plus a new code, and the migration of existing roles that
 * implies. Add constants accordingly.
 */
public enum Permission {

    /** Resolve another member's display details — the read behind every people-picker. */
    USER_READ("user:read", PermissionSide.TENANT, PermissionScope.GROUP),

    /** Invite, deactivate, and edit members (T-1.9). */
    USER_MANAGE("user:manage", PermissionSide.TENANT, PermissionScope.GROUP),

    /** See the group tree, as far as scope allows (T-1.3). */
    GROUP_READ("group:read", PermissionSide.TENANT, PermissionScope.GROUP),

    /** Create, move and delete groups within scope (T-1.3). */
    GROUP_MANAGE("group:manage", PermissionSide.TENANT, PermissionScope.GROUP),

    /** See the tenant's roles and what they contain (T-2.2). */
    ROLE_READ("role:read", PermissionSide.TENANT, PermissionScope.TENANT),

    /** Build and edit roles by selecting permissions (T-2.2). TENANT floor: a role's edit
     * affects everyone holding it, so no narrower scope can own the edit. */
    ROLE_MANAGE("role:manage", PermissionSide.TENANT, PermissionScope.TENANT),

    /** Assign and revoke roles within scope (T-2.3), gated by the no-escalation rule (T-2.6). */
    ROLE_ASSIGN("role:assign", PermissionSide.TENANT, PermissionScope.GROUP),

    /**
     * Watch, read or open a piece of content the learner has been assigned (T-3.4).
     *
     * <p>The first catalog-side entry, and the shape of every one that follows. Holding it
     * means "this is a person who consumes content"; it does NOT mean any particular course,
     * because which content reaches whom is an assignment (T-5.5) and whether it reaches them
     * yet is a gate (T-5.3). Those are separate checks in the one place that decides playback,
     * and collapsing them into a permission is how a permission ends up meaning nothing.
     *
     * <p>GROUP floor rather than COURSE, for the same reason: the useful grant is "everyone in
     * this department may watch what they are given", and per-course entitlement is not a role
     * scope. It is checked by streaming, not by any endpoint here.
     */
    CONTENT_VIEW("content:view", PermissionSide.TENANT, PermissionScope.GROUP),

    /**
     * See a question bank and what is in it (T-6.1).
     *
     * <p>RESOURCE floor, which is the first use of it: a grant may point at one bank. That is the
     * whole reason banks exist as rows — "the whole company" was the only other boundary
     * available, and an author who writes safety questions has no business reading the compliance
     * exam.
     *
     * <p>Reading the platform's SHARED library needs none of this. A shared bank is offered to
     * every tenant by definition, so gating the offer behind a per-tenant grant would mean a
     * customer had to be given permission to see something we are giving them.
     */
    BANK_READ("bank:read", PermissionSide.TENANT, PermissionScope.RESOURCE),

    /**
     * Write questions in a bank (T-6.1, T-6.2).
     *
     * <p>The authoring grant, and the one the boundary exists for. Separate from
     * {@link #BANK_MANAGE} because writing questions and deciding a bank's existence are different
     * jobs held by different people: a subject-matter expert authors, an administrator organises.
     * Folded together, every author would also be able to delete the bank they work in.
     *
     * <p><b>Not in the seeded {@code author} role, deliberately.</b> A bank-scoped grant is a
     * CUSTOM role holding only the RESOURCE-floored bank permissions (T-2.2), assigned at BANK
     * scope. The seeded role could never carry one: it holds {@code content:view}, which is
     * GROUP-floored, and {@code AssignmentService} refuses an assignment whose scope cannot cover
     * every permission the role contains. Seeding these into it would therefore have granted
     * authoring over every bank in the company — the opposite of what T-6.1 exists for — while
     * still not making the narrow grant expressible.
     */
    BANK_AUTHOR("bank:author", PermissionSide.TENANT, PermissionScope.RESOURCE),

    /**
     * Create, rename and organise banks, and copy from the shared library (T-6.1).
     *
     * <p>GROUP floor rather than RESOURCE, and the asymmetry is deliberate: creating a bank is not
     * an act on a bank, because the bank does not exist yet. A grant scoped to one bank cannot
     * authorise making another one, so the narrowest scope that can own this is a wider one.
     */
    BANK_MANAGE("bank:manage", PermissionSide.TENANT, PermissionScope.GROUP),

    /** Create a company (T-1.5). */
    TENANT_PROVISION("tenant:provision", PermissionSide.PLATFORM, PermissionScope.PLATFORM),

    /** Suspend and reinstate a company (T-1.4). */
    TENANT_SUSPEND("tenant:suspend", PermissionSide.PLATFORM, PermissionScope.PLATFORM),

    /** Act as a tenant user, always visibly afterwards, and read-only (T-2.8). */
    SUPPORT_IMPERSONATE("support:impersonate", PermissionSide.PLATFORM, PermissionScope.PLATFORM),

    /**
     * Open an impersonation session that may WRITE (T-2.8).
     *
     * <p>A second permission rather than a flag on the first, because "read-only by default"
     * only means something if turning it off is a decision somebody made about a person. Folded
     * into {@code support:impersonate} it would be a checkbox on a request body — held by
     * everyone who can impersonate at all, and the audit trail would show a choice where there
     * had never been a grant.
     *
     * <p>It is not sufficient on its own: a writable session needs both, so revoking this one
     * leaves the engineer able to look and not to touch, which is the state most support work
     * should be in.
     */
    SUPPORT_IMPERSONATE_WRITE("support:impersonate_write", PermissionSide.PLATFORM, PermissionScope.PLATFORM),

    /**
     * See when our staff entered this company's account, who did, and why (T-2.8).
     *
     * <p>A TENANT permission, which is the point: the record of what we did belongs to the
     * customer. Seeded into the company-administrator template so it is visible by default —
     * a visibility a customer has to ask us to enable is not visibility.
     */
    IMPERSONATION_READ("impersonation:read", PermissionSide.TENANT, PermissionScope.TENANT),

    /**
     * Configure this company's own identity provider and the email domains that route to it
     * (T-1.8).
     *
     * <p>TENANT floor, and not GROUP: a provider decides how everyone in the company signs in,
     * so there is no narrower scope that could own the decision. It is also the sharpest
     * permission a customer can hold — whoever has it decides which credential opens every
     * account in the company — which is why it is its own entry rather than part of
     * {@code user:manage}.
     */
    SSO_MANAGE("sso:manage", PermissionSide.TENANT, PermissionScope.TENANT);

    private static final java.util.Map<String, Permission> BY_CODE;

    static {
        java.util.Map<String, Permission> byCode = new java.util.HashMap<>();
        for (Permission permission : values()) {
            byCode.put(permission.code, permission);
        }
        BY_CODE = java.util.Map.copyOf(byCode);
    }

    /** The catalog entry for a code, or empty — the caller decides how loudly to fail. */
    public static java.util.Optional<Permission> byCode(String code) {
        return java.util.Optional.ofNullable(BY_CODE.get(code));
    }

    private final String code;
    private final String resource;
    private final String action;
    private final PermissionSide side;
    private final PermissionScope minScope;

    Permission(String code, PermissionSide side, PermissionScope minScope) {
        int colon = code.indexOf(':');
        if (colon <= 0 || colon == code.length() - 1) {
            // Fails enum initialization, which fails everything -- the loudest available way
            // to reject a malformed code before it becomes a row roles reference.
            throw new IllegalArgumentException("Permission code must be resource:action, got " + code);
        }
        this.code = code;
        this.resource = code.substring(0, colon);
        this.action = code.substring(colon + 1);
        this.side = side;
        this.minScope = minScope;
    }

    public String code() {
        return code;
    }

    public String resource() {
        return resource;
    }

    public String action() {
        return action;
    }

    public PermissionSide side() {
        return side;
    }

    public PermissionScope minScope() {
        return minScope;
    }
}
