package com.xenopsoftware.learn.common.tenancy;

import org.springframework.core.task.TaskDecorator;

/**
 * Carries the tenant across the submit-to-worker gap (T-1.1).
 *
 * <p>A {@code ThreadLocal} is not inherited by a thread pool, so an {@code @Async} method — or
 * anything else handed to an executor — runs with no tenant bound and {@code require()} fails, or
 * worse, runs with whatever tenant the previous task on that pooled thread leaked. This decorator
 * closes both holes: the tenant is read <b>at submit time, on the request thread</b>, bound in the
 * worker for the duration of the task, and unbound in a {@code finally} so the pooled thread is
 * clean for whoever gets it next.
 *
 * <p>It captures {@code null} faithfully: work submitted outside any tenant (a scheduled job, a
 * startup hook) runs with no tenant bound, and anything tenant-scoped it touches fails loudly via
 * {@link TenantContext#require()} instead of inheriting a stranger's tenant from the pool.
 */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        // On the submitting thread, while the request's tenant is still bound.
        String tenant = TenantContext.get();
        return () -> {
            if (tenant != null) {
                TenantContext.set(tenant);
            }
            try {
                task.run();
            } finally {
                // Same reasoning as TenantFilter: pooled threads outlive the task,
                // and an unbound-on-exit guarantee is what makes the pool safe.
                TenantContext.clear();
            }
        };
    }
}
