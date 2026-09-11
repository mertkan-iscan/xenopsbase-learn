package com.xenopsoftware.learn.catalog.content;

import tools.jackson.databind.JsonNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The five types the product ships with (T-5.1).
 *
 * <p>They live together because they are short and because a reader wants to see the whole set at
 * once; a sixth would be equally at home here or in its own class, which is the property the
 * registry exists to give.
 *
 * <p><b>Every payload here is a reference, never content.</b> A video payload holds the id of an
 * asset {@code streaming} owns; a SCORM payload holds the id of a package {@code packaging} owns.
 * Catalog stores neither the bytes nor a copy of the facts — it points, and asks the owner when it
 * needs more. That is what keeps ADR-0109's data-ownership rule true at the one table most likely
 * to break it, because "just cache the duration here" is a reasonable-sounding request.
 */
@Configuration(proxyBeanMethods = false)
public class BuiltInContentTypes {

    @Bean
    ContentTypeDefinition videoContentType() {
        return reference("video", "Video", "assetId",
            "A video item points at a streaming asset by id (T-3.1). Duration, encode state and "
            + "playback URL belong to streaming and are asked for, never copied.");
    }

    @Bean
    ContentTypeDefinition scormContentType() {
        return reference("scorm", "SCORM package", "packageId",
            "A SCORM item points at a package packaging extracted and validated (T-4.2).");
    }

    @Bean
    ContentTypeDefinition cmi5ContentType() {
        return reference("cmi5", "cmi5 package", "packageId",
            "Same shape as SCORM, different runtime (T-4.6): the launch and the statement store "
            + "differ, and the reference does not.");
    }

    /**
     * A web bundle with no runtime standard behind it (T-4.8).
     *
     * <p><b>Why this is a type of its own rather than "SCORM without a manifest".</b> A great deal
     * of real course content is exported as plain HTML5 — an authoring tool's "Web" output, a
     * developer's own interaction, a simulation somebody built. It is the same kind of archive as a
     * SCORM package and goes through exactly the same ingest (ADR-0105's list applies unchanged;
     * the danger was never the manifest), and it differs in the one thing that matters downstream:
     * <b>there is no standard for it to tell us anything.</b>
     *
     * <p>SCORM and cmi5 report completion through a contract their authors already implement. An
     * HTML5 bundle reports nothing unless somebody wrote code to, so its completion is opening it
     * plus whatever the bundle chooses to say through the small API the wrapper exposes
     * ({@code window.xenopslearn}). Modelling it as SCORM would mean serving a fake SCORM API to
     * content that never asked for one, and then reading silence as a failure to complete.
     */
    @Bean
    ContentTypeDefinition html5ContentType() {
        return reference("html5", "HTML5 package", "packageId",
            "An HTML5 item points at a bundle packaging extracted and validated (T-4.8). It has no "
            + "runtime standard, so completion is what the bundle reports and otherwise that it "
            + "was opened (ADR-0107).");
    }

    @Bean
    ContentTypeDefinition slidesContentType() {
        return reference("slides", "Slides or document", "documentId",
            "Rasterised to images by packaging (T-4.7), which owns the pages.");
    }

    @Bean
    ContentTypeDefinition testContentType() {
        return reference("test", "Test", "testId",
            "Points at an assessment test (T-6.4). Whether a learner may start one is catalog's "
            + "decision and the attempt itself is assessment's (ADR-0109).");
    }

    /**
     * Every built-in type has the same shape today — one required id — so they share one
     * validator rather than five copies of it. That is a statement about these five, not about
     * the interface: a type whose payload is genuinely different writes its own, which is the
     * whole point of the definition being a class.
     */
    private static ContentTypeDefinition reference(String code, String displayName, String field,
            String why) {
        return new ContentTypeDefinition() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public String displayName() {
                return displayName;
            }

            @Override
            public void validate(JsonNode payload) {
                JsonNode value = payload == null ? null : payload.get(field);
                if (value == null || !value.isTextual() || value.asText().isBlank()) {
                    // Named field, named type, and the reason -- an author reading this in a
                    // toast should not have to open the API docs to act on it.
                    throw new IllegalArgumentException(
                        "A " + displayName.toLowerCase(java.util.Locale.ROOT) + " item needs a '"
                        + field + "' in its payload. " + why);
                }
            }
        };
    }
}
