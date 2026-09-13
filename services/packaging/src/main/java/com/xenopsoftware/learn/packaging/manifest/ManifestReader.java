package com.xenopsoftware.learn.packaging.manifest;

import com.xenopsoftware.learn.packaging.bundle.PackageKind;
import com.xenopsoftware.learn.packaging.unpack.EntryPath;
import com.xenopsoftware.learn.packaging.unpack.PackageRejected;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Which file a launch opens, and what the package calls itself (T-4.2, T-4.6, T-4.7).
 *
 * <h2>The one thing an archive is allowed to decide</h2>
 *
 * <p>Everything else about a package is ours: its id, its state, the origin it is served from, the
 * type each file is served as. The entry point is the exception — only the manifest knows which of
 * four hundred HTML files is the course — and that makes this class the place where a string an
 * attacker wrote becomes part of a URL.
 *
 * <p>So the href is put through the same normalisation every extracted entry went through, and
 * then checked against the set of paths that were <b>actually extracted</b>. A manifest pointing
 * at {@code ../../../secrets} is refused by the first; one pointing at a file that was dropped for
 * not being on the allowlist, or that was never in the archive at all, is refused by the second.
 * A package whose entry point does not exist is broken whichever way it got that way, and saying
 * so at ingest is much kinder than a learner meeting a 404 inside a course six months later.
 *
 * <h2>The SCORM version is read from the namespace, never from a version attribute</h2>
 *
 * <p>{@code <schemaversion>} is free text that exporters fill in with everything from
 * {@code 1.2} to {@code CAM 1.3} to {@code 2004 4th Edition}. The XML namespace is the thing the
 * standard actually fixes, and it is what decides which API object the wrapper has to expose —
 * a SCORM 2004 package handed a SCORM 1.2 {@code API} finds it, calls it, and fails in the middle
 * of somebody's compliance training.
 */
@Component
public class ManifestReader {

    /** SCORM 1.2's content-packaging namespace. IMS's original, and the one still most common. */
    private static final String IMSCP_1P1P2 = "http://www.imsproject.org/xsd/imscp_rootv1p1p2";

    /** SCORM 2004's. Same elements, different URI, different runtime. */
    private static final String IMSCP_V1P1 = "http://www.imsglobal.org/xsd/imscp_v1p1";

    /** ADL's SCORM 2004 extensions. Its presence is the tie-breaker when the CP namespace is v1p1. */
    private static final String ADLCP_V1P3 = "http://www.adlnet.org/xsd/adlcp_v1p3";

    /** SCORM 1.2's ADL extensions, carrying {@code scormtype} in 1.2's spelling. */
    private static final String ADLCP_V1P2 = "http://www.adlnet.org/xsd/adlcp_rootv1p2";

    /**
     * Reads whichever manifest this kind of package is required to have.
     *
     * @param wanted    manifest files by lowercase path, as {@code SafeUnpacker} collected them
     * @param extracted every path that actually reached the packages bucket
     */
    public PackageManifest read(PackageKind kind, Map<String, byte[]> wanted, Set<String> extracted) {
        return switch (kind) {
            case SCORM -> scorm(wanted, extracted);
            case CMI5 -> cmi5(wanted, extracted);
            case HTML5 -> html5(extracted);
            case SLIDES -> slides(extracted);
        };
    }

    // ------------------------------------------------------------------------------ SCORM

    private PackageManifest scorm(Map<String, byte[]> wanted, Set<String> extracted) {
        byte[] xml = atRoot(wanted, "imsmanifest.xml").orElseThrow(() -> new PackageRejected(
            "A SCORM package must contain imsmanifest.xml at the root of the archive. This one "
            + "does not — if the course is inside a folder in the ZIP, re-export it or zip the "
            + "folder's CONTENTS rather than the folder."));

        Document document = parse(xml);
        Element manifest = document.getDocumentElement();
        String profile = scormProfile(manifest);

        // xml:base is a prefix the manifest may put in front of every href in the document, and
        // exporters do use it. Ignoring it produces a launch URL that is missing a directory and
        // a course that 404s on the first click.
        String manifestBase = attribute(manifest, "base");

        Element resources = firstChild(manifest, "resources").orElseThrow(() -> new PackageRejected(
            "imsmanifest.xml has no <resources> element, so nothing in the package can be "
            + "launched."));
        String resourcesBase = attribute(resources, "base");

        Element chosen = defaultOrganisationResource(manifest, resources)
            .or(() -> firstScoResource(resources))
            .or(() -> firstResourceWithHref(resources))
            .orElseThrow(() -> new PackageRejected(
                "imsmanifest.xml names no resource with an href, so there is no file to open. "
                + "This is usually a manifest-only export."));

        String href = attribute(chosen, "href");
        if (href == null || href.isBlank()) {
            throw new PackageRejected(
                "The resource imsmanifest.xml points at has no href, so there is no file to open.");
        }
        String entry = resolve(manifestBase, resourcesBase, attribute(chosen, "base"), href, extracted);
        return new PackageManifest(organisationTitle(manifest).orElse(null), entry, profile);
    }

