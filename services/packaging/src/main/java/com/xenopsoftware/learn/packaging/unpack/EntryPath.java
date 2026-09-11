package com.xenopsoftware.learn.packaging.unpack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Turning a name an attacker chose into a path that cannot leave the package root (ADR-0105).
 *
 * <p><b>The check is on the NORMALISED result, never on the string.</b> That is the sentence in
 * the ADR and it is the whole reason this class exists rather than a {@code contains("..")} at the
 * call site: {@code a/../../b} contains no leading {@code ..} and escapes anyway, and
 * {@code a/./../..} escapes without containing {@code ../} at all. Only walking the segments
 * answers the question.
 *
 * <p>Every refusal below has been a real CVE in somebody's unzip code. They are listed separately
 * rather than folded into one regular expression because a reader has to be able to check that
 * each case is handled, and because the failure message names which one fired.
 */
public final class EntryPath {

    private EntryPath() {}

    /**
     * The path this entry may be stored at, relative to the package root.
     *
     * @return the normalised path, or null when there is nothing to store — a directory entry, or
     *         a name that normalises away to the root itself. Directories are not objects in
     *         object storage, and materialising one is the other classic way an unzip escapes its
     *         root
     * @throws PackageRejected when the entry could reach outside the root, or is not a plain file
     */
    public static String normalise(String rawName, int maxLength) {
        if (rawName == null || rawName.isBlank()) {
            throw new PackageRejected("The archive contains an entry with no name.");
        }
        if (rawName.length() > maxLength) {
            throw new PackageRejected("The archive contains a path longer than " + maxLength
                + " characters: \"" + rawName.substring(0, Math.min(80, rawName.length())) + "…\"");
        }
        // A NUL in a path is never a filename. It is an attempt at a truncation bug in something
        // downstream that is written in C -- object storage, a virus scanner, a CDN.
        if (rawName.indexOf('\0') >= 0) {
            throw new PackageRejected("The archive contains a path with a NUL byte in it.");
        }

        // BACKSLASHES BECOME SEPARATORS BEFORE ANYTHING ELSE. A ZIP written on Windows can carry
        // `dir\file`, which is one segment to a naive splitter and two to Windows -- so
        // `..\..\x` would pass a check that only ever split on `/`.
        String name = rawName.replace('\\', '/');

        if (name.startsWith("/")) {
            throw new PackageRejected("The archive contains an absolute path: \"" + rawName + "\"");
        }
        // `C:/x` and `\\server\share\x` (already de-slashed above). A drive letter is absolute on
        // the one platform where it means anything, and means nothing anywhere else.
        if (name.length() >= 2 && name.charAt(1) == ':'
                && Character.isLetter(name.charAt(0))) {
            throw new PackageRejected(
                "The archive contains a path with a drive letter: \"" + rawName + "\"");
        }

        // A TRAILING SEPARATOR IS THE ZIP CONVENTION FOR A DIRECTORY, and it is decided here
        // rather than left to `ZipEntry.isDirectory()`. That method only answers yes for an entry
        // the archive marked as one, and archives written by hand or by unusual tooling mark
        // nothing -- so a caller relying on it alone would store `course/` as a zero-byte object
        // called `course`, shadowing the directory every other path is under.
        boolean looksLikeADirectory = name.endsWith("/");

        Deque<String> segments = new ArrayDeque<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                // `a//b` and `a/./b` are the same place as `a/b`. Dropping them is normalisation,
                // not a refusal.
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    // THE ONE THAT MATTERS. Popping an empty stack is the moment the path leaves
                    // the root, and it is the only place that can be detected.
                    throw new PackageRejected(
                        "The archive contains a path that escapes the package root: \""
                        + rawName + "\"");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }

        if (segments.isEmpty() || looksLikeADirectory) {
            return null;
        }
        String normalised = String.join("/", segments);
        // Windows reserved device names, which are still special WITH an extension -- `CON.txt`
        // is the console. This service does not write to a filesystem, so nothing here is at
        // risk; a customer's own tooling downloading a package export is, and this is cheap.
        for (String segment : segments) {
            String stem = segment.contains(".")
                ? segment.substring(0, segment.indexOf('.'))
                : segment;
            if (RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
                throw new PackageRejected(
                    "The archive contains a reserved device name: \"" + rawName + "\"");
            }
        }
        return normalised;
    }

    private static final java.util.Set<String> RESERVED = java.util.Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
}
