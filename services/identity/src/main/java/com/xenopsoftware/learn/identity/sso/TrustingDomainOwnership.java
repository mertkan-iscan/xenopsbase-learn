package com.xenopsoftware.learn.identity.sso;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Verification that verifies nothing, for the local stack and for tests (T-1.8).
 *
 * <p>The local stack's companies live at {@code acme.test} and {@code globex.test}, which can
 * never publish a DNS record, so without this the SSO path could not be exercised locally at all.
 *
 * <p><b>It is never the default.</b> {@link DnsDomainOwnership} is what runs when nobody chose,
 * and turning this on takes an explicit {@code identity.sso.domain-verification=trusting}. That
 * is the opposite of how {@code streaming} selects its fake media provider, on purpose: an
 * installation that accidentally runs without a video account uploads nothing, and an
 * installation that accidentally runs without domain verification hands one customer another
 * customer's sign-ins.
 */
@Component
@ConditionalOnProperty(name = "identity.sso.domain-verification", havingValue = "trusting")
public class TrustingDomainOwnership implements DomainOwnership {

    private static final Logger LOG = LoggerFactory.getLogger(TrustingDomainOwnership.class);

    @PostConstruct
    void warnLoudly() {
        // WARN rather than INFO, for FakeMediaProvider's reason: a green run against this must
        // never be read as evidence that domain verification works.
        LOG.warn("DOMAIN VERIFICATION IS DISABLED (identity.sso.domain-verification=trusting). "
            + "Any company can claim any email domain, including one it does not own, and "
            + "discovery will route that domain's sign-ins to them. Local stack only.");
    }

    @Override
    public boolean proves(String domain, String token) {
        LOG.warn("Accepting an unproved claim on {} because verification is disabled", domain);
        return true;
    }
}
