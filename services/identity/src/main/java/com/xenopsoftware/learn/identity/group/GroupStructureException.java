package com.xenopsoftware.learn.identity.group;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An edit that would break the tree's rules: a cycle, a depth overrun, or a group deleted out
 * from under its members. Refused with a reason a UI can show, never applied halfway.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class GroupStructureException extends RuntimeException {

    public GroupStructureException(String message) {
        super(message);
    }
}
