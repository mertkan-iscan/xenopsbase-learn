package com.xenopsoftware.learn.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The one error shape, asserted (T-9.13).
 *
 * <p>This is the sort of thing that drifts without anybody noticing, which is how the platform came
 * to have five shapes and a comment claiming it had one. A shape nothing asserts is a convention,
 * and a convention holds until the next person writes a handler.
 */
class ProblemsTest {

    @Test
    @DisplayName("a coded refusal carries type, title, status, detail and the short code")
    void aCodedRefusalIsAWholeProblemDocument() {
        ProblemDetail problem = Problems.of(HttpStatus.FORBIDDEN, "PLAYBACK_NOT_ENTITLED", "You are not entitled to this.");

        assertThat(problem.getStatus()).isEqualTo(403);
        // Derived from the code, so a new refusal cannot ship with a type somebody forgot to mint.
        assertThat(problem.getType()).hasToString("https://xenopsoftware.com/problems/playback-not-entitled");
        // A summary of the TYPE. `detail` is what is specific to this occurrence.
        assertThat(problem.getTitle()).isEqualTo("Playback not entitled");
        assertThat(problem.getDetail()).isEqualTo("You are not entitled to this.");
        // The extension member clients switch on, because switching on a URI invites prefix
        // matching and string surgery.
        assertThat(problem.getProperties()).containsEntry(Problems.CODE, "PLAYBACK_NOT_ENTITLED");
    }

    @Test
    @DisplayName("an uncoded refusal is about:blank rather than a code somebody invented")
    void anUncodedRefusalDoesNotInventAnIdentity() {
        // What catalog's ResponseStatusException handler produces: a status and a sentence, chosen
        // deliberately, with no machine-readable identity behind them. RFC 9457 has a word for
        // that and it is about:blank -- minting a type here would be asserting a contract nobody
        // wrote.
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, null, "A PUBLISHED item cannot become DRAFT.");

        assertThat(problem.getType()).hasToString("about:blank");
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getProperties() == null || !problem.getProperties().containsKey(Problems.CODE))
            .as("no empty code, which a client would read as a code")
            .isTrue();
    }

    @Test
    @DisplayName("the response carries application/problem+json, which is the point of using the RFC")
    void theResponseUsesTheRegisteredMediaType() {
        var response = Problems.respond(HttpStatus.BAD_REQUEST, "MALFORMED_BATCH", "This batch could not be read.");

        // A client library, a proxy or a person with curl already knows what this media type means.
        // A house shape served as application/json tells them nothing.
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("a filter writes the same document, because a filter answers before any advice can")
    void aFilterWritesTheSameShape() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Problems.write(response, HttpStatus.UNAUTHORIZED, "SERVICE_CREDENTIAL_INVALID", "The calling service could not be authenticated.");

        assertThat(response.getStatus()).isEqualTo(401);
        // Compared as a media type, not as a string: the header also carries the charset, and an
        // exact-string assertion here fails the moment somebody sets one -- which is exactly what
        // happened when the UTF-8 fix below went in.
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .isTrue();
        // Hand-written JSON, so this asserts the actual bytes rather than trusting a mapper. Boot 4
        // ships two Jacksons and a filter picking the wrong one produces a body that passes a unit
        // test and fails in the container.
        assertThat(response.getContentAsString())
            .isEqualTo(
                "{\"type\":\"https://xenopsoftware.com/problems/service-credential-invalid\"," +
                "\"title\":\"Service credential invalid\",\"status\":401," +
                "\"detail\":\"The calling service could not be authenticated.\"," +
                "\"code\":\"SERVICE_CREDENTIAL_INVALID\"}"
            );
    }

    @Test
    @DisplayName("the filter path declares UTF-8, because the servlet default is not")
    void theFilterDeclaresUtf8() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // FOUND ON THE WIRE, NOT HERE. setContentType with no charset leaves the container to
        // choose, and Tomcat chooses ISO-8859-1 -- the deployed service answered
        // `application/problem+json;charset=ISO-8859-1` while every unit test passed, because
        // MockHttpServletResponse does not reproduce that default.
        //
        // detail interpolates domain values, so the consequence is a mangled course title on an
        // error path: found late, and blamed on whatever produced the text.
        Problems.write(response, HttpStatus.CONFLICT, "GATED", "Le module « Introduction » est verrouillé.");

        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        // Round-tripped through the bytes rather than read as a String, which is where an encoding
        // mistake actually shows.
        assertThat(new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
            .contains("« Introduction »")
            .contains("verrouillé");
    }

    @Test
    @DisplayName("a detail containing a quote does not produce broken JSON")
    void detailIsEscaped() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Detail text is ours, but it interpolates domain values -- a course title, a node name --
        // and those are not. An unescaped quote here is a malformed body on a path that only runs
        // when something has already gone wrong.
        Problems.write(response, HttpStatus.CONFLICT, "GATED", "The node \"Intro\" is locked\nuntil Monday.");

        assertThat(response.getContentAsString())
            .contains("\\\"Intro\\\"")
            .contains("\\n")
            .doesNotContain("\n");
    }
}
