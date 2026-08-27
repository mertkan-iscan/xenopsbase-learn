package com.xenopsoftware.learn.identity.user;

/**
 * The lifecycle states of an {@link AppUser}. First-login provisioning creates {@code ACTIVE};
 * the transitions — inviting, deactivating, and what each may still do — are T-1.9's, declared
 * here so the schema and this enum change once.
 */
public enum UserStatus {
    ACTIVE,
    INVITED,
    DEACTIVATED
}
