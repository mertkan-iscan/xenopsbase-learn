package com.xenopsoftware.learn.assessment.bank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No such bank in THIS tenant, which is the only sense in which "no such bank" has a meaning here:
 * another company's id resolves to nothing because the discriminator filtered it, not because a
 * check refused it. Same 404 either way, which is the point (ADR-0102).
 *
 * <p>A platform bank that is not offered answers the same way, and for the same reason — see
 * {@link PlatformBanks#find}, whose {@code shared = true} predicate is what makes a guessed id
 * indistinguishable from an absent one.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class BankNotFound extends RuntimeException {

    public BankNotFound() {
        super("No such question bank");
    }
}
