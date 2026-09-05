package com.xenopsoftware.learn.streaming.config;

import com.xenopsoftware.learn.streaming.playback.PlaybackProperties;
import com.xenopsoftware.learn.streaming.progress.ProgressProperties;
import com.xenopsoftware.learn.streaming.video.UploadProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling exists for exactly one job so far — the upload reaper (T-3.2). Scheduled work runs
 * on a thread that binds no tenant, which is why the reaper is plain JDBC; anything scheduled
 * that touches tenant data through JPA will meet the T-1.1 resolver's refusal, on purpose.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({UploadProperties.class, PlaybackProperties.class,
    ProgressProperties.class})
public class StreamingConfiguration {

    /**
     * A bean rather than {@code Instant.now()} at the call sites that care (T-3.4). The
     * renewal property this service owes the player -- a two-hour video outlives many tokens
     * -- is only testable if a test can move time, and a test that moves real time by sleeping
     * for two hours is a test nobody runs.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
