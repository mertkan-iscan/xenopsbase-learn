package com.xenopsoftware.learn.packaging.launch;

import com.xenopsoftware.learn.packaging.bundle.ContentPackage;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The two URLs a package has, and the one place they are written (ADR-0105, T-4.3).
 *
 * <p>Both live on the tenant's content origin, which is a different origin to the application and
 * holds no cookie, no token and nothing worth stealing. That is the entire security property, and
 * it is a property of these strings — so they are built here, from configuration, and nowhere
 * else.
 *
 * <h2>Why a launch URL is a capability and that is not a lapse</h2>
 *
 * <p>Nothing authenticates a request to the content origin, because presenting a credential to it
 * is exactly what ADR-0105 forbids. So anybody holding one of these URLs can read that package's
 * files. The id in the middle is a version-4 UUID, which is the unguessable part; what it protects
 * is a customer's own course material, against people who do not have the link.
 *
 * <p>It is worth being plain that this is weaker than the rest of the platform's authorization
 * and deliberately so: the alternative is a session on the origin where third-party JavaScript
 * runs, and that trade — a leaked course, versus a leaked tenant — is the one the ADR makes.
 */
@Component
public class LaunchUrls {

    private final ContentOriginProperties properties;

    public LaunchUrls(ContentOriginProperties properties) {
        this.properties = properties;
    }

    /**
     * What the application puts in an iframe.
     *
     * <p>Never the package's own entry file directly. The wrapper is what implements the SCORM
     * API the package's discovery walk will look for on {@code window.parent}, and it is what
     * forwards to the application over {@code postMessage} — a package pointed straight at its
     * own entry finds no API and stops at the first {@code Initialize}.
     */
    public String launchUrl(String tenantId, UUID packageId) {
        return base(tenantId, packageId) + "/launch";
    }

    /** Where the package's own files are, and therefore what its relative links resolve against. */
    public String filesBase(String tenantId, UUID packageId) {
        return base(tenantId, packageId) + "/files/";
    }

    /** The full URL of the file a launch opens. */
    public String entryUrl(String tenantId, ContentPackage stored) {
        return stored.getEntryPath() == null
            ? null
            : filesBase(tenantId, stored.getId()) + stored.getEntryPath();
    }

    /** The origin itself, which the application needs as a {@code postMessage} target. */
    public String origin(String tenantId) {
        return properties.originFor(tenantId);
    }

    private String base(String tenantId, UUID packageId) {
        return properties.originFor(tenantId) + properties.pathPrefix() + "/" + tenantId + "/"
            + packageId;
    }
}
