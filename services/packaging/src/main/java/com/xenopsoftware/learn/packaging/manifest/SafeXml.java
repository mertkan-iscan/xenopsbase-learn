package com.xenopsoftware.learn.packaging.manifest;

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;

/**
 * Parsing XML that an attacker wrote (ADR-0105).
 *
 * <p><b>Every manifest this service reads came out of an uploaded archive</b>, which makes an XML
 * parser one of the more dangerous things in the ingest path. Java's defaults are not safe for
 * that input, and the failure is not a crash — it is a parse that succeeds and does something
 * else on the way:
 *
 * <ul>
 *   <li><b>XXE.</b> {@code <!ENTITY x SYSTEM "file:///etc/passwd">} makes the parser read a local
 *       file and put it in the document. In a service holding S3 credentials and a database URL,
 *       what it can read includes the environment.
 *   <li><b>SSRF by entity.</b> The same trick with an {@code http://} URL turns this service into
 *       a request forwarder inside our own network — including, on a cloud host, the instance
 *       metadata endpoint.
 *   <li><b>Billion laughs.</b> Ten nested entity definitions expand to gigabytes of memory from a
 *       one-kilobyte file, which is the zip bomb again in a place the unpack limits do not reach.
 * </ul>
 *
 * <p>{@code FEATURE_SECURE_PROCESSING} plus {@code disallow-doctype-decl} closes all three at
 * once, and the second is what makes it airtight: with no DOCTYPE there are no entities to
 * declare, so nothing depends on getting the other switches exactly right. No real SCORM,
 * cmi5 or tincan manifest has a DOCTYPE — they are namespaced XML, not SGML documents.
 */
final class SafeXml {

    private SafeXml() {}

    static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // The one that matters most: no DOCTYPE means no entity declarations at all.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        // Namespace-aware, because the SCORM version is decided by the namespace URI and nothing
        // else -- see ManifestReader. Without this the parser reports prefixed names and the
        // detection would be matching on a prefix an exporter chose.
        factory.setNamespaceAware(true);
        // Belt and braces: even with DOCTYPEs refused, an external schema location in the
        // instance document is another way out to the network.
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder builder = builder(factory);
        // No EntityResolver is installed on purpose: with DOCTYPEs refused there is nothing for
        // one to resolve, and installing a permissive one is the usual way this protection is
        // undone by somebody making a stubborn manifest work.
        return builder.parse(new ByteArrayInputStream(xml));
    }

    private static DocumentBuilder builder(DocumentBuilderFactory factory)
            throws ParserConfigurationException {
        return factory.newDocumentBuilder();
    }
}
