package com.xenopsoftware.learn.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Closed by default, this service's version (T-2.4's rule, streaming's shape).
 *
 * <p>Every endpoint under {@code /api/**} is consciously accounted for. A new one that nobody
 * decided the authorization of fails this test with instructions, which is the loud version of the
 * information a pen test would eventually deliver quietly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class CatalogCoverageTest extends PostgresTestHarness {

    /**
     * Endpoint → why authentication alone is the whole check today.
     *
     * <p>The reason is the same one for all of them and it is a real gap with a named owner, not
     * an oversight: the permission catalog and its evaluator live in {@code identity} (T-2.1,
     * T-2.4), and a check here needs the caller's GRANTS to travel between services.
     * Service-to-service authentication (T-9.11) carries who the caller is; it does not yet carry
     * what they hold. Until it does, any authenticated member of a company can author that
     * company's catalog — which is wrong, and is written down here rather than discovered.
     */
    private static final String AUTHZ_GAP =
        "T-9.11/T-2.4 -- content:author and content:publish belong in the catalog and cannot be "
        + "checked here until grants travel between services; annotating now would deny everyone";

    private static final Map<String, String> AUTH_ONLY = Map.ofEntries(
        Map.entry("ServiceChainResource#whoami",
            "shared by every service (T-9.11): reports what the caller's own token says and which "
            + "service carried it here, and nothing about anybody else"),
        Map.entry("ContentItemResource#types",
            "the list of content types this build supports. It is the same for every company and "
            + "reveals nothing about any of them -- a type picker is not a disclosure"),
        Map.entry("ContentItemResource#search", AUTHZ_GAP),
        Map.entry("ContentItemResource#item", AUTHZ_GAP),
        Map.entry("ContentItemResource#create", AUTHZ_GAP),
        Map.entry("ContentItemResource#update", AUTHZ_GAP),
        Map.entry("ContentItemResource#state", AUTHZ_GAP),
        Map.entry("CourseResource#all", AUTHZ_GAP),
        Map.entry("CourseResource#tree", AUTHZ_GAP),
        Map.entry("CourseResource#create", AUTHZ_GAP),
        Map.entry("CourseResource#addModule", AUTHZ_GAP),
        Map.entry("CourseResource#moveModule", AUTHZ_GAP),
        Map.entry("CourseResource#addNode", AUTHZ_GAP),
        Map.entry("CourseResource#moveNode", AUTHZ_GAP),
        Map.entry("CourseResource#setRequired", AUTHZ_GAP),
        Map.entry("CourseResource#rebalance", AUTHZ_GAP),
        Map.entry("GateResource#save", AUTHZ_GAP),
        Map.entry("GateResource#remove", AUTHZ_GAP),
        Map.entry("GateResource#rule", AUTHZ_GAP),
        Map.entry("GateResource#reachability",
            "reads another person's reachability by learnerId, which is not a secret from anyone "
            + "who can already read the whole course -- and an author previewing a course as a "
            + "particular learner is a real need. It should still require a permission once "
            + "grants travel between services (T-9.11), like everything else on this list"),
        Map.entry("AssignmentResource#all", AUTHZ_GAP),
        Map.entry("AssignmentResource#assign", AUTHZ_GAP),
        Map.entry("AssignmentResource#assignAll", AUTHZ_GAP),
        Map.entry("AssignmentResource#revoke", AUTHZ_GAP),
        Map.entry("AssignmentResource#obligations", AUTHZ_GAP));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyEndpointIsConsciouslyAccountedFor() {
        Set<String> unaccounted = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            boolean api = mapping.getPathPatternsCondition() != null
                && mapping.getPathPatternsCondition().getPatternValues().stream()
                    .anyMatch(pattern -> pattern.startsWith("/api/"));
            if (api) {
                String endpoint = handler.getBeanType().getSimpleName() + "#"
                    + handler.getMethod().getName();
                if (!AUTH_ONLY.containsKey(endpoint)) {
                    unaccounted.add(endpoint);
                }
            }
        });
        assertThat(unaccounted)
            .as("endpoints that decided their authorization by omission -- list each in AUTH_ONLY "
                + "with its reason, or bring the permission machinery here")
            .isEmpty();
    }
}
