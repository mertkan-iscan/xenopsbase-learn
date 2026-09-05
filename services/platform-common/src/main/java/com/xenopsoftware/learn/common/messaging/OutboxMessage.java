package com.xenopsoftware.learn.common.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing that happened, on its way out (T-9.8).
 *
 * @param id            the message identity, and the ONLY thing a consumer may dedupe on. It is
 *                      generated when the row is written, so a message republished after a relay
 *                      crash carries the same id as the one that may already have arrived
 * @param tenantId      whose event this is. Carried explicitly because a consumer runs on a
 *                      delivery thread with no request and therefore no bound tenant
 * @param subject       where it is published. The topology in {@link Streams} decides what that
 *                      means; a subject not covered by a stream is a message nobody stores
 * @param type          what happened, as a stable string. Consumers switch on it, so it outlives
 *                      any class name
 * @param payload       the body, as JSON. A string rather than an object because this row is read
 *                      back by versions of a service that have not been written yet
 * @param correlationId the request that caused it, carried through publication into consumption
 *                      so one id spans the whole causal chain (T-9.13 extends this to traces)
 */
public record OutboxMessage(UUID id, String tenantId, String subject, String type, String payload,
                            String correlationId, Instant occurredAt) {}
