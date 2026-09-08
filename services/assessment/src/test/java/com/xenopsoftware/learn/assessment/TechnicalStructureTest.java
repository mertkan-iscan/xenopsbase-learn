package com.xenopsoftware.learn.assessment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * The template's conventions (T-9.10), plus the rule this module is most likely to break.
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
        .because("the tenant is bound once, from the verified token, by TenantFilter");

    /**
     * ASSESSMENT REACHES INTO NO OTHER MODULE (ADR-0109).
     *
     * <p>The temptation here is not catalog's. Catalog is tempted to copy a fact — a video's
     * duration. This module is tempted to reach for a <b>decision</b>: T-6.1's authoring boundary
     * is a permission, {@code identity} owns the evaluator that answers permission questions, and
     * the shortest path to a green acceptance criterion is an import.
     *
     * <p>That import is the thing this rule exists to stop, and it would be the wrong fix twice
     * over. It would drag identity's entity graph into this artifact, and it would make the
     * boundary look enforced while the grants it depends on still live in another process's
     * database — a check that compiles, runs, and answers from nothing.
     *
     * <p>The right fix is ADR-0109's {@code core} merge, or extracting the evaluator behind its
     * port. Both are larger than an import, which is exactly why a rule is cheaper than a
     * convention.
     */
    @ArchTest
    static final ArchRule assessmentReachesIntoNoOtherModule = noClasses()
        .that()
        .resideInAPackage("com.xenopsoftware.learn.assessment..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.xenopsoftware.learn.identity..", "com.xenopsoftware.learn.catalog..",
                            "com.xenopsoftware.learn.streaming..", "com.xenopsoftware.learn.reporting..",
                            "com.xenopsoftware.learn.packaging..")
        .because("a bank's authoring boundary is identity's decision to answer, asked across a "
            + "published interface and never by importing it (ADR-0109's data-ownership rule)");

    /**
     * And the direction that matters once ADR-0109's {@code core} merge happens: nothing may reach
     * into assessment's internals either.
     *
     * <p>Vacuous today — assessment is its own artifact, so a call from {@code catalog} could not
     * compile. The merge is what makes that call compile, in a build where nothing else would
     * object, and a rule written after the merge is a rule written after the calls.
     */
    @ArchTest
    static final ArchRule noModuleReachesIntoAssessmentsInternals = noClasses()
        .that()
        .resideOutsideOfPackages("com.xenopsoftware.learn.assessment..", "com.xenopsoftware.learn.common..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.xenopsoftware.learn.assessment..")
        .because("modules merged into one process (ADR-0109) must stay separable")
        .allowEmptyShould(true);
}
