package com.xenopsoftware.learn.identity.sso;

import java.util.Hashtable;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A TXT record on the domain itself (T-1.8).
 *
 * <p>DNS because publishing a record on a domain is the thing only its owner can do, and because
 * every customer already knows how. The record is looked up at {@code _xenopslearn-verify.<domain>}
 * rather than at the apex: an apex TXT set is shared with SPF, DMARC and every SaaS vendor the
 * customer has ever verified with, and appending to it is where a careless change removes
 * somebody's mail.
 *
 * <p><b>The default, and deliberately so.</b> A verification that trusts the claimant is not a
 * verification, so the safe implementation is the one that runs when nobody configured anything.
 * This is a departure from how {@code streaming} picks its media provider — there the fake is the
 * default because a missing video account is an inconvenience, and here a missing check is a way
 * into somebody else's company.
 *
 * <p>The JDK's own DNS provider, so this brings no dependency. It answers over UDP with the
 * resolver the host is configured with, which is the same resolver an operator would use to check
 * the record by hand.
 */
@Component
@ConditionalOnProperty(name = "identity.sso.domain-verification", havingValue = "dns",
    matchIfMissing = true)
public class DnsDomainOwnership implements DomainOwnership {

    /** The subdomain the record lives on. Prefixed with an underscore, as verification records are. */
    public static final String RECORD = "_xenopslearn-verify";

    private static final Logger LOG = LoggerFactory.getLogger(DnsDomainOwnership.class);

    @Override
    public boolean proves(String domain, String token) {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        // Bounded, because this runs inside a request an administrator is waiting on and a
        // nameserver that never answers must not hold the thread until a proxy gives up.
        environment.put("com.sun.jndi.dns.timeout.initial", "2000");
        environment.put("com.sun.jndi.dns.timeout.retries", "2");
        InitialDirContext dns = null;
        try {
            dns = new InitialDirContext(environment);
            Attributes attributes = dns.getAttributes(RECORD + "." + domain, new String[] {"TXT"});
            Attribute txt = attributes.get("TXT");
            if (txt == null) {
                return false;
            }
            for (int i = 0; i < txt.size(); i++) {
                // Quoted by most resolvers, unquoted by some; a record that differs only by the
                // quotes a resolver added is the same record.
                String value = String.valueOf(txt.get(i)).trim().replaceAll("^\"|\"$", "");
                if (token.equals(value)) {
                    return true;
                }
            }
            return false;
        } catch (NamingException e) {
            // NXDOMAIN, no TXT, or a resolver that could not be reached. All of them mean "not
            // proved right now", and none of them is an error the administrator can act on
            // differently -- so this answers false and says why in the log rather than turning a
            // missing record into a 500.
            LOG.info("No verification record for {}.{}: {}", RECORD, domain, e.toString());
            return false;
        } finally {
            if (dns != null) {
                try {
                    dns.close();
                } catch (NamingException ignored) {
                    // Closing a context that already failed has nothing to report.
                }
            }
        }
    }
}
