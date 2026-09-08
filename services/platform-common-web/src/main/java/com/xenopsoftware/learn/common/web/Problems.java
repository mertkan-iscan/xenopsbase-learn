package com.xenopsoftware.learn.common.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * One error shape for every refusal this platform writes: RFC 9457 (T-9.13).
 *
 * <h2>There were five, and a comment claiming there was one</h2>
 *
 * A client talking to these services had to parse, depending on which one answered:
 *
 * <pre>
 * {"error": {"code": "...", "message": "..."}}   reporting, streaming, two shared filters
 * {"status": 409, "message": "..."}              catalog
 * {"reason": "...", "message": "..."}            identity, impersonation
 * {"message": "..."}                             identity, deactivated user
 * (empty)                                        identity, access denied
 * </pre>
 *
 * <p>{@code RefusalAdvice} said its shape "matches the refusals identity writes from its filters",
 * which was not true of any of them. That is the ordinary way this happens: each shape was
 * reasonable where it was written, nobody could see the others, and the comment asserting
 * consistency was the only thing keeping track.
 *
 * <h2>Why RFC 9457 and not a sixth house shape</h2>
 *
 * It is a standard with a registered media type, so a client library, a proxy or a human debugging
 * with curl already knows what {@code status}, {@code title} and {@code detail} mean.
 * xenopsbase-stemcell — the infrastructure this repository is built to be forked into — already
 * answers in it, so a fork ends up with one error contract rather than a seam between two.
 *
 * <p>The stemcell reaches RFC 9457 through jhipster's {@code ExceptionTranslator}. This does not:
 * pulling the jhipster framework into a repository that is not a jhipster application to get a
 * response shape would be the wrong trade. Spring's own {@link ProblemDetail} is the format, and
 * the format is the part that has to match.
 *
 * <h2>{@code code} survives as an extension member, deliberately</h2>
 *
 * RFC 9457 says {@code type} is the machine-readable identifier, and it is set here. But a URI is
 * an awkward thing for a client to switch on — it invites prefix-matching and string surgery — so
 * the short token that callers already branch on ({@code PLAYBACK_NOT_ENTITLED},
 * {@code MALFORMED_BATCH}) is kept beside it as an extension member. The RFC provides for exactly
 * this, and it is why the player's {@code usePlaybackToken} reads one field rather than parsing a
 * URI.
 */
public final class Problems {

    /** The extension member carrying the short, switchable code. */
    public static final String CODE = "code";

    /**
     * Types are URIs under a domain this project controls, and they do not have to resolve —
     * RFC 9457 says so. What matters is that they are stable and unambiguous, which a bare token
     * in a shared namespace is not.
     */
    private static final String TYPE_BASE = "https://xenopsoftware.com/problems/";

    private Problems() {}

    /**
     * @param status the HTTP status; also the {@code status} member
     * @param code the short machine-readable token, e.g. {@code PLAYBACK_NOT_ENTITLED}
     * @param detail what went wrong THIS time, written for whoever is on the other end
     */
    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(typeFor(code));
        problem.setTitle(titleFor(status, code));
        if (detail != null && !detail.isBlank()) {
            problem.setDetail(detail);
        }
        if (code != null && !code.isBlank()) {
            problem.setProperty(CODE, code);
        }
        return problem;
    }

    /** The same document as a response, with the media type the RFC registers. */
    public static ResponseEntity<ProblemDetail> respond(HttpStatusCode status, String code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(of(status, code, detail));
    }

    /**
     * For a servlet filter, which answers before any {@code @ControllerAdvice} can see the request.
     *
     * <p>Written by hand rather than through an ObjectMapper, and that is not laziness. Boot 4
     * ships two Jacksons — {@code tools.jackson} for web serialisation and
     * {@code com.fasterxml.jackson} underneath — and a filter that picks the wrong one produces a
     * body that looks right in a unit test and fails in the container. The document has four
     * fields and no user-supplied structure; the only values that vary are escaped below.
     */
    public static void write(HttpServletResponse response, HttpStatusCode status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // UTF-8 EXPLICITLY, because the servlet default is not.
        //
        // setContentType with no charset leaves the container to pick one, and Tomcat picks
        // ISO-8859-1. Observed on the wire against the deployed service:
        //
        //   Content-Type: application/problem+json;charset=ISO-8859-1
        //
        // `detail` interpolates domain values -- a course title, a node name, a person's name --
        // so that mangles any non-ASCII character, on an error path, where it would be found late
        // and blamed on whatever produced the text. MockHttpServletResponse does not reproduce the
        // container's default, which is why the unit tests were happy and the wire was not.
        //
        // The @ControllerAdvice path does not need this: Spring's message converters write
        // ProblemDetail as UTF-8 already. Only a filter writing the response itself has to say so.
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response
            .getWriter()
            .write(
                "{\"type\":\"" +
                escape(typeFor(code).toString()) +
                "\",\"title\":\"" +
                escape(titleFor(status, code)) +
                "\",\"status\":" +
                status.value() +
                ",\"detail\":\"" +
                escape(detail == null ? "" : detail) +
                "\",\"" +
                CODE +
                "\":\"" +
                escape(code == null ? "" : code) +
                "\"}"
            );
    }

    private static URI typeFor(String code) {
        if (code == null || code.isBlank()) {
            // The RFC's own default, and honest: a refusal with no code has no identified type.
            return URI.create("about:blank");
        }
        return URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /**
     * A summary of the TYPE, not of this occurrence — that is what {@code detail} is for. Derived
     * from the code so a new refusal cannot ship with a title somebody forgot to write, and falls
     * back to the status reason when there is no code.
     */
    private static String titleFor(HttpStatusCode status, String code) {
        if (code == null || code.isBlank()) {
            HttpStatus resolved = HttpStatus.resolve(status.value());
            return resolved == null ? "Request failed" : resolved.getReasonPhrase();
        }
        String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The minimum JSON string escaping. Detail text is ours, but it interpolates domain values. */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
