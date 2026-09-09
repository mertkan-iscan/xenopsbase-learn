package com.xenopsoftware.learn.assessment.attempt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Time a test can move (T-6.6), the shape streaming's copy of this already has.
 *
 * <p>The whole task is about a deadline the server owns. Every assertion here is about what happens
 * an hour later, and a test that could only make one by waiting an hour is a test nobody runs.
 */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-09-04T09:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    public static class Wiring {

        /**
         * Replaces the real clock everywhere in the context.
         *
         * <p>{@code @Primary} rather than a bean name, so nothing under test has to know it is
         * being tested -- the services take a {@link Clock} and get this one.
         */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    public void reset() {
        now = Instant.parse("2026-09-04T09:00:00Z");
    }
}
