package com.xenopsoftware.learn.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * Turning virtual threads on must not quietly unbind the tenant from async work (ADR-0111, T-1.1).
 *
 * <h2>Why this is a test rather than a paragraph</h2>
 *
 * {@code spring.threads.virtual.enabled} replaces the executor behind {@code @Async} entirely:
 * {@code ThreadPoolTaskExecutor} becomes {@code SimpleAsyncTaskExecutor}, built by a different
 * auto-configuration path. Boot applies the single {@link org.springframework.core.task.TaskDecorator}
 * bean on both paths — but "Boot happens to do this on both paths" is a fact about a version, and
 * {@link TenancyConfiguration}'s whole design rests on it: the decorator is registered as a bean
 * precisely so that no service can enable async execution without also getting tenant propagation.
 *
 * <p>If a future Boot drops the decorator from the virtual-thread builder, nothing fails. Every
 * test that does not look at the tenant still passes. What happens instead is that an
 * {@code @Async} method starts running with no tenant bound — {@link TenantContext#require()}
 * throws in production for work that ran fine yesterday — or, on the paths that tolerate a missing
 * tenant, reads nothing where it should read something. That is a bad way to find out.
 *
 * <p>So the assertion is on the wiring, not on the documentation.
 */
class TenantTaskDecoratorSurvivesVirtualThreadsTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
        .withUserConfiguration(TenancyConfiguration.class);

    @Test
    void theTenantCrossesTheSubmitGapOnVirtualThreads() {
        contexts.withPropertyValues("spring.threads.virtual.enabled=true")
            .run(context -> assertThat(tenantSeenByWorkSubmittedAs("acme", context.getBean(AsyncTaskExecutor.class)))
                .as("the tenant bound at submit time must be bound inside the task")
                .isEqualTo("acme"));
    }

    /**
     * The same assertion with virtual threads off. Not redundant: it is what tells you, when the
     * test above fails, whether the decorator broke or only the virtual-thread path did.
     */
    @Test
    void andOnPlatformThreads() {
        contexts.withPropertyValues("spring.threads.virtual.enabled=false")
            .run(context -> assertThat(tenantSeenByWorkSubmittedAs("acme", context.getBean(AsyncTaskExecutor.class)))
                .isEqualTo("acme"));
    }

    /**
     * Work submitted with nothing bound must arrive with nothing bound. The decorator captures
     * {@code null} faithfully rather than inheriting, and a virtual thread is fresh per task, so
     * this is the easy direction — which is exactly why it is worth asserting before somebody
     * "fixes" the null case by falling back to a default tenant.
     */
    @Test
    void andWorkSubmittedOutsideARequestCarriesNoTenant() {
        contexts.withPropertyValues("spring.threads.virtual.enabled=true")
            .run(context -> assertThat(tenantSeenByWorkSubmittedAs(null, context.getBean(AsyncTaskExecutor.class)))
                .isNull());
    }

    /**
     * The leak, on a thread that is genuinely reused.
     *
     * <p>This assertion used to live in identity's {@code AsyncTenantPropagationTest}, pinned to a
     * one-thread pool. Virtual threads made it unreachable there — {@code @Async} now starts a
     * fresh thread per task, so "the previous task's tenant is still bound" cannot happen on that
     * path, and the test failed on the thread name while every tenant assertion in it passed.
     *
     * <p>It is here rather than deleted because the property did not stop mattering, it stopped
     * being reachable through {@code @Async}. {@link TenancyConfiguration} is explicit that
     * anything building its own executor must apply this decorator itself, and every one of those
     * is a pool. So the decorator is exercised directly, against a single platform thread reused
     * across two tasks, which is exactly the shape that leaks without the {@code finally}.
     */
    @Test
    void aReusedThreadIsCleanAfterATenantsTaskRanOnIt() throws Exception {
        TaskDecorator decorator = new TenantTaskDecorator();
        ExecutorService oneThread = Executors.newSingleThreadExecutor();
        try {
            TenantContext.set("globex");
            Runnable bound = decorator.decorate(() -> firstSeen.set(TenantContext.get()));
            TenantContext.clear();
            Runnable unbound = decorator.decorate(() -> secondSeen.set(TenantContext.get()));

            oneThread.submit(bound).get(5, TimeUnit.SECONDS);
            oneThread.submit(unbound).get(5, TimeUnit.SECONDS);
        } finally {
            oneThread.shutdownNow();
            TenantContext.clear();
        }

        assertThat(firstSeen.get()).isEqualTo("globex");
        assertThat(secondSeen.get())
            .as("without the decorator's finally, the second task inherits globex from the "
                + "thread the first one ran on -- the leak that only shows under load")
            .isNull();
    }

    private final AtomicReference<String> firstSeen = new AtomicReference<>();
    private final AtomicReference<String> secondSeen = new AtomicReference<>();

    private static String tenantSeenByWorkSubmittedAs(String tenant, AsyncTaskExecutor executor) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        if (tenant != null) {
            TenantContext.set(tenant);
        }
        try {
            executor.execute(() -> {
                seen.set(TenantContext.get());
                done.countDown();
            });
        } finally {
            TenantContext.clear();
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).as("the task ran").isTrue();
        return seen.get();
    }
}
