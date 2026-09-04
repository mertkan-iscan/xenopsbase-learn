package com.xenopsoftware.learn.streaming.playback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Time a test can move (T-3.4). The renewal property — a two-hour video outlives its tokens —
 * is only assertable if two hours can pass in a millisecond.
 */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-09-04T09:00:00Z");

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    public void reset() {
        now = Instant.parse("2026-09-04T09:00:00Z");
    }
}
