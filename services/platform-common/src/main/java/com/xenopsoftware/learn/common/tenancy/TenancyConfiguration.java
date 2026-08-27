package com.xenopsoftware.learn.common.tenancy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Async work is tenant-safe by default, in every service (T-1.1).
 *
 * <p>{@code @EnableAsync} lives here rather than in each service so that no service can enable
 * async execution without also getting {@link TenantTaskDecorator}. The failure this prevents is
 * quiet: an {@code @Async} method works in every test that never looks at the tenant, and then
 * under load runs on a pooled thread still carrying the previous request's tenant.
 *
 * <p>Boot's auto-configured {@code applicationTaskExecutor} picks up the single
 * {@link TaskDecorator} bean; anything that builds its own executor must apply the decorator
 * itself — there is no ambient mechanism that reaches into executors this configuration never saw.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class TenancyConfiguration {

    @Bean
    TaskDecorator tenantTaskDecorator() {
        return new TenantTaskDecorator();
    }
}
