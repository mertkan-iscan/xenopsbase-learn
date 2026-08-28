package com.xenopsoftware.learn.streaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Video assets, upload targets, encode state and playback tokens.
 *
 * <p>The learner hot path, and a separate process on purpose (ADR-0109) — but never load-bearing
 * during playback: video bytes are delivered by the edge, and this service's hot-path job ends
 * at an entitlement decision and a signature (ADR-0101). T-3.10 asserts that property on every
 * build, because it decays one convenience proxy endpoint at a time.
 */
@SpringBootApplication
@ComponentScan({"com.xenopsoftware.learn.streaming", "com.xenopsoftware.learn.common"})
public class StreamingApp {

    public static void main(String[] args) {
        SpringApplication.run(StreamingApp.class, args);
    }
}
