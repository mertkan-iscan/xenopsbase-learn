package com.xenopsoftware.learn.packaging;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * The template's conventions (T-9.10), plus the two rules this module exists to be held to.
 */
@AnalyzeClasses(packages = "com.xenopsoftware.learn", importOptions = ImportOption.DoNotIncludeTests.class)
class TechnicalStructureTest {

    @ArchTest
    static final ArchRule controllersLiveUnderWebRest = classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideInAPackage("..web.rest..")
        .because("controllers outside web.rest are easy to expose by accident");

    @ArchTest
    static final ArchRule onlyTheFilterResolvesTheTenant = noClasses()
        .that()
        .resideOutsideOfPackage("com.xenopsoftware.learn.common.tenancy..")
        .should()
        .callMethod(com.xenopsoftware.learn.common.tenancy.TenantContext.class, "set", String.class)
        .because("the tenant is bound once, from the verified token, by TenantFilter — and this "
            + "module is the one with a route whose only input is an anonymous URL");

    /**
     * NOTHING ON THE APPLICATION ORIGIN SERVES AN UPLOADED PACKAGE (ADR-0105).
     *
     * <p>The same rule identity and streaming already carry, and here it applies to the service
     * that actually has the files — which is the module most able to break it, and the one where
     * breaking it would be one line of a controller.
     *
     * <p>It is why the management API is {@code /api/v1/uploads} rather than the noun a reader
     * expects, and why the content-origin route is {@code /served}. Both names are inconvenient on
     * purpose: a rule that can be satisfied by reading a comment is not a rule.
     */
    @ArchTest
    static final ArchRule noAppOriginRouteServesUploadedPackages = classes()
        .should(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaClass>(
            "not map a path under /packages (ADR-0105)") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaClass type,
                    com.tngtech.archunit.lang.ConditionEvents events) {
                for (String path : mappedPaths(type)) {
                    if (path.toLowerCase(java.util.Locale.ROOT).contains("packages")) {
                        events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(type,
                            type.getName() + " maps " + path + " on the application origin; "
                            + "uploaded packages are served by the content origin (ADR-0105)"));
                    }
                }
            }
        })
        .because("an uploaded package on the app origin has the app's DOM, session and tokens");

    /**
     * NO UPLOAD PATH THROUGH A REQUEST THREAD, structurally.
     *
     * <p>Streaming carries this rule because video bytes must never reach it at all. Here the
     * bytes DO get read — somebody has to open the archive before a learner's browser does — but
     * they are read <b>from object storage</b>, on this service's terms, inside limits checked
     * before the first one arrived.
     *
     * <p>Multipart would be the shortcut past all of it: an archive spooled by the container,
     * sized by a header the client wrote, arriving through the gateway. It would appear as a
     * reasonable convenience ("the upload target is two calls; let me just accept the file"), and
     * it would move the ingest from a bounded background-shaped operation onto the request path
     * for every author on a bad connection. Configuration disables it too; this is the
     * compile-time half of the same refusal.
     */
    @ArchTest
    static final ArchRule noMultipartUploadPath = noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.web.multipart..")
        .because("archives arrive at a presigned upload target, never through a request thread");

    /**
     * THE XML PARSER IS BUILT IN ONE PLACE.
     *
     * <p>Every manifest this service reads came out of an uploaded archive, and Java's default
     * {@code DocumentBuilderFactory} is not safe for that input — XXE reads local files, external
     * entities turn this service into a request forwarder inside our own network, and nested
     * entity expansion is a zip bomb somewhere the unpack limits do not reach.
     *
     * <p>{@code SafeXml} configures all of that off. The failure mode this rule prevents is a
     * second, plainer parser appearing next to it, added by somebody making a stubborn manifest
     * work — a change that looks like two lines and quietly removes every one of those
     * protections.
     */
    @ArchTest
    static final ArchRule onlySafeXmlParses = noClasses()
        .that()
        .haveSimpleNameNotEndingWith("SafeXml")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("javax.xml.parsers.DocumentBuilderFactory")
        .because("uploaded XML is parsed with entities and DOCTYPEs refused, in SafeXml, once");

    /** Every path any Spring web mapping on this class declares, class level and method level. */
    private static java.util.Set<String> mappedPaths(com.tngtech.archunit.core.domain.JavaClass type) {
        // A set: @GetMapping("/x") sets both `value` and its alias `path`, and reporting one
        // mistake twice makes a failure message read like two mistakes.
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        type.getAnnotations().forEach(annotation -> collectPaths(annotation, paths));
        type.getMethods().forEach(method ->
            method.getAnnotations().forEach(annotation -> collectPaths(annotation, paths)));
        return paths;
    }

    private static void collectPaths(com.tngtech.archunit.core.domain.JavaAnnotation<?> annotation,
            java.util.Set<String> into) {
        if (!annotation.getRawType().getName().startsWith("org.springframework.web.bind.annotation.")) {
            return;
        }
        for (String attribute : java.util.List.of("value", "path")) {
            annotation.get(attribute).ifPresent(value -> {
                if (value instanceof Object[] many) {
                    for (Object one : many) {
                        into.add(String.valueOf(one));
                    }
                } else {
                    into.add(String.valueOf(value));
                }
            });
        }
    }
}
