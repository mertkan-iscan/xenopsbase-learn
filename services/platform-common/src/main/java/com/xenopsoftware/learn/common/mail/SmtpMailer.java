package com.xenopsoftware.learn.common.mail;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The real one: SMTP, through Spring's sender (T-5.6).
 *
 * <p>SMTP rather than a vendor API because it is the one protocol every provider speaks — a
 * company's own relay, a transactional service, or the catcher in the local stack are the same
 * three properties and no code change. Picking a vendor SDK here would put that choice in a shared
 * library, where changing it later means changing every service that mails.
 */
public class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailer(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(Letter letter) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(letter.to());
        message.setSubject(letter.subject());
        message.setText(letter.body());
        try {
            sender.send(message);
        } catch (MailException e) {
            // Wrapped, and the address is NOT in the message. This exception ends up in a
            // reminder_sent.detail column and in logs; "could not reach the mail server" is the
            // operational fact, and repeating who it was for adds nothing an operator can act on.
            throw new MailNotSent("The mail provider refused or could not be reached", e);
        }
    }

    @Override
    public boolean delivers() {
        return true;
    }
}
