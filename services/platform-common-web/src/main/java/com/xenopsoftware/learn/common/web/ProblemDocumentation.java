package com.xenopsoftware.learn.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts the error shape into the API description, so a generated client can type a failure as well
 * as a success (T-9.10, #88).
 *
 * <h2>What was missing</h2>
 *
 * {@link Problems} made every refusal on this platform an RFC 9457 document, and then the
 * descriptions said nothing about it: 56 operations across the three published services declared
 * one response each, always the success. A client generated from that has a type for the body it
 * gets when everything works and {@code unknown} for every other case — which is the case it most
 * needs help with, because the success is the one a developer already has in front of them.
 *
 * <h2>It describes what is true, and nothing else</h2>
 *
 * The temptation with a customizer like this is to staple 400/401/403/404/500 onto every operation
 * and call the description complete. That asserts contracts nobody wrote, which is the mistake
 * {@code RefusalAdvice}'s old comment made: a claim of consistency that no code was keeping.
 *
 * <p>So this adds exactly one response, and only where it genuinely applies — the status gate's
 * 403, on every path under {@code /api/}.
 * {@link com.xenopsoftware.learn.common.tenancy.StatusGateFilter} is a {@code @Component} on the
 * shared package root whose {@code shouldNotFilter} is keyed to that prefix, so every service that
 * scans this library refuses a suspended or read-only account there, before any handler runs. That
 * is true of every {@code /api} operation in this repository, and of the next one somebody writes.
 *
 * <p>Everything narrower belongs to the endpoint that can produce it, declared with
 * {@code @ApiResponse} beside the method — see {@code PlaybackResource}, {@code ProgressResource}
 * and {@code TelemetryResource}. <b>A response somebody has already declared is never rewritten
 * here</b>, because some refusals deliberately carry no body: the disclosure rule (T-2.4) answers a
 * bare 404 precisely so nothing separates "not yours" from "no such thing", and a customizer that
 * helpfully attached a schema to it would document a body that must not exist.
 *
 * <h2>Not 401</h2>
 *
 * A missing or invalid bearer token is refused by Spring Security's own entry point, which answers
 * an empty body and a {@code WWW-Authenticate} header. There is no schema to declare for it, and a
 * blanket 401 would be false on the one {@code /api} path that permits anonymous callers —
 * identity's provider discovery (T-1.8).
 */
@Configuration(proxyBeanMethods = false)
public class ProblemDocumentation {

    /** The component name; {@link #REF} is what an {@code @ApiResponse} points at. */
    public static final String SCHEMA_NAME = "Problem";

    /** A compile-time constant, so an annotation can use it. */
    public static final String REF = "#/components/schemas/" + SCHEMA_NAME;

    /** The media type RFC 9457 registers, which is what {@link Problems} writes. */
    public static final String PROBLEM_JSON = "application/problem+json";

    private static final String GATE_DESCRIPTION = """
        The account was refused before the handler ran: the company is suspended, or it is \
        read-only and this is a write. Every path under `/api` answers this.

        A permission denial can also answer 403, and it carries **no body** — a refusal that \
        described itself would confirm the resource exists, which is what the disclosure rule is \
        protecting.""";

    private static final String SCHEMA_DESCRIPTION = """
        An RFC 9457 problem document. Every refusal this platform writes has this shape, on \
        `application/problem+json`.

        RFC 9457 permits extension members and this platform uses one, `code`. Switch on that \
        rather than on `type`: a URI invites prefix-matching and string surgery, and the short \
        token is the thing that stays readable in a client.""";

    /**
     * NOT named after the class, and that is not a style choice.
     *
     * <p>Component scanning registers this {@code @Configuration} under the decapitalised class
     * name, {@code problemDocumentation}. A {@code @Bean} method of the same name then asks for a
     * name that is already bound, and Boot refuses it outright — every service failed to start
     * with {@code BeanDefinitionOverrideException} and not one unit test noticed, because a test
     * that calls the method never asks a context to register it.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSchemas(SCHEMA_NAME, problemSchema());

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/api/")) {
                    return;
                }
                item.readOperations().forEach(ProblemDocumentation::addStatusGateRefusal);
            });
        };
    }

    /** Public so an endpoint points at the one document rather than describing a copy of it. */
    public static Content problemContent() {
        return new Content().addMediaType(
            PROBLEM_JSON,
            new MediaType().schema(new Schema<>().$ref(REF)));
    }

    private static void addStatusGateRefusal(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        // NEVER over a declared response. An endpoint that documents its own 403 knows something
        // this does not -- including, sometimes, that the body is deliberately absent.
        if (responses.get("403") != null) {
            return;
        }
        responses.addApiResponse("403", new ApiResponse()
            .description(GATE_DESCRIPTION)
            .content(problemContent()));
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
            .name(SCHEMA_NAME)
            .description(SCHEMA_DESCRIPTION)
            .addProperty("type", new StringSchema()
                .format("uri")
                .description("A stable URI identifying the kind of problem. It does not resolve; "
                    + "RFC 9457 says it need not."))
            .addProperty("title", new StringSchema()
                .description("A short, human-readable summary of the kind of problem."))
            .addProperty("status", new IntegerSchema()
                .format("int32")
                .description("The HTTP status code, repeated in the document."))
            .addProperty("detail", new StringSchema()
                .description("What went wrong THIS time, written for whoever is on the other end. "
                    + "Absent when the refusal must not describe itself."))
            .addProperty("instance", new StringSchema()
                .format("uri")
                .description("The occurrence this document is about, when there is one."))
            .addProperty("code", new StringSchema()
                .description("The extension member a client switches on, e.g. "
                    + "`PLAYBACK_NOT_ENTITLED`. Absent when the refusal has no machine-readable "
                    + "identity behind it, such as a bare `ResponseStatusException`."));
    }
}
