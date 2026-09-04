package com.xenopsoftware.learn.common.service;

/**
 * Which service is making this call, and on whose behalf (T-9.11).
 *
 * <p>The distinction is the point of the record. {@code onBehalfOfUser} false means a service
 * acting for itself — a scheduled reconciliation, a rollup — and true means it is carrying a
 * person's identity through the hop. A log line or an audit entry that cannot tell those apart
 * cannot answer "who did this", which is the question audit exists for.
 *
 * @param serviceId the caller's own id, taken from its verified token and not from anything it
 *        asserted about itself
 */
public record CallingService(String serviceId, boolean onBehalfOfUser) {

    /** The request attribute the filter leaves it under. */
    public static final String ATTRIBUTE = CallingService.class.getName();
}
