package com.xenopsoftware.learn.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * This module is what the gateway and the eight MVC modules agree on, so it may not know which
 * one it is running inside (ADR-0111).
 *
 * <p>Paired with the {@code enforce-no-web-stack} enforcer rule in this module's pom, and neither
 * is redundant. The enforcer guards the classpath, which is what actually breaks the gateway — a
 * servlet JAR present but unreferenced still flips Boot's application type. This guards the
 * source, which is what breaks first and reads clearly when it does: somebody moves a filter down
 * here because "every service has one", and finds out from a rule that says why rather than from
 * a compiler error about a missing symbol.
 *
 * <p>Both directions are worth having because they fail at different times. Only one of them can
 * fail during the change that caused it.
 */
@AnalyzeClasses(
    packages = "com.xenopsoftware.learn.common",
    importOptions = ImportOption.DoNotIncludeTests.class)
class PlatformCommonIsStackNeutralTest {

    @ArchTest
    static final ArchRule nothingHereNamesAServletRequest = noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.servlet..", "org.springframework.web.servlet..")
        .because("the gateway depends on this module and has no servlet request to give it (ADR-0111)");

    /**
     * The mirror of the rule above. A reactive type here would be just as wrong and for the
     * symmetrical reason: it would make the eight MVC services carry Reactor to share a constant.
     */
    @ArchTest
    static final ArchRule nothingHereNamesAReactiveExchange = noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.web.reactive..", "reactor.core..")
        .because("this module is what both stacks share, so it may not be written in either one");

    /**
     * The security context is per-request and per-stack — {@code SecurityContextHolder} on MVC,
     * {@code ReactiveSecurityContextHolder} on the gateway. Code down here that reads one of them
     * works in eight processes and silently returns nothing in the ninth.
     */
    @ArchTest
    static final ArchRule nothingHereReadsTheSecurityContext = noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.security..")
        .because("who is calling is a question each stack answers differently (ADR-0111)");
}
