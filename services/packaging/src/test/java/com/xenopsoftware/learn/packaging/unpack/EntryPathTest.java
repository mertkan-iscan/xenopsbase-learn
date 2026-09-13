package com.xenopsoftware.learn.packaging.unpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The escapes, one test each (ADR-0105).
 *
 * <p>Written as separate cases rather than one loop with a list, because when this fails somebody
 * needs to know <em>which</em> escape got through, and a parameterised failure that says
 * "case 7" makes them count.
 */
class EntryPathTest {

    @ParameterizedTest(name = "refuses {0}")
    @DisplayName("every shape of path that leaves the package root")
    @ValueSource(strings = {
        "../secrets.txt",
        "a/../../secrets.txt",
        // The one the ADR singles out: no leading `..`, and it still escapes. A
        // `startsWith("..")` check passes this happily.
        "a/./../../secrets.txt",
        "a/b/../../../secrets.txt",
        "/etc/passwd",
        // Backslashes are separators on the platform half these archives are built on. Without
        // the de-slashing this is ONE segment and looks harmless.
        "..\\..\\secrets.txt",
        "a\\..\\..\\secrets.txt",
        "C:/Windows/System32/drivers/etc/hosts",
        "c:\\windows\\win.ini",
    })
    void refusesAnythingThatEscapes(String name) {
        assertThatThrownBy(() -> EntryPath.normalise(name, 512))
            .isInstanceOf(PackageRejected.class)
            // The message has to name the entry: an author with four hundred files needs to know
            // which one their authoring tool wrote badly.
            .hasMessageContaining(name);
    }

    @Test
    @DisplayName("a NUL byte in a path is never a filename")
    void refusesNulBytes() {
        assertThatThrownBy(() -> EntryPath.normalise("index.html\u0000.png", 512))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("NUL");
    }

    @Test
    @DisplayName("a path longer than the limit is refused before it is walked")
    void refusesLongPaths() {
        String tooLong = "a/".repeat(400) + "index.html";
        assertThatThrownBy(() -> EntryPath.normalise(tooLong, 512))
            .isInstanceOf(PackageRejected.class);
    }

    @Test
    @DisplayName("Windows device names are refused even with an extension")
    void refusesReservedNames() {
        assertThatThrownBy(() -> EntryPath.normalise("course/CON.txt", 512))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("reserved");
    }

    @ParameterizedTest(name = "{0} normalises to {1}")
    @DisplayName("ordinary paths survive, tidied")
    @org.junit.jupiter.params.provider.CsvSource({
        "index.html,index.html",
        "course/index.html,course/index.html",
        "course//index.html,course/index.html",
        "course/./index.html,course/index.html",
        // Climbing back inside is not an escape: this is `course/index.html`.
        "course/pages/../index.html,course/index.html",
        "course\\index.html,course/index.html",
        "./index.html,index.html",
    })
    void normalisesRatherThanRefusing(String raw, String expected) {
        assertThat(EntryPath.normalise(raw, 512)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a directory entry has nothing to store")
    void directoriesAreNull() {
        // Not a refusal: object storage has no directories, and creating one is the other classic
        // way an unzip escapes its root.
        assertThat(EntryPath.normalise("course/", 512)).isNull();
        assertThat(EntryPath.normalise("./", 512)).isNull();
    }
}
