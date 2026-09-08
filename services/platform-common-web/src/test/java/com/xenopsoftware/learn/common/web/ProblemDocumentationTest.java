package com.xenopsoftware.learn.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * The error shape is in the description, and only where it is true (T-9.10, #88).
 *
 * <p>Asserted rather than eyeballed in a generated file for the reason the whole error-shape task
 * exists: what nothing checks drifts, and a description that has quietly stopped matching the
 * service is worse than one that was never written, because a client trusts it.
 */
class ProblemDocumentationTest {

    private final OpenApiCustomizer customizer = new ProblemDocumentation().problemResponses();

    @Test
    @DisplayName("the problem document is a named schema, with the code clients switch on")
    void theShapeIsDeclaredOnce() {
        OpenAPI api = openApi("/api/v1/videos");

        customizer.customise(api);

        Schema<?> problem = api.getComponents().getSchemas().get(ProblemDocumentation.SCHEMA_NAME);
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsOnlyKeys(
            "type", "title", "status", "detail", "instance", "code");
        // Named, not inlined at every use: one component is what makes a generated client carry
        // one type for a failure instead of an anonymous shape per operation.
        assertThat(problem.getDescription()).contains("RFC 9457");
    }

    @Test
    @DisplayName("every /api operation declares the status gate's 403")
    void theGateRefusesEveryApiPath() {
        OpenAPI api = openApi("/api/v1/videos");

        customizer.customise(api);

        ApiResponse refusal = responses(api, "/api/v1/videos").get("403");
        assertThat(refusal).isNotNull();
        assertThat(refusal.getContent().get(ProblemDocumentation.PROBLEM_JSON).getSchema().get$ref())
            .isEqualTo(ProblemDocumentation.REF);
    }

    @Test
    @DisplayName("a path the gate does not filter is left alone")
    void nothingIsClaimedAboutPathsTheGateNeverSees() {
        // StatusGateFilter.shouldNotFilter is keyed to the /api/ prefix, so a webhook or an
        // actuator endpoint never meets it. Declaring the refusal there would be describing a
        // response the service cannot produce.
        OpenAPI api = openApi("/webhooks/media");

        customizer.customise(api);

        assertThat(responses(api, "/webhooks/media").get("403")).isNull();
    }

    @Test
    @DisplayName("a response the endpoint declared for itself is never rewritten")
    void anEndpointKnowsMoreThanThisDoes() {
        // The case this protects: PlaybackResource declares a 404 with NO content, because the
        // disclosure rule (T-2.4) answers a bare 404 so that "not yours" and "no such thing" are
        // one answer. A customizer that helpfully attached a schema would document a body that
        // must not exist.
        OpenAPI api = openApi("/api/v1/me/nodes/{id}/playback-token");
        responses(api, "/api/v1/me/nodes/{id}/playback-token")
            .addApiResponse("403", new ApiResponse().description("the endpoint's own words"));

        customizer.customise(api);

        ApiResponse declared = responses(api, "/api/v1/me/nodes/{id}/playback-token").get("403");
        assertThat(declared.getDescription()).isEqualTo("the endpoint's own words");
        assertThat(declared.getContent()).isNull();
    }

    private static OpenAPI openApi(String path) {
        Operation operation = new Operation().responses(new ApiResponses()
            .addApiResponse("200", new ApiResponse()
                .description("ok")
                .content(new Content().addMediaType("application/json", new MediaType()))));
        return new OpenAPI().paths(new Paths().addPathItem(path, new PathItem().post(operation)));
    }

    private static ApiResponses responses(OpenAPI api, String path) {
        return api.getPaths().get(path).getPost().getResponses();
    }
}
