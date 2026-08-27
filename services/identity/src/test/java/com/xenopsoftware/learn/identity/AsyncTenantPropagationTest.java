package com.xenopsoftware.learn.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;

/**
 * A thread pool does not inherit a {@code ThreadLocal}, so T-1.1 requires the tenant to be
 * carried across to async work explicitly — and proved, not assumed.
 *
 * <p>The pool is pinned to one worker thread. That is what turns "the tenant was there" from a
 * lucky draw on a fresh thread into the two properties that matter: the same pooled thread sees
 * each submission's own tenant, and a submission with no tenant sees none — rather than whatever
 * the previous task left behind.
 */
@SpringBootTest(properties = {
    "spring.task.execution.pool.core-size=1",
    "spring.task.execution.pool.max-size=1"
})
class AsyncTenantPropagationTest extends PostgresTestHarness {

    static class Probe {
        /** What the worker thread observes: {@code threadName|tenant}. */
        @Async
        public CompletableFuture<String> observed() {
            return CompletableFuture.completedFuture(
                Thread.currentThread().getName() + "|" + TenantContext.get());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @Autowired
    private Probe probe;

    @Test
    void anAsyncMethodSeesTheSubmittersTenant() throws Exception {
        String observed = TenantContext.callWith("acme", () -> probe.observed().get());
        assertThat(observed).endsWith("|acme");
        assertThat(threadOf(observed)).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void thePooledWorkerIsCleanAfterATenantsTaskRanOnIt() throws Exception {
        String bound = TenantContext.callWith("globex", () -> probe.observed().get());
        assertThat(bound).endsWith("|globex");

        // Same single worker thread, next task, no tenant at the submit site. Without the
        // decorator's finally this would read "globex" -- the leak that only shows under load.
        String unbound = probe.observed().get();
        assertThat(threadOf(unbound)).isEqualTo(threadOf(bound));
        assertThat(unbound).endsWith("|null");
    }

    private static String threadOf(String observed) {
        return observed.substring(0, observed.indexOf('|'));
    }
}
