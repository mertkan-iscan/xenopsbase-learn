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
        return template.replace("{tenant}", tenantId);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
