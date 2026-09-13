package com.xenopsoftware.learn.packaging.launch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where a package is served from, and where the application lives (ADR-0105).
 *
 * <p><b>The origin is built from configuration and never concatenated from the request.</b> That
 * is the ADR's sentence, and it is the difference between an isolation boundary and a comment: an
 * origin derived from {@code Host} is an origin an attacker sets. Both values here are also what
 * the {@code postMessage} bridge compares {@code event.origin} against — by string equality, not
 * by suffix — so a wrong value fails closed and loudly rather than quietly widening the boundary.
 *
 * @param template     the content origin, with {@code {tenant}} where the company's id goes. One
 *                     origin per tenant is ADR-0105's decision and is not retrofittable: a launch
 *                     URL is embedded in course content and recorded in attempt history, so
 *                     changing the scheme later rewrites data rather than configuration
 * @param appOrigin    the application's own origin, exactly as a browser writes it. The only
 *                     origin the wrapper will post to, and the only one it accepts a message from
 * @param pathPrefix   the path the content origin routes to this service. It exists as a property
 *                     because the value has to match a route in something that is not this
 *                     codebase — the local stack's Caddyfile, a CDN rule in a cluster
 */
@ConfigurationProperties(prefix = "packaging.content-origin")
public record ContentOriginProperties(String template, String appOrigin, String pathPrefix) {

    public ContentOriginProperties {
        template = blankTo(template, "http://{tenant}.localhost:8090");
        appOrigin = blankTo(appOrigin, "http://localhost:5173");
        pathPrefix = blankTo(pathPrefix, "/packages");
    }

    /**
     * The origin this company's packages are served from.
     *
     * <p>The tenant id is substituted rather than interpolated by a caller, so there is exactly one
     * place that knows the scheme. It is also the reason a tenant id has to be host-safe — which
     * it is, being the identifier a company is created with rather than a name somebody typed.
     */
    public String originFor(String tenantId) {
        String origin = template.replace("{tenant}", tenantId);
        refuseReservedLabel(origin);
        return origin;
    }

    /**
     * A hostname whose first label has {@code --} in its third and fourth characters is refused.
     *
     * <p><b>This exists because of the scheme, not in spite of it.</b> ADR-0105's amended origin is
     * {@code <tenant>--usercontent-<env>.<domain>} — one label below the apex, because that is what
     * Cloudflare's Universal SSL covers and anything deeper is a certificate somebody has to buy.
     * The double hyphen is the separator that keeps it to one label.
     *
     * <p>RFC 5891 reserves any label with {@code --} in positions three and four for
     * internationalised domain names ({@code xn--} being the only assigned one). So a two-character
     * company id produces {@code ab--usercontent-dev}, which is a name registrars and resolvers are
     * entitled to refuse and some do — and the failure would arrive as a company whose courses do
     * not load, months after the id was chosen and long past the point where it can be changed.
     *
     * <p>Refused loudly here rather than checked at company creation, because this is the class
     * that knows the scheme. When tenant ids stop being chosen for us this becomes the rule that
     * provisioning has to satisfy, and it is better as a thrown exception than as a sentence in a
     * document.
     */
    private static void refuseReservedLabel(String origin) {
        String withoutScheme = origin.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int end = withoutScheme.indexOf('.');
        String label = end < 0 ? withoutScheme : withoutScheme.substring(0, end);
        if (label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-') {
            throw new IllegalStateException("\"" + label + "\" cannot be a hostname label: RFC 5891"
                + " reserves \"--\" in the third and fourth characters for internationalised domain"
                + " names. A company id of two characters produces one under this origin scheme.");
        }
    }

    /**
     * The Content-Security-Policy every response on the content origin carries.
     *
     * <p><b>Emitted by this service rather than by whatever proxy is in front of it.</b> The local
     * stack's Caddy sets the same header, and for a while that was the only place it existed --
     * which meant the second half of ADR-0105's isolation lived in a development file and would
     * have been silently absent the first time a package was served from a cluster. A control that
     * a later convenience can remove is a control with a shelf life; this one now travels with the
     * thing it protects, and Caddy's copy is defence in depth.
     *
     * <p>{@code unsafe-inline} and {@code unsafe-eval} are permitted because real authoring tools
     * emit both, and a policy that forbids them forbids SCORM. That is survivable precisely because
     * of WHERE this runs: the script it permits has no session, no token and no cookie in reach.
     * {@code connect-src 'self'} is the part doing the work -- package code cannot call out.
     *
     * <p>{@code frame-ancestors} names the application and <b>{@code 'self'}</b>, and the second is
     * not optional. The launch chain is two nested frames -- the application frames the wrapper,
     * the wrapper frames the package -- and the inner one is this origin framing itself, which
     * {@code frame-ancestors} governs exactly as strictly as it governs a stranger. Without
     * {@code 'self'} the wrapper loads, both API objects appear, and the course itself is refused
     * with a blank frame.
     */
    public String contentSecurityPolicy() {
        return "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; "
            + "connect-src 'self'; "
            + "frame-ancestors 'self' " + appOrigin;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
