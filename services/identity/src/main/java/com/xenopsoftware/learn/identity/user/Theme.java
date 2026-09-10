package com.xenopsoftware.learn.identity.user;

import java.util.Locale;
import java.util.Optional;

/**
 * Which of the product's two palettes somebody reads it in (T-10.9).
 *
 * <p>A closed set, and therefore an enum rather than a string: the frontend resolves exactly these
 * three cases and a fourth value in the column would be a row no screen can render. The database
 * says the same thing a second time (V14's CHECK), because a preference written by a future
 * migration script is not going through this class.
 *
 * <p><b>{@link #SYSTEM} is not the same as no answer at all.</b> Both follow the operating
 * system today, so they look identical on screen — but one is a person who tried dark and went
 * back, and the other is a person who has never been asked. Collapsing them is the mistake V13
 * refused for timezones and V14 refuses again here: the second population is one a customer can
 * go and ask, and only if it is still distinguishable.
 */
public enum Theme {
    LIGHT,
    DARK,
    SYSTEM;

    /**
     * Parses what a caller sent, tolerantly in case and whitespace only.
     *
     * <p>Blank is {@link Optional#empty()} rather than an error, because clearing a preference is
     * a real request: it puts somebody back into "has not told us" instead of leaving a choice
     * they have stopped wanting. The caller distinguishes the two — a blank body field clears,
     * an absent one leaves the stored value alone.
     */
    public static Optional<Theme> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException notATheme) {
            throw new IllegalArgumentException(
                "\"" + value + "\" is not a theme this product has. Use light, dark or system.",
                notATheme);
        }
    }
}
