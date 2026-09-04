package com.xenopsoftware.learn.identity.sso;

/**
 * Proof that a company owns an email domain (T-1.8).
 *
 * <p>Discovery routes a learner to a provider by the domain of the address they typed, so an
 * unproved domain is a way to be handed other people's sign-ins. "Proved rather than typed" is
 * the acceptance criterion and this is the seam that keeps it honest.
 */
public interface DomainOwnership {

    /** Whether the domain currently publishes this verification token. */
    boolean proves(String domain, String token);
}
