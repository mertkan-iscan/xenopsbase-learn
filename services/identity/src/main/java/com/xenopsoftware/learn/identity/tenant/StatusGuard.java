package com.xenopsoftware.learn.identity.tenant;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The boundary, as opposed to the fast path (T-1.4's third criterion).
 *
 * <p>Called inside the transaction that is about to write, and it reads the database rather than
 * the published entry. The edge can be stale for the length of a request; a write must not be.
 * The two checks are deliberately different mechanisms — one cached and permissive when it
 * cannot answer, one authoritative and inside the transaction — because a single mechanism that
 * is both fast and authoritative is the thing that does not exist.
 */
@Component
public class StatusGuard {

    private final EffectiveStatus effective;

    public StatusGuard(EffectiveStatus effective) {
        this.effective = effective;
    }

    /** Refuses the write if the tenant may not make one. */
    public void requireWritable() {
        String tenant = TenantContext.get();
        if (tenant == null) {
            return;
        }
        AccountStatus status = effective.ofTenant(tenant);
        if (!status.permitsWrites()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                status.reasonCode() + ": this account cannot be changed while it is " + status);
        }
    }
}
