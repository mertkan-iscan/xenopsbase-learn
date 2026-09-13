package com.xenopsoftware.learn.packaging.bundle;

import java.util.Locale;

/**
 * What kind of thing was uploaded (T-4.2, T-4.6, T-4.7).
 *
 * <p><b>The codes are catalog's, not this service's.</b> {@code scorm}, {@code cmi5},
 * {@code html5} and {@code slides} are four of the six content types catalog publishes
 * ({@code BuiltInContentTypes}), and an author picks one there before they get here. Inventing a
 * parallel vocabulary — {@code SCORM_PACKAGE}, {@code DOCUMENT} — would mean a translation table
 * between two services that are describing the same thing, and translation tables are where the
 * sixth type gets forgotten.
 *
 * <p>{@code video} is deliberately not here. Video bytes never touch this service or any other of
 * ours: they go straight to the delivery provider's own upload target (ADR-0101, T-3.2). A kind
 * for it would be an invitation to route them through here "just for small files".
 */
public enum PackageKind {

    /** A SCORM 1.2 or 2004 archive. Which one is decided by the manifest, not by the uploader. */
    SCORM("scorm"),

    /** cmi5: the same archive shape, a different runtime and a different statement store. */
    CMI5("cmi5"),

    /**
     * A web bundle with no runtime standard behind it (T-4.8).
     *
     * <p><b>The same archive, the same ingest, one difference downstream.</b> Everything on
     * ADR-0105's list applies to an HTML5 bundle exactly as it applies to a SCORM one — the danger
     * was never the manifest, it was the uploaded JavaScript, and both are that. What differs is
     * that SCORM and cmi5 have a contract for telling us the learner finished and this has none.
     *
     * <p>So its entry point is found rather than declared ({@code index.html}), and its completion
     * is whatever the bundle chooses to say through the small API the wrapper exposes, plus the
     * fact that it was opened. Modelling it as SCORM would mean serving a fake SCORM API to
     * content that never asked for one, and then reading its silence as a failure to complete.
     */
    HTML5("html5"),

    /**
     * Slides or a document.
     *
     * <p>Accepted as an archive of already-rasterised pages rather than as a {@code .pptx} this
     * service converts. Rendering an arbitrary Office document means running an office suite on
     * uploaded input, which is a much larger attack surface than unzipping one — and a decision
     * that deserves its own ADR rather than arriving inside a content type.
     */
    SLIDES("slides");

    private final String code;

    PackageKind(String code) {
        this.code = code;
    }

    /** The code catalog uses, and the one on the wire. */
    public String code() {
        return code;
    }

    public static PackageKind of(String code) {
        if (code != null) {
            for (PackageKind kind : values()) {
                if (kind.code.equals(code.strip().toLowerCase(Locale.ROOT))) {
                    return kind;
                }
            }
        }
        throw new IllegalArgumentException(
            "\"" + code + "\" is not a kind of package this platform ingests. Use scorm, cmi5, "
            + "html5 or slides. Video does not come through here: it is uploaded straight to the "
            + "delivery provider (ADR-0101).");
    }
}
