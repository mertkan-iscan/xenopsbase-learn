package com.xenopsoftware.learn.packaging.manifest;

/**
 * What the archive said about itself, after it has been checked (T-4.2, T-4.6).
 *
 * @param title     the package's own name, or null when it did not give one. Offered to the author
 *                  as a suggestion for the content item's title, never imposed: a course called
 *                  "Untitled Course 3" by an authoring tool is not what a customer wants in their
 *                  catalogue
 * @param entryPath the file a launch opens, relative to the package root — normalised, and proven
 *                  to be a file that was actually extracted
 * @param profile   which runtime the wrapper has to present: {@code scorm-1.2},
 *                  {@code scorm-2004}, {@code cmi5}, {@code html5} — which gets the small
 *                  self-reporting API and no SCORM one — or null for slides, which get neither
 */
public record PackageManifest(String title, String entryPath, String profile) {

    public static final String SCORM_12 = "scorm-1.2";
    public static final String SCORM_2004 = "scorm-2004";
    public static final String CMI5 = "cmi5";

    /** A web bundle. No standard runtime; the wrapper offers `window.xenopslearn` (T-4.8). */
    public static final String HTML5 = "html5";
}
