package com.xenopsoftware.learn.core;

import com.xenopsoftware.learn.catalog.CatalogApp;
import com.xenopsoftware.learn.identity.IdentityApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * {@code identity} and {@code catalog} in one process (ADR-0109, T-9.17).
 *
 * <h2>An assembly, not a merge of source</h2>
 *
 * Nothing moved. Both modules are still their own Maven modules, their own packages and their own
 * test suites; this depends on them as libraries and starts them together. That is deliberate, and
 * it is what makes ADR-0109's promise literally true rather than aspirational — it says splitting
 * later is "a deployment change and a client swap", and with the code untouched the split is
 * deleting this module and giving each of them an image again.
 *
 * <p>It also keeps 331 existing tests running exactly where they were, against each module's own
 * context. A merge that moved 173 files would have had to re-prove all of them.
 *
 * <h2>Fully-qualified bean names, because the two modules share vocabulary</h2>
 *
 * Spring's default names a scanned bean after its simple class name, so
 * {@code identity.authz.AssignmentService} and {@code catalog.assign.AssignmentService} both want
 * to be {@code assignmentService} — and the context refuses to start. That is not a coincidence
 * worth working around case by case: both modules genuinely have an <b>Assignment</b>, they mean
 * different things (a role granted to a person, and content assigned to a learner), and the same
 * clash is what makes {@code /api/v1/assignments} unroutable at the gateway.
 *
 * <p>{@link FullyQualifiedAnnotationBeanNameGenerator} names every scanned bean by its FQN, so the
 * two coexist under names that say which module they came from. Applied here rather than by
 * renaming a class in one module, because renaming would make the merged process the reason a
 * standalone module's class is called something awkward.
 *
 * <p>Safe to apply: nothing in either module refers to a scanned bean by name. The
 * {@code @Qualifier}s that do exist name {@code @Bean} methods, which keep their method names.
 *
 * <h2>Why this is not {@code @SpringBootApplication}</h2>
 *
 * That annotation implies a component scan of its own package and exposes no {@code excludeFilters}.
 * Combining it with an explicit {@code @ComponentScan} means two scans with different settings over
 * overlapping packages — which can register the same class twice under two different names once a
 * name generator is involved. Decomposed, the scan is declared exactly once.
 *
 * <h2>The three exclusions</h2>
 *
 * Each is a bean that exists twice once the two modules are in one room:
 *
 * <ul>
 *   <li><b>{@link IdentityApp} and {@link CatalogApp}</b> are {@code @SpringBootApplication}, which
 *       is a {@code @Configuration}, so scanning their packages would nest two more applications
 *       inside this one — each re-triggering auto-configuration.
 *   <li><b>catalog's {@code SecurityConfiguration}.</b> Two {@code SecurityFilterChain} beans both
 *       matching {@code /api/**} is not an error: the first by order wins and the other silently
 *       never runs. Which one is first is bean-ordering, and that is not a thing to leave to chance
 *       for the filter chain that decides what is public. identity's is kept because it is a strict
 *       superset — the same STATELESS, CSRF-off, {@code /api/**}-authenticated shape, plus the
 *       {@code POST /api/v1/auth/discovery} exception T-1.8 needs.
 * </ul>
 *
 * <p>{@link FlywayAutoConfiguration} goes for a different reason: it builds one Flyway against the
 * primary DataSource, and {@code CorePersistenceConfiguration} declares two databases with no
 * primary between them.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ComponentScan(
    basePackages = {
        "com.xenopsoftware.learn.core",
        "com.xenopsoftware.learn.identity",
        "com.xenopsoftware.learn.catalog",
        "com.xenopsoftware.learn.common",
    },
    nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            IdentityApp.class,
            CatalogApp.class,
            com.xenopsoftware.learn.catalog.config.SecurityConfiguration.class,
        }))
public class CoreApp {

    public static void main(String[] args) {
        SpringApplication.run(CoreApp.class, args);
    }
}
