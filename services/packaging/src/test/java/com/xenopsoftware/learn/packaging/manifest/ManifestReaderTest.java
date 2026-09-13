package com.xenopsoftware.learn.packaging.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.packaging.bundle.PackageKind;
import com.xenopsoftware.learn.packaging.unpack.PackageRejected;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the one thing an archive is allowed to decide (T-4.2, T-4.6).
 *
 * <p>The manifests here are shaped like what real authoring tools emit, including the parts that
 * are annoying: {@code xml:base}, a query string on the launch href, a {@code default}
 * organisation that is not the first one.
 */
class ManifestReaderTest {

    private final ManifestReader reader = new ManifestReader();

    private static Map<String, byte[]> manifest(String name, String xml) {
        return Map.of(name, xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("SCORM 1.2 is recognised by its namespace, not by a version string")
    void readsScorm12() {
        String xml = """
            <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2"
                      xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_rootv1p2">
              <organizations default="ORG">
                <organization identifier="ORG">
                  <title>Fire Safety</title>
                  <item identifier="I1" identifierref="RES"><title>Module 1</title></item>
                </organization>
              </organizations>
              <resources>
                <resource identifier="RES" adlcp:scormtype="sco" href="content/start.html"/>
              </resources>
            </manifest>
            """;

        PackageManifest read = reader.read(PackageKind.SCORM,
            manifest("imsmanifest.xml", xml),
            Set.of("content/start.html", "index.html"));

        assertThat(read.profile()).isEqualTo(PackageManifest.SCORM_12);
        assertThat(read.entryPath()).isEqualTo("content/start.html");
        assertThat(read.title()).isEqualTo("Fire Safety");
    }

    @Test
    @DisplayName("SCORM 2004 gets the 2004 profile, because the wrong API is found and then fails")
    void readsScorm2004() {
        String xml = """
            <manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1"
                      xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3">
              <organizations default="ORG">
                <organization identifier="ORG">
                  <title>Data Protection</title>
                  <item identifier="I1" identifierref="RES"/>
                </organization>
              </organizations>
              <resources>
                <resource identifier="RES" adlcp:scormType="sco" href="index.html"/>
              </resources>
            </manifest>
            """;

        PackageManifest read = reader.read(PackageKind.SCORM,
            manifest("imsmanifest.xml", xml), Set.of("index.html"));

        assertThat(read.profile()).isEqualTo(PackageManifest.SCORM_2004);
        assertThat(read.entryPath()).isEqualTo("index.html");
    }

    @Test
    @DisplayName("xml:base is applied, because ignoring it produces a launch URL that 404s")
    void appliesXmlBase() {
        String xml = """
            <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2"
                      xmlns:xml="http://www.w3.org/XML/1998/namespace" xml:base="course/">
              <organizations default="ORG">
                <organization identifier="ORG"><item identifierref="RES"/></organization>
              </organizations>
              <resources xml:base="pages/">
                <resource identifier="RES" href="start.html"/>
              </resources>
            </manifest>
            """;

        PackageManifest read = reader.read(PackageKind.SCORM,
            manifest("imsmanifest.xml", xml), Set.of("course/pages/start.html"));

        assertThat(read.entryPath()).isEqualTo("course/pages/start.html");
    }

    @Test
    @DisplayName("a query string on the href is part of the launch, not part of the file name")
    void keepsTheQueryString() {
        String xml = """
            <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2">
              <organizations default="ORG">
                <organization identifier="ORG"><item identifierref="RES"/></organization>
              </organizations>
              <resources>
                <resource identifier="RES" href="index.html?mode=normal"/>
              </resources>
            </manifest>
            """;

        PackageManifest read = reader.read(PackageKind.SCORM,
            manifest("imsmanifest.xml", xml), Set.of("index.html"));

        assertThat(read.entryPath()).isEqualTo("index.html?mode=normal");
    }

    @Test
    @DisplayName("a manifest pointing outside the package is refused, and says the manifest did it")
    void refusesTraversalInTheManifest() {
        String xml = """
            <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2">
              <organizations default="ORG">
                <organization identifier="ORG"><item identifierref="RES"/></organization>
              </organizations>
              <resources>
                <resource identifier="RES" href="../../../etc/passwd"/>
              </resources>
            </manifest>
            """;

        assertThatThrownBy(() -> reader.read(PackageKind.SCORM,
            manifest("imsmanifest.xml", xml), Set.of("index.html")))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("points outside the package");
    }

    @Test
    @DisplayName("a manifest pointing at a file that was not extracted is refused at ingest")
    void refusesAMissingEntry() {
        String xml = """
            <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2">
              <organizations default="ORG">
                <organization identifier="ORG"><item identifierref="RES"/></organization>
              </organizations>
              <resources>
                <resource identifier="RES" href="missing.html"/>
              </resources>
            </manifest>
            """;

        // Much kinder here than as a 404 inside somebody's compliance training six months later.
        assertThatThrownBy(() -> reader.read(PackageKind.SCORM,
            manifest("imsmanifest.xml", xml), Set.of("index.html")))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("not in the package");
    }

    @Test
    @DisplayName("XXE is refused: no DOCTYPE means no entity to resolve")
    void refusesEntities() {
        String xml = """
            <?xml version="1.0"?>
            <!DOCTYPE manifest [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2">
              <resources><resource identifier="RES" href="&secret;"/></resources>
            </manifest>
            """;

        assertThatThrownBy(() -> reader.read(PackageKind.SCORM,
            manifest("imsmanifest.xml", xml), Set.of("index.html")))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("not valid XML");
    }

    @Test
    @DisplayName("cmi5 reads the assignable unit's url")
    void readsCmi5() {
        String xml = """
            <courseStructure xmlns="https://w3id.org/xapi/profiles/cmi5/v1/CourseStructure.xsd">
              <course id="http://example.com/course">
                <title><langstring lang="en">Induction</langstring></title>
              </course>
              <au id="http://example.com/au1">
                <title><langstring lang="en">Part one</langstring></title>
                <url>content/index.html</url>
              </au>
            </courseStructure>
            """;

        PackageManifest read = reader.read(PackageKind.CMI5,
            manifest("cmi5.xml", xml), Set.of("content/index.html"));

        assertThat(read.profile()).isEqualTo(PackageManifest.CMI5);
        assertThat(read.entryPath()).isEqualTo("content/index.html");
        assertThat(read.title()).isEqualTo("Part one");
    }

    @Test
    @DisplayName("a cmi5 unit hosted on somebody else's server is refused")
    void refusesExternalAssignableUnits() {
        String xml = """
            <courseStructure>
              <au id="x"><url>https://vendor.example.com/course/</url></au>
            </courseStructure>
            """;

        // Legal cmi5, and refused anyway: it would let a third party change what a learner sees
        // after the customer approved it, with no re-upload and no record.
        assertThatThrownBy(() -> reader.read(PackageKind.CMI5,
            manifest("cmi5.xml", xml), Set.of("index.html")))
            .isInstanceOf(PackageRejected.class)
            .hasMessageContaining("external address");
    }

    @Test
    @DisplayName("an HTML5 bundle opens at its index and gets no SCORM API")
    void readsHtml5() {
        PackageManifest read = reader.read(PackageKind.HTML5, Map.of(),
            Set.of("index.html", "app.js", "styles.css", "media/hero.png"));

        assertThat(read.entryPath()).isEqualTo("index.html");
        // NOT a SCORM profile. A bundle handed an `API` it never asked for would be a lie the
        // discovery walk cannot detect, and its silence would then read as a failure to complete.
        assertThat(read.profile()).isEqualTo(PackageManifest.HTML5);
    }

    @Test
    @DisplayName("one HTML file and no index is unambiguous, so it is accepted")
    void acceptsASinglePage() {
        PackageManifest read = reader.read(PackageKind.HTML5, Map.of(),
            Set.of("course.html", "course.js"));

        assertThat(read.entryPath()).isEqualTo("course.html");
    }

    @Test
    @DisplayName("no index and several pages is genuinely ambiguous, and says the usual cause")
    void refusesAnAmbiguousBundle() {
        assertThatThrownBy(() -> reader.read(PackageKind.HTML5, Map.of(),
            Set.of("page-1.html", "page-2.html", "page-3.html")))
            .isInstanceOf(PackageRejected.class)
            // The single most common upload mistake, named rather than left to be guessed at.
            .hasMessageContaining("zip the folder's CONTENTS");
    }

    @Test
    @DisplayName("slides need no manifest and get no runtime")
    void readsSlides() {
        PackageManifest read = reader.read(PackageKind.SLIDES, Map.of(),
            Set.of("page-02.png", "page-01.png", "page-03.png"));

        assertThat(read.entryPath()).isEqualTo("page-01.png");
        assertThat(read.profile()).isNull();
    }

    @Test
    @DisplayName("a SCORM package with no manifest is told exactly what is usually wrong")
    void explainsAMissingManifest() {
        assertThatThrownBy(() ->
            reader.read(PackageKind.SCORM, Map.of("course/imsmanifest.xml", new byte[0]),
                Set.of("course/index.html")))
            .isInstanceOf(PackageRejected.class)
            // The single most common upload mistake: zipping the folder instead of its contents.
            .hasMessageContaining("zip the folder's CONTENTS");
    }
}
