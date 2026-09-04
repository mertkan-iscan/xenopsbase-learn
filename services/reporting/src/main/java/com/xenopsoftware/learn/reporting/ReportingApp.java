package com.xenopsoftware.learn.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Telemetry ingest, rollups, reports and exports.
 *
 * <p>A separate process on two grounds rather than one (T-9.7). Its write rate scales with
 * concurrent learners rather than with users and its reads aggregate rather than look up, which
 * is a shape nothing else in the platform has. And — the stronger argument — <b>reporting being
 * down must never stop a video playing or an attempt being submitted</b>, which is only true if
 * no learner-path request calls into it synchronously.
 *
 * <p>It owns its data outright. It does not read another module's schema to build a report,
 * however convenient that would be: the first time a report joins across {@code catalog_db} or
 * {@code assessment_db}, the decomposition is over and nobody will have noticed it happening.
 * What it consumes is written down in {@code docs/reporting-inputs.md} and enforced by
 * credentials rather than by convention.
 */
@SpringBootApplication
@ComponentScan({"com.xenopsoftware.learn.reporting", "com.xenopsoftware.learn.common"})
public class ReportingApp {

    public static void main(String[] args) {
        SpringApplication.run(ReportingApp.class, args);
    }
}
