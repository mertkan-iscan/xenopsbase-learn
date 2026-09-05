package com.xenopsoftware.learn.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wires a mail provider, or wires the honest absence of one (T-5.6).
 *
 * <p>Gated on {@code platform.mail.enabled} so a service that sends nothing pays for none of it,
 * the same rule the bus follows. A service that turns it on and configures {@code spring.mail.host}
 * gets SMTP; one that turns it on and does not gets {@link LoggingMailer} and a WARN, because the
 * local stack has no mail server and refusing to start would be a worse answer than saying so.
 *
 * <p><b>Why the two nested classes.</b> {@code spring-boot-starter-mail} is an optional dependency,
 * so {@link JavaMailSender} may not be on the classpath at all — and a bean method mentioning it
 * would fail to load rather than be skipped. The pair are mutually exclusive on class presence,
 * which needs no ordering between them. {@code @ConditionalOnMissingBean} across two scanned
 * configurations would need exactly that ordering, and would silently pick whichever was seen
 * first.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.mail.enabled", havingValue = "true")
public class MailConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MailConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JavaMailSender.class)
    static class WhenTheMailClientIsPresent {

        /**
         * SMTP if it is configured, a logger if it is not.
         *
         * <p>Boot only creates a {@link JavaMailSender} when {@code spring.mail.host} is set, so an
         * empty provider here means nobody configured a mail server — the same signal an empty
         * {@code nats-url} carries for the bus.
         */
        @Bean
        Mailer mailer(ObjectProvider<JavaMailSender> senders,
                @Value("${platform.mail.from:}") String from) {
            JavaMailSender sender = senders.getIfAvailable();
            if (sender == null || from == null || from.isBlank()) {
                LOG.warn("MAIL IS NOT CONFIGURED. Reminders will be recorded as sent and delivered "
                    + "nowhere. Set spring.mail.host and platform.mail.from to deliver them.");
                return new LoggingMailer();
            }
            LOG.info("Mail will be sent from {}", from);
            return new SmtpMailer(sender, from);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.mail.javamail.JavaMailSender")
    static class WhenTheMailClientIsAbsent {

        @Bean
        Mailer mailer() {
            LOG.warn("MAIL IS ENABLED BUT NO MAIL CLIENT IS ON THE CLASSPATH. Add "
                + "spring-boot-starter-mail to this service to deliver anything.");
            return new LoggingMailer();
        }
    }
}
