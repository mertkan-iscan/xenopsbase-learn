package com.xenopsoftware.learn.identity.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A token that cannot be provisioned without a decision a human has to make: its {@code sub} is
 * unknown, but its email already belongs to an existing user. Auto-relinking here would make
 * email ownership equivalent to account takeover, so the service refuses and this surfaces as a
 * 409. The deliberate repair is {@code docs/runbooks/identity.md}.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class IdentityConflictException extends RuntimeException {

    public IdentityConflictException(String message) {
        super(message);
    }
}
