package com.xenopsoftware.learn.reporting;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * The template's conventions (T-9.10), plus the rule this service exists to keep (T-9.7).
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
        // Ingest is T-7.1; there is nothing to serve yet.
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyTheFilterResolvesTheTenant = noClasses()
        .that()
        .resideOutsideOfPackage("com.xenopsoftware.learn.common.tenancy..")
        .should()
        .callMethod(com.xenopsoftware.learn.common.tenancy.TenantContext.class, "set", String.class)
        .because("the tenant is bound once, from the verified token, by TenantFilter");

    /**
     * Reporting owns its data outright (T-9.7). A report that reaches into another module's
     * package is one step from a report that reaches into its schema, and the first such join
     * ends the decomposition without anybody noticing — it works, it is faster, and it is only
     * discovered when a schema change somewhere else breaks a report.
     *
     * <p>The database credentials are the other half of this, and they are the half that cannot
     * be argued with: {@code reporting_db} has one role and it belongs to nobody else. Proved in
     * {@code DatabaseOwnershipTest}.
     */
    @ArchTest
    static final ArchRule reportingReadsNoOtherModule = noClasses()
        .that()
        .resideInAPackage("com.xenopsoftware.learn.reporting..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.xenopsoftware.learn.identity..", "com.xenopsoftware.learn.catalog..",
                            "com.xenopsoftware.learn.streaming..", "com.xenopsoftware.learn.assessment..")
        .because("reporting receives what it needs as events and keeps its own copy (T-9.7)");

    /**
     * And the other direction, which is the one that stops a learner waiting on a report: no
     * module may reach into reporting either. A synchronous call from playback into this service
     * is exactly what makes reporting being down stop a video (T-9.7).
     */
    @ArchTest
    static final ArchRule noModuleReachesIntoReporting = noClasses()
        .that()
        .resideOutsideOfPackages("com.xenopsoftware.learn.reporting..", "com.xenopsoftware.learn.common..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.xenopsoftware.learn.reporting..")
        .because("nothing on a learner's request path may call reporting synchronously (T-9.7)")
        .allowEmptyShould(true);
}
