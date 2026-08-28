package com.xenopsoftware.learn.identity.authz;

/**
 * Which side of the platform a permission belongs to. The token's {@code side} claim is the
 * coarse pre-filter (ADR-0103): a TENANT permission can never be held by platform staff and vice
 * versa, before any role is even consulted.
 */
public enum PermissionSide {
    TENANT,
    PLATFORM
}
