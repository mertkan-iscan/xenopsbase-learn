package com.xenopsoftware.learn.gateway.web.filter;

import com.xenopsoftware.learn.common.messaging.Correlation;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * The reactive twin of {@code CorrelationFilter} (T-9.8, T-9.13, ADR-0111).
 *
 * <p>Same header, same MDC key, same rule about which inbound values may be adopted — all four
 * read from {@link Correlation} in {@code platform-common}, because an edge that mints
 * {@code X-Correlation-Id} while the services read something else produces a platform where every
 * service has an id and no two of them agree on it.
 *
 * <p>Runs first. Anything logged by a filter ordered ahead of this one has no id, and an id
 * assigned only to requests that got past authentication is missing exactly when it is wanted —
 * a request refused at the door is a request somebody still has to trace.
 *
 * <h2>Why the Reactor context and not just MDC</h2>
 *
 * MDC is thread-local and WebFlux hands one request between threads freely, so an id set here and
 * read later appears on some log lines and not others depending on which scheduler ran that
 * operator. The gaps are invisible and read as missing requests rather than as a broken field. So
 * the id goes into the Reactor context, which travels with the request, and
 * {@code CorrelationContextConfiguration} restores it into MDC around each operator.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String inbound = exchange.getRequest().getHeaders().getFirst(Correlation.HEADER);
        String id = Correlation.isAcceptable(inbound) ? inbound : UUID.randomUUID().toString();

        // Written onto the REQUEST, not merely held here: Spring Cloud Gateway forwards request
        // headers to the routed service, so this is what carries the id inward to be picked up by
        // CorrelationFilter. Held only in the context, the id would stop at this process and each
        // service would mint its own -- four ids for one request, which is worse than none,
        // because it looks like it is working.
        ServerHttpRequest request = exchange.getRequest().mutate()
            .header(Correlation.HEADER, id)
            .build();

        // Echoed, so a caller can quote it in a support ticket without reading their own logs.
        exchange.getResponse().getHeaders().set(Correlation.HEADER, id);

        return chain
            .filter(exchange.mutate().request(request).build())
            .contextWrite(Context.of(Correlation.MDC_KEY, id))
            // The context is gone by the time this runs, so the id is captured by the lambda.
            // Without it the completion line -- the one carrying status and duration -- is the
            // single most useful log line and the only one missing its id.
            .doFinally(signal -> MDC.remove(Correlation.MDC_KEY));
    }
}
