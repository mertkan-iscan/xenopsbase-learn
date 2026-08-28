package com.xenopsoftware.learn.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * The conventions, enforced rather than documented.
 *
 * <p>Every rule here exists because the alternative is a review catching it, and a review catches
 * it until the week nobody has time. These are the rules a new module inherits by being generated
 * from the template (T-9.10) — so they are written once, here, and the eighth service gets them
 * for free.
 */
@AnalyzeClasses(packages = "com.xenopsoftware.learn", importOptions = ImportOption.DoNotIncludeTests.class)
class TechnicalStructureTest {

    /**
     * Everything under {@code /api/**} is authenticated by SecurityConfiguration. A controller
     * outside that tree is a controller whose exposure was decided by where somebody put the file.
     */
    @ArchTest
    static final ArchRule controllersLiveUnderWebRest = classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideInAPackage("..web.rest..")
        .because("controllers outside web.rest are easy to expose by accident");

    /**
     * The shared library must not know about any service. The moment it does, it stops being
     * infrastructure and becomes a place where two modules are coupled through a third — which is
     * harder to see than coupling them directly.
     */
    @ArchTest
    static final ArchRule commonKnowsNothingAboutServices = noClasses()
        .that()
        .resideInAPackage("com.xenopsoftware.learn.common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.xenopsoftware.learn.identity..", "com.xenopsoftware.learn.catalog..",
                            "com.xenopsoftware.learn.streaming..", "com.xenopsoftware.learn.assessment..",
                            "com.xenopsoftware.learn.reporting..")
        .because("platform-common is shared infrastructure, not a place for domain code");

    /**
     * The tenant comes from a verified claim, never from something the caller controls.
     *
     * <p>Enforced structurally because the version of this that gets added is reasonable-looking:
     * a header for testing, a query parameter for support. This rule is what makes that a build
     * failure rather than a code review someone is too busy for.
     */
    @ArchTest
    static final ArchRule onlyTheFilterResolvesTheTenant = noClasses()
        .that()
        .resideOutsideOfPackage("com.xenopsoftware.learn.common.tenancy..")
        .should()
        .callMethod(com.xenopsoftware.learn.common.tenancy.TenantContext.class, "set", String.class)
        .because("the tenant is bound once, from the verified token, by TenantFilter");

    /**
     * A reference to a person is a reference to {@code app_user.id}, never a stored {@code sub}
     * (ADR-0104). {@code AppUser.idpSub} is the one legitimate holder; a field shaped like a
     * subject anywhere else is the convenience copy this rule exists to refuse — it looks stable
     * on every request and stops being stable the day a customer changes identity provider.
     * {@code SchemaConventionsTest} enforces the same rule at the column level.
     */
    @ArchTest
    static final ArchRule aSubIsStoredOnlyByAppUser = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
        .noFields()
        .that()
        .areDeclaredInClassesThat()
        .resideOutsideOfPackage("com.xenopsoftware.learn.identity.user..")
        .should()
        .haveNameMatching("(?i)^(sub|idp_?sub|keycloak_?sub|oidc_?sub|subject_?id)$")
        .because("app_user.idp_sub is the only stored sub; everything else references app_user.id (ADR-0104)");

    /**
     * No check names a role (T-2.4). Roles are runtime data a customer builds and rebuilds; a
     * method gated on {@code tenant-admin} is a method no customer can re-wire. Checks say
     * {@code hasPermission('resource', 'action')} and the catalog evaluator answers.
     */
    @ArchTest
    static final ArchRule noPreAuthorizeNamesARole = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
        .methods()
        .that()
        .areAnnotatedWith(org.springframework.security.access.prepost.PreAuthorize.class)
        .should(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>(
            "check a permission, not a role") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaMethod method,
                    com.tngtech.archunit.lang.ConditionEvents events) {
                String expression = method
                    .getAnnotationOfType(org.springframework.security.access.prepost.PreAuthorize.class)
                    .value();
                if (expression.matches(".*(hasRole|hasAnyRole|hasAuthority|hasAnyAuthority|ROLE_).*")) {
                    events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(method,
                        method.getFullName() + " names a role or authority: " + expression));
                }
            }
        })
        .because("roles are runtime data; checks name a catalog permission and the evaluator decides (T-2.4)")
        // No production method carries @PreAuthorize until T-2.3 supplies grants; an empty
        // match set is the current correct state, not a broken rule.
        .allowEmptyShould(true);
}
