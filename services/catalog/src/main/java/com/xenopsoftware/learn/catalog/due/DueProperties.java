package com.xenopsoftware.learn.catalog.due;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The knobs the reminder pass turns (T-5.6).
 *
 * @param sendHour the hour, IN THE LEARNER'S OWN TIMEZONE, at which a reminder goes out. Nine, so
 *                 nobody is woken at three in the morning by a compliance nudge — which is what a
 *                 server-side "send at 09:00" does to a third of a global company
 * @param catchUp  how late a reminder may still be sent. A window missed by longer than this is
 *                 RECORDED AS MISSED rather than sent (see {@link ReminderService}): a service that
 *                 has been down for a week must not mail a week of nudges the moment it returns,
 *                 and must not pretend the window never existed either
 * @param interval how often the pass runs. It is idempotent, so this is a latency knob and not a
 *                 correctness one — running it twice a minute sends nothing twice
 */
@ConfigurationProperties(prefix = "catalog.due")
public record DueProperties(Integer sendHour, Duration catchUp, Duration interval) {

    public DueProperties {
        sendHour = sendHour == null ? 9 : sendHour;
        catchUp = catchUp == null ? Duration.ofDays(2) : catchUp;
        interval = interval == null ? Duration.ofMinutes(15) : interval;
        if (sendHour < 0 || sendHour > 23) {
            throw new IllegalArgumentException("catalog.due.send-hour must be an hour of the day");
        }
    }
}
