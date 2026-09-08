package com.xenopsoftware.learn.assessment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Banks, questions, tests, forms, attempts and grading.
 *
 * <p><b>ADR-0109 says this module starts inside {@code core}, beside {@code identity} and
 * {@code catalog}, and this class is not that</b> — for the reason {@code CatalogApp} already
 * gives: the merge is a deployment change by that ADR's own argument, and doing it while three
 * services are mid-flight onto a cluster would break manifests to buy nothing the code needs. What
 * the ADR requires of the code is true here from the first commit: this module owns
 * {@code assessment_db} outright and reads nobody else's schema.
 *
 * <p>The part worth watching is different from catalog's. Catalog is tempted to copy a video's
 * duration; this module is tempted to reach for a <i>permission</i>. T-6.1's authoring boundary is
 * a grant scoped to a bank, and the evaluator that answers such questions lives inside
 * {@code identity} today, with the grants themselves in identity's tables. So the temptation is to
 * write a local "is this person an author" check that consults something convenient — a role name
 * on the token, a column on the bank — and that is exactly the special case ADR-0103 exists to
 * refuse. There is no enforcement here yet, deliberately, and the endpoints say so one by one.
 */
@SpringBootApplication
@ComponentScan({"com.xenopsoftware.learn.assessment", "com.xenopsoftware.learn.common"})
public class AssessmentApp {

    public static void main(String[] args) {
        SpringApplication.run(AssessmentApp.class, args);
    }
}
