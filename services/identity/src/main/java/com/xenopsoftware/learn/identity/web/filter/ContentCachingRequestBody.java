package com.xenopsoftware.learn.identity.web.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the body once, so it can be both fingerprinted and handled.
 *
 * <p>Spring own ContentCachingRequestWrapper caches what the application reads, which is too
 * late for a filter needing the bytes BEFORE the request is handled. This reads them up front
 * and replays them downstream.
 *
 * <p>Safe here because the only bodies this filter sees are small JSON documents: it is bound to
 * requests carrying an idempotency key, and video never travels through a request thread in this
 * platform at all (ADR-0101, T-3.2).
 */
class ContentCachingRequestBody extends HttpServletRequestWrapper {

    private final byte[] body;

    ContentCachingRequestBody(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream replay = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return replay.read();
            }

            @Override
            public boolean isFinished() {
                return replay.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("This request is replayed, not streamed");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
