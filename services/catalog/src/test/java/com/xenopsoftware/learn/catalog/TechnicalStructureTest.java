package com.xenopsoftware.learn.catalog;

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
     * CATALOG POINTS AT OTHER MODULES' THINGS AND OWNS NONE OF THEM (ADR-0109).
     *
     * <p>This is the rule most at risk in this repository, and the reason is that breaking it
     * would be helpful. A content item already holds a streaming asset's id; caching the video's
     * duration beside it would save a call on the one screen that renders a course, and the
     * request to do so will be reasonable, specific and hard to argue with.
     *
     * <p>It is still the end of the decomposition. A duration copied here is a duration that goes
     * stale when the asset is re-encoded, and the bug it produces — a progress bar that disagrees
     * with the video — is discovered by a learner and diagnosed nowhere near here.
     *
     * <p>The database credentials are the half that cannot be argued with: {@code catalog} has one
     * role and it reaches one database (T-9.9's init script). This rule is the half that catches
     * the mistake at compile time instead.
     */
    @ArchTest
    static final ArchRule catalogReachesIntoNoOtherModule = noClasses()
        .that()
        .resideInAPackage("com.xenopsoftware.learn.catalog..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.xenopsoftware.learn.identity..", "com.xenopsoftware.learn.streaming..",
                            "com.xenopsoftware.learn.assessment..", "com.xenopsoftware.learn.reporting..",
                            "com.xenopsoftware.learn.packaging..")
        .because("a content item POINTS at an asset and asks its owner; it never copies its facts "
            + "(ADR-0109's data-ownership rule)");

    /**
     * And the direction that matters once ADR-0109's {@code core} merge happens: nothing may reach
     * into catalog's internals either.
     *
     * <p>Vacuous today — catalog is its own artifact, so a call from {@code identity} could not
     * compile. The merge is what makes that call compile, in a build where nothing else would
     * object, and a rule written after the merge is a rule written after the calls.
     */
    @ArchTest
    static final ArchRule noModuleReachesIntoCatalogsInternals = noClasses()
        .that()
        .resideOutsideOfPackages("com.xenopsoftware.learn.catalog..", "com.xenopsoftware.learn.common..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.xenopsoftware.learn.catalog..")
        .because("modules merged into one process (ADR-0109) must stay separable")
        .allowEmptyShould(true);
}
