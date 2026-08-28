package com.xenopsoftware.learn.identity.authz;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A role edit refused: wrong side, a system role, or a role something still points at. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RoleException extends RuntimeException {

    public RoleException(String message) {
        super(message);
    }
}
