package com.xenopsoftware.learn.identity.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes a POST safe to retry (T-1.5).
 *
 * <p>The mechanism T-1.5 assumed existed. A client sends {@code Idempotency-Key}; the first
 * request runs and its response is stored against the key, and every later request with that key
 * gets the stored response instead of a second execution. Which matters most for exactly the
 * operation this was built for: a timed-out provisioning call, retried by a client, would
 * otherwise create a second company.
 *
 * <h2>Three cases, and the third is the one usually got wrong</h2>
 *
 * A new key runs. A completed key replays. A key that exists with no stored response is a
 * request still in flight, and is answered with 409 rather than executed — running it again is
 * precisely what the key was sent to prevent.
 *
 * <p>The body is fingerprinted, so reusing a key with a different payload is refused rather than
 * answered with the first request result. That is a client bug, and returning somebody else
 * success would hide the bug and the wrong outcome together.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 90)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);

    private static final String IN_FLIGHT =
        "{\"error\":\"This idempotency key is in flight; retry shortly.\"}";
    private static final String REUSED =
        "{\"error\":\"This idempotency key was already used for a different request.\"}";

    private final JdbcTemplate jdbc;

    public IdempotencyFilter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only writes, and only when the client asked for the guarantee.
        return !"POST".equalsIgnoreCase(request.getMethod())
            || request.getHeader(HEADER) == null
            || request.getHeader(HEADER).isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        ContentCachingRequestBody cached = new ContentCachingRequestBody(request);
        String fingerprint = fingerprint(request, cached.body());

        try {
            jdbc.update("""
                INSERT INTO idempotency_record (idempotency_key, request_fingerprint)
                VALUES (?, ?)
                """, key, fingerprint);
        } catch (DuplicateKeyException alreadySeen) {
            replay(key, fingerprint, response);
            return;
        }

        ContentCachingResponseWrapper recorder = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(cached, recorder);
            completed = true;
        } finally {
            if (completed) {
                String body = new String(recorder.getContentAsByteArray(), StandardCharsets.UTF_8);
                jdbc.update("""
                    UPDATE idempotency_record
                       SET response_status = ?, response_body = ?, completed_at = now()
                     WHERE idempotency_key = ?
                    """, recorder.getStatus(), body, key);
            } else {
                // The request threw rather than answering. Releasing the key lets the client
                // retry; holding it would turn one failure into a permanent one.
                jdbc.update(
                    "DELETE FROM idempotency_record WHERE idempotency_key = ? AND completed_at IS NULL",
                    key);
            }
            recorder.copyBodyToResponse();
        }
    }

    private void replay(String key, String fingerprint, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> stored = jdbc.queryForList("""
            SELECT request_fingerprint, response_status, response_body
              FROM idempotency_record WHERE idempotency_key = ?
            """, key);
        if (stored.isEmpty()) {
            // Rare: the first attempt failed and released the key between our insert and this
            // read. Treated as in flight, which is the safe direction.
            answer(response, 409, IN_FLIGHT);
            return;
        }
        Map<String, Object> record = stored.getFirst();
        if (!fingerprint.equals(record.get("request_fingerprint"))) {
            LOG.warn("Idempotency key {} reused with a different request body", key);
            answer(response, 422, REUSED);
            return;
        }
        Object status = record.get("response_status");
        if (status == null) {
            answer(response, 409, IN_FLIGHT);
            return;
        }
        answer(response, (Integer) status, (String) record.get("response_body"));
    }

    private static void answer(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body == null ? "" : body);
    }

    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not optional", e);
        }
    }
}