    /**
     * SCORM 1.2 or SCORM 2004, decided by namespace.
     *
     * <p>The content-packaging namespace alone is not quite enough: SCORM 2004 and some 1.2-era
     * exporters both emit {@code imscp_v1p1}. ADL's {@code adlcp_v1p3} appears only in 2004, so
     * its presence settles it, and its absence with the newer CP namespace is treated as 2004
     * anyway — {@code imsproject.org} is the URI that only 1.2 ever used.
     */
    private String scormProfile(Element manifest) {
        String namespace = manifest.getNamespaceURI();
        if (IMSCP_1P1P2.equals(namespace)) {
            return PackageManifest.SCORM_12;
        }
        if (IMSCP_V1P1.equals(namespace)) {
            return PackageManifest.SCORM_2004;
        }
        // A manifest in no namespace at all. Real exports do this, and refusing them would refuse
        // working courses -- so it falls back to 1.2, which is the version whose API a 2004
        // package would ALSO find (the 2004 object is a superset by name, not by behaviour), and
        // logs nothing here because the resource walk below is where a real problem surfaces.
        return declares(manifest, ADLCP_V1P3) ? PackageManifest.SCORM_2004 : PackageManifest.SCORM_12;
    }

    private boolean declares(Element manifest, String namespace) {
        var attributes = manifest.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            if (namespace.equals(attributes.item(index).getNodeValue())) {
                return true;
            }
        }
        return false;
    }

    /** The resource the default organisation's first launchable item points at. */
    private Optional<Element> defaultOrganisationResource(Element manifest, Element resources) {
        return firstChild(manifest, "organizations").flatMap(organisations -> {
            String defaultId = attribute(organisations, "default");
            List<Element> all = children(organisations, "organization");
            Element organisation = all.stream()
                .filter(one -> defaultId != null && defaultId.equals(attribute(one, "identifier")))
                .findFirst()
                // A `default` naming an organisation that is not there is a broken manifest, and
                // the first organisation is what every player falls back to.
                .orElseGet(() -> all.isEmpty() ? null : all.getFirst());
            if (organisation == null) {
                return Optional.empty();
            }
            return firstIdentifierRef(organisation)
                .flatMap(reference -> children(resources, "resource").stream()
                    .filter(one -> reference.equals(attribute(one, "identifier")))
                    .findFirst());
        });
    }

    /** Items nest, and the first one with an identifierref is the one that opens. */
    private Optional<String> firstIdentifierRef(Element parent) {
        for (Element item : children(parent, "item")) {
            String reference = attribute(item, "identifierref");
            if (reference != null && !reference.isBlank()) {
                return Optional.of(reference);
            }
            Optional<String> nested = firstIdentifierRef(item);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private Optional<Element> firstScoResource(Element resources) {
        return children(resources, "resource").stream()
            .filter(resource -> {
                // `scormtype` lives in ADL's namespace and is spelled differently in the two
                // versions' schemas -- `scormType` in 2004, `scormtype` in 1.2 -- and exporters
                // disagree about the case in both. Asked for both spellings in both namespaces.
                String type = firstNonNull(
                    resource.getAttributeNS(ADLCP_V1P3, "scormType"),
                    resource.getAttributeNS(ADLCP_V1P3, "scormtype"),
                    resource.getAttributeNS(ADLCP_V1P2, "scormType"),
                    resource.getAttributeNS(ADLCP_V1P2, "scormtype"));
                return type != null && type.equalsIgnoreCase("sco");
            })
            .filter(resource -> notBlank(attribute(resource, "href")))
            .findFirst();
    }

    private Optional<Element> firstResourceWithHref(Element resources) {
        return children(resources, "resource").stream()
            .filter(resource -> notBlank(attribute(resource, "href")))
            .findFirst();
    }

    private Optional<String> organisationTitle(Element manifest) {
        return firstChild(manifest, "organizations")
            .flatMap(organisations -> children(organisations, "organization").stream().findFirst())
            .flatMap(organisation -> firstChild(organisation, "title"))
            .map(Node::getTextContent)
            .map(String::strip)
            .filter(title -> !title.isEmpty());
    }

    // ------------------------------------------------------------------------------- cmi5

    private PackageManifest cmi5(Map<String, byte[]> wanted, Set<String> extracted) {
        byte[] xml = atRoot(wanted, "cmi5.xml").orElseThrow(() -> new PackageRejected(
            "A cmi5 package must contain cmi5.xml at the root of the archive."));
        Document document = parse(xml);
        Element structure = document.getDocumentElement();

        // <au> is the assignable unit -- the thing a learner opens. A course structure with
        // blocks nests them, so this walks rather than looking one level down.
        Element unit = firstDescendant(structure, "au").orElseThrow(() -> new PackageRejected(
            "cmi5.xml contains no <au>, so there is no assignable unit to open."));
        String url = firstChild(unit, "url").map(Node::getTextContent).map(String::strip)
            .orElseThrow(() -> new PackageRejected(
                "The <au> in cmi5.xml has no <url>, so there is no file to open."));

        /*
         * A cmi5 <url> MAY be absolute, and that is a legitimate part of the standard: an AU can
         * live on the vendor's own servers. It is refused here anyway.
         *
         * Hosting somebody else's URL inside a course would put a third party in a position to
         * change what a learner is shown after the customer approved it, with no re-upload and no
         * record -- and it would do it inside the iframe the whole of ADR-0105 exists to make
         * safe. If a customer needs a hosted AU, that is a product decision with an audit story,
         * not a side effect of a <url> element.
         */
        if (url.contains("://") || url.startsWith("//")) {
            throw new PackageRejected("The <au> in cmi5.xml points at an external address (\""
                + url + "\"). This platform only launches content it holds.");
        }
        String entry = resolve(null, null, null, url, extracted);
        String title = firstChild(unit, "title").flatMap(node -> firstChild(node, "langstring"))
            .or(() -> firstDescendant(structure, "title").flatMap(node -> firstChild(node, "langstring")))
            .map(Node::getTextContent).map(String::strip).filter(text -> !text.isEmpty())
            .orElse(null);
        return new PackageManifest(title, entry, PackageManifest.CMI5);
    }

    // ------------------------------------------------------------------------------ html5

    /**
     * An HTML5 bundle: no manifest, so the entry point is found rather than read (T-4.8).
     *
     * <p><b>{@code index.html} at the root, and nothing clever after that.</b> Every web bundle in
     * existence has one, because that is what a web server serves for a directory — and the two
     * fallbacks below are for the two ways a real export gets it slightly wrong, not for a general
     * search. Guessing further than this would mean picking one of forty HTML files and being
     * confidently wrong about which one is the course.
     *
     * <p>The profile is {@code html5}, which the wrapper reads to decide that this package gets the
     * small self-reporting API and <b>not</b> a SCORM one. A bundle handed an {@code API} object it
     * never asked for would find it through the discovery walk only if it were looking, and if it
     * were looking it would be a SCORM package.
     */
    private PackageManifest html5(Set<String> extracted) {
        String entry = atRootNamed(extracted, "index.html")
            .or(() -> atRootNamed(extracted, "index.htm"))
            // Exactly one HTML file in the whole bundle: unambiguous, whatever it is called.
            // Two or more without an index is genuinely ambiguous and is refused below.
            .or(() -> onlyHtml(extracted))
            .orElseThrow(() -> new PackageRejected(
                "An HTML5 package needs an index.html at the root of the archive. This one has "
                + "none — if the bundle is inside a folder in the ZIP, re-export it or zip the "
                + "folder's CONTENTS rather than the folder."));
        return new PackageManifest(null, entry, PackageManifest.HTML5);
    }

    private Optional<String> atRootNamed(Set<String> extracted, String name) {
        return extracted.stream().filter(path -> path.equalsIgnoreCase(name)).findFirst();
    }

    private Optional<String> onlyHtml(Set<String> extracted) {
        List<String> pages = extracted.stream()
            .filter(path -> {
                String lower = path.toLowerCase(Locale.ROOT);
                return lower.endsWith(".html") || lower.endsWith(".htm");
            })
            .toList();
        return pages.size() == 1 ? Optional.of(pages.getFirst()) : Optional.empty();
    }

    // ----------------------------------------------------------------------------- slides

    /**
     * Slides have no manifest and no runtime.
     *
     * <p>The entry is {@code index.html} when the export has one, and otherwise the first page
     * image in path order — which is what a rasterised deck is: a folder of numbered images. There
     * is no profile, because there is no API for the wrapper to present.
     */
    private PackageManifest slides(Set<String> extracted) {
        Optional<String> index = extracted.stream()
            .filter(path -> path.equalsIgnoreCase("index.html") || path.equalsIgnoreCase("index.htm"))
            .findFirst();
        if (index.isPresent()) {
            return new PackageManifest(null, index.get(), null);
        }
        String firstPage = extracted.stream()
            .filter(path -> path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp|avif)$"))
            .sorted()
            .findFirst()
            .orElseThrow(() -> new PackageRejected(
                "The archive contains neither an index.html nor any page images, so there is "
                + "nothing to show. Slides are uploaded as an already-rasterised export."));
        return new PackageManifest(null, firstPage, null);
    }

    // ------------------------------------------------------------------------------ shared

    /**
     * Turns an href from a manifest into a path this platform will serve, or refuses it.
     *
     * <p>Three bases can apply — the manifest's, the resources element's and the resource's own —
     * and they compose in that order, which is what the content-packaging specification says and
     * what exporters rely on.
     */
    private String resolve(String manifestBase, String resourcesBase, String resourceBase,
            String href, Set<String> extracted) {
        // The query and fragment a SCORM href may carry (`index.html?mode=review#start`) are part
        // of the launch URL and not part of the file name. Split before normalising, so the file
        // is looked up by its actual path, and re-attached to what the browser is sent.
        int cut = indexOfAny(href, '?', '#');
        String file = cut < 0 ? href : href.substring(0, cut);
        String suffix = cut < 0 ? "" : href.substring(cut);

        String joined = join(manifestBase, join(resourcesBase, join(resourceBase, decode(file))));
        String normalised;
        try {
            normalised = EntryPath.normalise(joined, 512);
        } catch (PackageRejected escaped) {
            // Re-worded: the author needs to know it was the MANIFEST that pointed outside, not
            // one of their four hundred files.
            throw new PackageRejected("The manifest's entry point points outside the package: \""
                + href + "\"");
        }
        if (normalised == null) {
            throw new PackageRejected("The manifest's entry point is not a file: \"" + href + "\"");
        }
        // THE CHECK THAT MAKES THE REST OF IT SAFE. The entry must be something that was actually
        // extracted -- present in the archive, on the allowlist, and now in the bucket. Anything
        // else would be a launch URL for a file that is not there.
        String match = extracted.stream()
            .filter(path -> path.equalsIgnoreCase(normalised))
            .findFirst()
            .orElseThrow(() -> new PackageRejected(
                "The manifest points at \"" + href + "\", which is not in the package. Either the "
                + "export is missing a file, or it is a file type this platform does not serve."));
        return match + suffix;
    }

    /** Only the manifest at the archive root counts — a nested one belongs to a sub-package. */
    private Optional<byte[]> atRoot(Map<String, byte[]> wanted, String name) {
        return Optional.ofNullable(wanted.get(name));
    }

    private Document parse(byte[] xml) {
        try {
            return SafeXml.parse(xml);
        } catch (Exception notXml) {
            throw new PackageRejected("The package's manifest is not valid XML: "
                + notXml.getMessage());
        }
    }

    private static String join(String base, String path) {
        if (base == null || base.isBlank()) {
            return path;
        }
        return base.endsWith("/") ? base + path : base + "/" + path;
    }

    /**
     * Percent-decoding, because an href is a URI reference and a file with a space in its name is
     * written {@code My%20Course.html}. Left as-is when the string is not valid encoding: a
     * literal {@code %} in a filename is legal, and refusing it would refuse real packages.
     */
    private static String decode(String href) {
        try {
            return java.net.URLDecoder.decode(href, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            return href;
        }
    }

    private static int indexOfAny(String value, char first, char second) {
        int a = value.indexOf(first);
        int b = value.indexOf(second);
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (notBlank(value)) {
            return value;
        }
        // xml:base is namespaced; getAttribute("base") does not find it.
        String namespaced = element.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, name);
        return notBlank(namespaced) ? namespaced : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Children by local name, ignoring the namespace.
     *
     * <p>Namespace-aware parsing tells us the SCORM version from the root element, and then the
     * element names themselves are matched loosely on purpose: exporters put resources in the CP
     * namespace, in no namespace, and occasionally in a namespace of their own invention, and a
     * strict match would refuse courses that every other player opens.
     */
    private static List<Element> children(Element parent, String localName) {
        NodeList nodes = parent.getChildNodes();
        List<Element> found = new java.util.ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element && named(element, localName)) {
                found.add(element);
            }
        }
        return found;
    }

    private static Optional<Element> firstChild(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    private static Optional<Element> firstDescendant(Element parent, String localName) {
        for (Element child : children(parent, "*")) {
            if (named(child, localName)) {
                return Optional.of(child);
            }
            Optional<Element> deeper = firstDescendant(child, localName);
            if (deeper.isPresent()) {
                return deeper;
            }
        }
        return Optional.empty();
    }

    private static boolean named(Element element, String localName) {
        if (localName.equals("*")) {
            return true;
        }
        String local = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
        int colon = local.indexOf(':');
        if (colon >= 0) {
            local = local.substring(colon + 1);
        }
        return local.equalsIgnoreCase(localName);
    }
}
