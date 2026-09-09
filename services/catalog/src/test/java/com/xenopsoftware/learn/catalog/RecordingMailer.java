package com.xenopsoftware.learn.catalog;

import com.xenopsoftware.learn.common.mail.Letter;
import com.xenopsoftware.learn.common.mail.MailNotSent;
import com.xenopsoftware.learn.common.mail.Mailer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A mailer that keeps what it was handed and can be told to refuse an address (T-5.6, T-6.7).
 *
 * <p>{@code @Primary} rather than a property, because what T-5.6 tests is the call site's behaviour
 * when a provider says no — and the only honest way to produce that is a provider that says no.
 *
 * <p><b>Top-level rather than nested inside one test</b>, which is what T-6.7 needed it to be:
 * catalog now sends two kinds of letter (a reminder, and "your test has been marked"), and a second
 * test declaring its own {@code @Primary Mailer} makes three candidates in one context — the stub,
 * the real bean and the new one — which Spring refuses. One shared stub, in the package the other
 * shared test fixtures live in.
 */
public class RecordingMailer implements Mailer {

    @TestConfiguration(proxyBeanMethods = false)
    public static class Wiring {

        @Bean
        @Primary
        public RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }

    private final List<Letter> sent = new CopyOnWriteArrayList<>();
    private volatile String refuseTo;

    @Override
    public void send(Letter letter) {
        if (letter.to().equals(refuseTo)) {
            throw new MailNotSent("The provider refused " + letter.to(), null);
        }
        sent.add(letter);
    }

    @Override
    public boolean delivers() {
        return true;
    }

    /** What has been handed to it. */
    public List<Letter> sent() {
        return sent;
    }

    public void refuse(String address) {
        refuseTo = address;
    }

    public void forget() {
        sent.clear();
        refuseTo = null;
    }
}
