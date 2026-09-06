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
 * A worker thread does not inherit a {@code ThreadLocal}, so T-1.1 requires the tenant to be
 * carried across to async work explicitly — and proved, not assumed.
 *
 * <h2>What changed here when virtual threads were turned on (ADR-0111)</h2>
 *
 * This test used to pin the executor to a single pooled worker
 * ({@code spring.task.execution.pool.core-size=1}) and assert that the <b>same thread</b> saw its
 * own tenant on one submission and none on the next. That was the sharpest possible statement of
 * the leak it exists to prevent, and it is no longer expressible: with
 * {@code spring.threads.virtual.enabled}, {@code @Async} runs on {@code SimpleAsyncTaskExecutor},
 * which ignores the pool properties and starts a <b>fresh virtual thread per task</b>. The
 * assertion failed on exactly that — {@code expected: "task-3" but was: "task-4"} — while every
 * tenant assertion in it still passed.
 *
 * <p>The honest reading of that failure is not "adjust the test". It is that <b>the leak this
 * guarded against cannot occur on this path any more</b>, because nothing is reused. Asserting a
 * property that has become unreachable would be asserting the executor's implementation, not the
 * platform's invariant.
 *
 * <p>So the property moved rather than disappeared. It still matters wherever a thread genuinely
 * is reused — {@link com.xenopsoftware.learn.common.tenancy.TenancyConfiguration} is explicit that
 * anything building its own executor must apply the decorator itself — and it is now asserted
 * against a deliberately reused thread in {@code TenantTaskDecoratorSurvivesVirtualThreadsTest},
 * next to the decorator. What stays here is what this test is uniquely placed to prove: that the
 * <b>wired application</b>, with its real configuration, carries the tenant across {@code @Async}.
 */
@SpringBootTest
class AsyncTenantPropagationTest extends PostgresTestHarness {

    static class Probe {
        /** What the worker thread observes: {@code threadName|virtual|tenant}. */
        @Async
        public CompletableFuture<String> observed() {
            Thread worker = Thread.currentThread();
            return CompletableFuture.completedFuture(
                worker.getName() + "|" + worker.isVirtual() + "|" + TenantContext.get());
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

    /**
     * Work submitted with nothing bound must arrive with nothing bound.
     *
     * <p>Still worth asserting after the change above, and for a reason the pooled version
     * obscured: this is the case that a well-meaning "fall back to a default tenant" would break,
     * and it would break it identically on pooled and virtual threads. {@code require()} failing
     * loudly is the intended behaviour for tenant-scoped work that arrives without one.
     */
    @Test
    void andWorkSubmittedOutsideARequestCarriesNoTenant() throws Exception {
        assertThat(probe.observed().get()).endsWith("|null");
    }

    /**
     * The executor really is the virtual-thread one, so the two assertions above are about the
     * configuration this service ships rather than about a test-only default.
     *
     * <p>Named explicitly because the failure it guards against is silent: if
     * {@code spring.threads.virtual.enabled} were dropped from application.yml, every test here
     * would still pass, and the only visible difference would be throughput under load.
     */
    @Test
    void andItRunsOnAVirtualThread() throws Exception {
        String observed = TenantContext.callWith("acme", () -> probe.observed().get());
        assertThat(virtualFlagOf(observed))
            .as("asked of the thread itself, not inferred from its name: ThreadPoolTaskExecutor "
                + "names its workers `task-N` too, so a name check would pass with virtual "
                + "threads switched off")
            .isEqualTo("true");
        assertThat(observed).endsWith("|acme");
    }

    private static String threadOf(String observed) {
        return observed.substring(0, observed.indexOf('|'));
    }

    /** The middle field of {@code threadName|virtual|tenant}. */
    private static String virtualFlagOf(String observed) {
        int first = observed.indexOf('|');
        return observed.substring(first + 1, observed.indexOf('|', first + 1));
    }
}
