package com.xenopsoftware.learn.identity.sso;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The real verification's failure behaviour (T-1.8). No Spring and no container: what matters
 * here is what happens when DNS says nothing, and that must not depend on either.
 *
 * <p>What is deliberately NOT asserted is a successful lookup. Proving that would mean publishing
 * a TXT record on a domain this repository controls and having every build depend on it resolving
 * — a test that fails on an aeroplane and during a DNS incident, and passes for the wrong reason
 * once somebody caches it. The success path is the one line that compares two strings; the part
 * worth pinning is that everything else answers "not proved" instead of throwing.
 */
class DnsDomainOwnershipTest {

    private final DnsDomainOwnership ownership = new DnsDomainOwnership();

    @Test
    void aDomainThatCannotExistIsNotProved() {
        // .invalid is reserved by RFC 2606 precisely so it can never resolve, so this is
        // NXDOMAIN by definition rather than by luck. It is also what a resolver-less build
        // machine produces for everything, which is why the assertion is the same either way.
        assertThat(ownership.proves("nothing-here.invalid", "xenopslearn-verify=whatever"))
            .isFalse();
    }

    @Test
    void aLookupThatFailsIsAnAnswerAndNotAnException() {
        // An administrator clicking "verify" before publishing the record is the ordinary case,
        // not an error condition. A 500 here would read as our fault and tell them nothing they
        // could act on.
        assertThat(ownership.proves("", "token")).isFalse();
        assertThat(ownership.proves("..", "token")).isFalse();
    }

    @Test
    void theRecordIsOnItsOwnSubdomainRatherThanTheApex() {
        // An apex TXT set is shared with SPF, DMARC and every vendor the customer has verified
        // with, and appending to it by hand is where somebody's mail stops working.
        assertThat(DnsDomainOwnership.RECORD).startsWith("_").doesNotContain(".");
    }
}
