package com.xenopsoftware.learn.gateway.config;

import com.xenopsoftware.learn.common.messaging.Correlation;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Bridges the correlation id between the Reactor context and MDC (T-9.13).
 *
 * <p>In a reactive process the two are not the same thing and neither works alone. The Reactor
 * context follows the request across threads but logback cannot read it; MDC is what
 * {@code %X{correlationId}} reads, but it is thread-local and WebFlux moves a request between
 * threads whenever it pleases.
 *
 * <p>Registering a {@link ThreadLocalAccessor} tells Micrometer's context propagation how to
 * restore the id into MDC around each operator, so a log line written from any scheduler carries
 * it. Without this the pattern renders an empty field — the logs look fine and simply have
 * nothing in that column, which is a hard thing to notice and an easy thing to assume is working.
 *
 * <p>{@code Hooks.enableAutomaticContextPropagation()} applies it without every operator opting
 * in. It costs a restore per operator, and it is paid deliberately: logs that cannot be correlated
 * are only useful when exactly one request is in flight, which is never true of the edge.
 *
 * <p>Forked from the stemcell's, with one substantive change — the key is
 * {@link Correlation#MDC_KEY} rather than a constant declared here. The gateway must agree with
 * the eight services on what the field is called, and the only way to guarantee that is to read
 * it from the module they share (ADR-0111).
 */
@Configuration(proxyBeanMethods = false)
public class CorrelationContextConfiguration {

    @PostConstruct
    public void registerCorrelationIdAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcCorrelationAccessor());
        Hooks.enableAutomaticContextPropagation();
    }

    /**
     * Moves one MDC entry, not the whole map.
     *
     * <p>Propagating all of MDC would also carry values a downstream operator never set, which
     * produces log lines attributed to the wrong request — subtler and more damaging than having
     * no id at all, because those lines look correct.
     */
    static final class MdcCorrelationAccessor implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return Correlation.MDC_KEY;
        }

        @Override
        public String getValue() {
            return MDC.get(Correlation.MDC_KEY);
        }

        @Override
        public void setValue(String value) {
            MDC.put(Correlation.MDC_KEY, value);
        }

        @Override
        public void setValue() {
            // Called when the context has no value: the thread must be left clean, or the id
            // leaks into the next request that happens to reuse it.
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
