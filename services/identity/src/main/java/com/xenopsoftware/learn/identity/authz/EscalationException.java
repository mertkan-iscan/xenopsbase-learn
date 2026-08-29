package com.xenopsoftware.learn.identity.authz;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The caller tried to hand out something they do not hold (T-2.6). 403 rather than 404: the
 * permission they named is in the public catalog, so refusing to confirm it exists would hide
 * nothing and only make the error unactionable.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class EscalationException extends RuntimeException {

    public EscalationException(String message) {
        super(message);
    }
}
