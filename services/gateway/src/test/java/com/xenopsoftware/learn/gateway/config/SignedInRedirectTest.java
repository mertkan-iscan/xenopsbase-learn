package com.xenopsoftware.learn.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * Where a person lands after signing in (T-10.2).
 *
 * <p>THIS TEST EXISTS BECAUSE IT ALREADY HAPPENED ON THE CLUSTER. Spring's default success handler
 * returns the saved request's absolute URL, rebuilt from scheme, host and port as the gateway saw
 * them. Behind the tunnel and the ingress that produced
 * {@code https://learn-dev.xenopsoftware.com:80/} — the scheme from one forwarded header and the
 * port from another, disagreeing — and the browser answered ERR_SSL_PROTOCOL_ERROR after a
 * successful login.
 *
 * <p>The address is built from the configured app origin now, so the headers cannot decide it.
 */
class SignedInRedirectTest {

    private static final String APP = "https://learn-dev.xenopsoftware.com";

    private final SecurityConfiguration configuration = new SecurityConfiguration(
        new GatewayProperties("http://identity:8082", "http://streaming:8083",
            "http://reporting:8084", "http://catalog:8085", "http://assessment:8086",
            "http://packaging:8087", APP));

    /** A request as the gateway sees it behind the proxy: https claimed, port 80 reported. */
    private static MockHttpServletRequest asTheProxyPresentsIt(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setScheme("https");
        request.setServerName("learn-dev.xenopsoftware.com");
        request.setServerPort(80);
        return request;
    }

    private String redirectAfterSigningIn(MockHttpServletRequest signIn) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        configuration.backToTheApp(signIn, response, null);
        return response.getRedirectedUrl();
    }

    @Nested
    @DisplayName("with nothing saved")
    class WithNothingSaved {

        @Test
        @DisplayName("lands on the app's own origin, never one built from the request")
        void landsOnTheAppOrigin() throws Exception {
            String where = redirectAfterSigningIn(asTheProxyPresentsIt("/login/oauth2/code/oidc"));

            assertThat(where).isEqualTo(APP + "/");
        }

        @Test
        @DisplayName("never names a port, which is the whole failure")
        void neverNamesAPort() throws Exception {
            String where = redirectAfterSigningIn(asTheProxyPresentsIt("/login/oauth2/code/oidc"));

            // https://host:80 is a browser speaking TLS to a plaintext port. The assertion is on
            // the string rather than on a parsed URI because that is what a browser is handed.
            assertThat(where).doesNotContain(":80").doesNotContain(":8080");
        }
    }

    @Nested
    @DisplayName("with a deep link saved")
    class WithADeepLinkSaved {

        private MockHttpServletRequest signInAfterAsking(String path, String query) {
            MockHttpServletRequest original = asTheProxyPresentsIt(path);
            original.setQueryString(query);
            MockHttpServletRequest signIn = asTheProxyPresentsIt("/login/oauth2/code/oidc");
            signIn.setSession(original.getSession());
            new HttpSessionRequestCache().saveRequest(original, new MockHttpServletResponse());
            return signIn;
        }

        @Test
        @DisplayName("keeps the path, so a learner comes back to what they asked for")
        void keepsThePath() throws Exception {
            MockHttpServletRequest signIn = signInAfterAsking("/watch/a-node-id", null);

            assertThat(redirectAfterSigningIn(signIn)).isEqualTo(APP + "/watch/a-node-id");
        }

        @Test
        @DisplayName("keeps the query with it")
        void keepsTheQuery() throws Exception {
            MockHttpServletRequest signIn = signInAfterAsking("/review/an-attempt", "from=home");

            assertThat(redirectAfterSigningIn(signIn))
                .isEqualTo(APP + "/review/an-attempt?from=home");
        }

        @Test
        @DisplayName("replaces the saved origin, which is the part the gateway cannot observe")
        void replacesTheSavedOrigin() throws Exception {
            MockHttpServletRequest signIn = signInAfterAsking("/watch/a-node-id", null);
            HttpServletRequest saved = signIn;
            DefaultSavedRequest cached = (DefaultSavedRequest) new HttpSessionRequestCache()
                .getRequest(saved, new MockHttpServletResponse());

            // What Spring would have sent, and what we send instead.
            assertThat(cached.getRedirectUrl()).contains(":80");
            assertThat(redirectAfterSigningIn(signIn)).doesNotContain(":80");
        }
    }
}
