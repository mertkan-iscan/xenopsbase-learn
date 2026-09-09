package com.xenopsoftware.learn.gateway.relay;

/**
 * The person's session is over and no token can be obtained for them (T-10.2).
 *
 * <p>Distinct from a permission refusal on purpose. "You are not signed in any more" is
 * recoverable by signing in again, and the browser has work in its hands that must survive that;
 * "you may not do this" is not recoverable at all. A single 401 for both would leave the frontend
 * unable to tell which it is, and it would guess — badly, in the case that matters, which is a
 * submitted exam.
 */
public class SessionEndedException extends RuntimeException {

    public SessionEndedException(String message) {
        super(message);
    }
}
