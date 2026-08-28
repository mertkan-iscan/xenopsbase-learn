package com.xenopsoftware.learn.streaming;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * The template's conventions (T-9.10), plus the one rule this service exists to hold: the
 * delivery vendor stays inside its adapter (T-3.1, ADR-0101).
 */
@AnalyzeClasses(packages = "com.xenopsoftware.learn", importOptions = ImportOption.DoNotIncludeTests.class)
class TechnicalStructureTest {

    @ArchTest
    static final ArchRule controllersLiveUnderWebRest = classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideInAPackage("..web.rest..")
        .because("controllers outside web.rest are easy to expose by accident")
        // No controllers exist until T-3.2; an empty match set is the current correct state.
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyTheFilterResolvesTheTenant = noClasses()
        .that()
        .resideOutsideOfPackage("com.xenopsoftware.learn.common.tenancy..")
        .should()
        .callMethod(com.xenopsoftware.learn.common.tenancy.TenantContext.class, "set", String.class)
        .because("the tenant is bound once, from the verified token, by TenantFilter");

    /**
     * The port is only a port while no domain code knows the vendor's name. The swap ADR-0101
     * names — own transcode into R2, same edge — stays an adapter swap exactly as long as this
     * rule holds, and it erodes one reasonable convenience at a time: a provider id in a
     * controller, a vendor field in a report.
     */
    @ArchTest
    static final ArchRule theVendorStaysInsideItsAdapter = noClasses()
        .that()
        .resideOutsideOfPackage("..media.cloudflare..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameContaining("Cloudflare")
        .because("domain code that learns the word Cloudflare turns the adapter swap into a migration (T-3.1)");

    /** The same fence from the other side: vendor-named classes may not exist elsewhere. */
    @ArchTest
    static final ArchRule vendorClassesLiveInTheAdapterPackage = classes()
        .that()
        .haveSimpleNameContaining("Cloudflare")
        .should()
        .resideInAPackage("..media.cloudflare..")
        .because("the adapter package is the vendor's entire footprint (T-3.1)");

    /**
     * No upload path exists through this service, structurally (T-3.2). Bytes through a request
     * thread means a thread held for hours, a heap spooling gigabytes, and large-object traffic
     * on exactly the path ADR-0101 exists to keep empty — and it would arrive as a reasonable
     * convenience ("just for small files"). Multipart is also disabled in configuration; this
     * rule is the compile-time half of the same refusal.
     */
    @ArchTest
    static final ArchRule noUploadPathThroughThisService = noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.web.multipart..")
        .because("video bytes go directly to the provider's upload target, never through a request thread (T-3.2)");
}
