package com.xenopsoftware.learn.assessment.config;

import com.xenopsoftware.learn.assessment.attempt.AttemptProperties;
import com.xenopsoftware.learn.assessment.integrity.IntegrityProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The service's own wiring (T-6.6).
 *
 * <p>{@code @EnableScheduling} is here as well as on the messaging configuration, for the reason
 * catalog's copy gives: that one is conditional on {@code platform.outbox.enabled}, so the attempt
 * reaper would inherit its scheduler and stop running the day somebody turned the bus off in an
 * environment. A change about messages that silently left every abandoned exam open.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({AttemptProperties.class, IntegrityProperties.class})
public class AssessmentConfiguration {

    /**
     * The clock every rule about time takes as an argument (T-6.6).
     *
     * <p>A bean rather than {@code Instant.now()} scattered through services, and here it is more
     * than tidiness: the whole task is about a deadline the server owns, and a test that could only
     * assert it by waiting an hour would not be a test anybody ran.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
