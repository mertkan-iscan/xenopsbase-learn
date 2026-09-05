package com.xenopsoftware.learn.common.mail;

/**
 * One message to one person (T-5.6).
 *
 * <p>Plain text and one recipient, deliberately. Multipart bodies, attachments and recipient lists
 * are all things this platform will eventually want, and every one of them is a decision about what
 * leaves the company — an attachment is data walking out of the permission model (T-7.9 says so in
 * its own words), and a list is a way to disclose who else is behind on their training. Adding them
 * when there is a task that needs them keeps those decisions attached to somebody's name.
 *
 * @param to      the address, as identity holds it
 * @param subject the subject line
 * @param body    plain text, already rendered — a {@link Mailer} does not know what a reminder is
 */
public record Letter(String to, String subject, String body) {

    public Letter {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("A letter needs a recipient");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("A letter needs a subject");
        }
    }
}
