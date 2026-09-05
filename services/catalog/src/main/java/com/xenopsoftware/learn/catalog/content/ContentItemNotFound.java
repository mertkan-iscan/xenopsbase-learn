package com.xenopsoftware.learn.catalog.content;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No such item in THIS tenant, which is the only sense in which "no such item" has a meaning
 * here: another company's id resolves to nothing because the discriminator filtered it, not
 * because a check refused it. Same 404 either way, which is the point (ADR-0102).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ContentItemNotFound extends RuntimeException {

    public ContentItemNotFound() {
        super("No such content item");
    }
}
