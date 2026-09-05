package com.xenopsoftware.learn.common.mail;

/**
 * The provider would not take it (T-5.6).
 *
 * <p>Unchecked, because the compiler forcing every caller to handle it is how a catch block that
 * swallows the message gets written. The callers that must not fail — a reminder pass, a report
 * schedule — catch it deliberately and record the failure where somebody can see it.
 */
public class MailNotSent extends RuntimeException {

    public MailNotSent(String message, Throwable cause) {
        super(message, cause);
    }
}
