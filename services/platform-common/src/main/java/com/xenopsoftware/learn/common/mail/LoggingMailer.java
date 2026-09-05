package com.xenopsoftware.learn.common.mail;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers nothing, and is loud about it (T-5.6).
 *
 * <p>The local default, chosen for the same reason {@code platform.messaging.nats-url} is empty by
 * default: a developer must be able to run the platform without an SMTP server, and the thing that
 * makes that safe is saying so rather than quietly succeeding. Every send is logged at INFO with
 * the recipient and subject, and the absence of a provider is a WARN at startup.
 *
 * <p><b>The body is not logged.</b> A reminder names the training somebody is behind on; that is
 * about a person, and application logs are the least controlled place this platform stores
 * anything. The subject and recipient are enough to answer "did it try".
 *
 * <p>It also keeps what it was given, which is what lets a test assert that a reminder pass sent
 * one mail to the right person without an SMTP server anywhere near it.
 */
public class LoggingMailer implements Mailer {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingMailer.class);

    private final List<Letter> sent = new ArrayList<>();

    @Override
    public synchronized void send(Letter letter) {
        sent.add(letter);
        LOG.info("MAIL NOT SENT (no provider configured): to={} subject=\"{}\"",
            letter.to(), letter.subject());
    }

    @Override
    public boolean delivers() {
        return false;
    }

    /** Everything handed over since startup, oldest first. */
    public synchronized List<Letter> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
    }
}
