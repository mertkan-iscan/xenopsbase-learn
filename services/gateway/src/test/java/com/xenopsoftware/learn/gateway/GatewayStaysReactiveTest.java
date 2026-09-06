package com.xenopsoftware.learn.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The gateway is the one reactive process and must stay one (ADR-0111).
 *
 * <p>Three guards, at three different moments, because the failure has three different shapes and
 * only the first of them is loud:
 *
 * <ol>
 *   <li>The {@code enforce-no-servlet-stack} rule in this module's pom fails the build when a
 *       servlet artifact is <b>declared</b>.
 *   <li>The ArchUnit rule below fails when servlet code is <b>written</b>.
 *   <li>{@link #tomcatIsNotOnTheClasspath()} fails when a servlet container arrives
 *       <b>transitively</b>, through a dependency nobody read the tree of.
 * </ol>
 *
 * <p>The third is the one worth having a test for. Boot resolves the web application type by
 * looking for classes, not by reading a pom: {@code Tomcat} merely being present makes it choose
 * SERVLET, Spring Cloud Gateway refuses to start, and <b>nothing in this repository fails</b>. The
 * first sign is a pod that will not come up, which is a bad place to learn it.
 */
@AnalyzeClasses(
    packages = "com.xenopsoftware.learn",
    importOptions = ImportOption.DoNotIncludeTests.class)
class GatewayStaysReactiveTest {

    @ArchTest
    static final ArchRule nothingHereNamesAServletRequest = noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.servlet..", "org.springframework.web.servlet..")
        .because("Spring Cloud Gateway runs on WebFlux and there is no servlet request to name (ADR-0111)");

    /**
     * The servlet half of the shared library is not reachable from here, by name.
     *
     * <p>Separate from the package rule above because it fails for a more useful reason: someone
     * reaching for {@code TenantFilter} or {@code ServiceCalls} up here is not making a mistake
     * about servlets, they are looking for behaviour that exists on the other side. The rule that
     * catches them should say where the reactive equivalent is.
     */
    @ArchTest
    static final ArchRule theServletHalfOfTheSharedLibraryIsNotReachable = noClasses()
        .should()
        .dependOnClassesThat()
        .haveNameMatching(
            "com\\.xenopsoftware\\.learn\\.common\\.(tenancy\\.(TenantFilter|StatusGateFilter|PublishedStatusLookup)"
                + "|messaging\\.CorrelationFilter|service\\..*|web\\.rest\\..*)")
        .because("those are platform-common-web; the reactive twins live in this module and the "
            + "contract they share is in platform-common (ADR-0111)");

    /**
     * No servlet container on the classpath at all.
     *
     * <p>Asserted by trying to load the class rather than by inspecting the pom, because the pom
     * is what the enforcer already checks and the classpath is what Boot actually reads.
     */
    @Test
    void tomcatIsNotOnTheClasspath() {
        assertThatExceptionOfType(ClassNotFoundException.class)
            .as("a servlet container here makes Boot choose SERVLET and this process stops "
                + "starting -- with a green build (ADR-0111)")
            .isThrownBy(() -> Class.forName("jakarta.servlet.Servlet"));
    }
}
