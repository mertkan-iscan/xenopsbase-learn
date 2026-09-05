package com.xenopsoftware.learn.common.mail;

/**
 * Sends a letter, or says why it could not (T-5.6).
 *
 * <p><b>This is the mail provider T-5.6 said to use, and it did not exist.</b> Nothing in this
 * platform had ever sent an email — invitations hand the token back to the caller and are
 * explicitly never mailed by us. Two tasks need one (T-5.6's reminders, T-7.9's scheduled reports),
 * so the port lives here rather than inside either of them: a second implementation written next to
 * the reminder scheduler would be the beginning of two mail stacks with two sets of failure
 * behaviour, and the one nobody exercises is the one that is wrong.
 *
 * <p><b>Failure is a thrown exception, and callers are expected to catch it.</b> That is the shape
 * T-5.6's fourth criterion requires: a mail failure must never block the assignment. A method
 * returning a boolean would be ignored at exactly the call site where it mattered.
 */
public interface Mailer {

    /**
     * Hands the letter to the provider.
     *
     * @throws MailNotSent if the provider refused it or could not be reached. The caller decides
     *                     what that means; for a reminder it means recording the failure and
     *                     carrying on, because an obligation does not stop existing when a mail
     *                     server does.
     */
    void send(Letter letter);

    /**
     * Whether anything actually leaves the building.
     *
     * <p>Its own method so that a service can say so at startup. A green test run against a mailer
     * that delivers nowhere proves the call sites work and nothing at all about mail, which is the
     * same trap {@code platform.messaging.nats-url} being empty sets for the bus.
     */
    boolean delivers();
}
