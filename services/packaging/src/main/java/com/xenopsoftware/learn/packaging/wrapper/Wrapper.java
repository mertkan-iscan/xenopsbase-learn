package com.xenopsoftware.learn.packaging.wrapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wrapper document, with this launch's configuration in it (ADR-0105, T-4.3).
 *
 * <h2>Why this file is served by this service and not shipped with the frontend</h2>
 *
 * <p>ADR-0105 describes the wrapper as "deployed with the frontend but served from the content
 * origin", and the second half is the load-bearing one: it has to be on the tenant's content
 * origin, because a wrapper on the application's origin would put the package's discovery walk one
 * {@code window.parent} away from the application's DOM — which is the entire attack the decision
 * prevents.
 *
 * <p>The content origin's routes come here. So the wrapper is a resource in this jar, and the
 * consequence worth naming is that it is versioned with this service rather than with the web
 * application. If it later has to ship with the frontend — because it grows enough to want the
 * frontend's tooling — the route stays where it is and the file moves behind it.
 *
 * <h2>The configuration goes in as JSON, not as substituted JavaScript</h2>
 *
 * <p>Four values are per-launch: the entry URL, the profile, the package id and the application's
 * origin. Pasting them into a script body means every one of them is an injection site — and one
 * of them, the entry URL, contains a path an <em>archive</em> chose. Serialised as JSON into a
 * {@code <script type="application/json">} block and read back with {@code JSON.parse}, they are
 * data in a place no parser will execute, and the only escaping that matters is the one below.
 */
@Component
public class Wrapper {

    private static final String TEMPLATE = "wrapper/scorm-wrapper.html";
    private static final String PLACEHOLDER = "{{LAUNCH_JSON}}";

    private final JsonMapper json = JsonMapper.builder().build();
    private final String template;

    public Wrapper() {
        try (var stream = new ClassPathResource(TEMPLATE).getInputStream()) {
            this.template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            // A packaging build without its wrapper cannot serve a single course, so failing at
            // startup is right: the alternative is a service that looks healthy and 500s the
            // first time a learner opens a SCORM package.
            throw new UncheckedIOException("The wrapper template " + TEMPLATE + " is missing from "
                + "the packaging jar", missing);
        }
        if (!template.contains(PLACEHOLDER)) {
            throw new IllegalStateException("The wrapper template no longer contains "
                + PLACEHOLDER + ", so a launch would be configured with nothing");
        }
    }

    /**
     * @param entryUrl    the absolute URL of the package's own entry file, on the content origin
     * @param profile     {@code scorm-1.2}, {@code scorm-2004}, {@code cmi5}, or null for slides —
     *                    which decides which API object, if any, the package will find
     * @param appOrigin   the ONLY origin this document will post to or accept a message from,
     *                    compared by string equality on both ends
     */
    public String html(String entryUrl, String profile, UUID packageId, String appOrigin,
            String contentOrigin) {
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("entryUrl", entryUrl);
        launch.put("profile", profile);
        launch.put("packageId", packageId.toString());
        launch.put("appOrigin", appOrigin);
        launch.put("contentOrigin", contentOrigin);
        return template.replace(PLACEHOLDER, escapeForScriptElement(json.writeValueAsString(launch)));
    }

    /**
     * The one escape a JSON island still needs.
     *
     * <p>JSON inside a {@code <script>} element is parsed by the HTML tokeniser first, and the
     * tokeniser is looking for {@code </script}. A value containing that string would close the
     * element early and everything after it would be markup — which, since one of these values is
     * a path chosen by an uploaded archive, is a script-injection primitive. Breaking the sequence
     * with a backslash keeps the JSON identical to a parser and invisible to the tokeniser.
     *
     * <p>{@code <!--} is escaped for the same reason: it starts a comment the tokeniser honours
     * inside a script element.
     */
    private static String escapeForScriptElement(String encoded) {
        return encoded.replace("</", "<\\/").replace("<!--", "<\\!--");
    }
}
