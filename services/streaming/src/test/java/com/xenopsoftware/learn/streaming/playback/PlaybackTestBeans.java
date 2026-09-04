package com.xenopsoftware.learn.streaming.playback;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The three collaborators of the entitlement decision a test needs to steer: catalog, which
 * does not exist; identity, which is another process; and time, which is two hours long.
 *
 * <p>{@code @Primary} rather than a conditional on the production beans, deliberately. Ordering
 * between a scanned {@code @Component} and an imported test bean is not something to depend on,
 * and a test whose overriding is order-sensitive fails in a way that looks like a bug in the
 * code under test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PlaybackTestBeans {

    /**
     * One bean, not two: {@code MutableClock} IS a {@code Clock}, so a second primary
     * {@code Clock} delegating to it would leave two primary candidates for the same type.
     *
     * <p>And named {@code testClock}, not {@code clock}, because a same-named bean is an
     * override rather than a candidate — the context refuses to start rather than preferring
     * the primary one.
     */
    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    StubEntitlement stubEntitlement() {
        return new StubEntitlement();
    }

    @Bean
    @Primary
    StubViewerPermissions stubViewerPermissions() {
        return new StubViewerPermissions();
    }

    @Bean
    @Primary
    StubViewerDirectory stubViewerDirectory() {
        return new StubViewerDirectory();
    }
}
