package com.xenopsoftware.learn.catalog.due;

import com.xenopsoftware.learn.catalog.assign.Assignment;
import com.xenopsoftware.learn.catalog.assign.AssignmentRepository;
import com.xenopsoftware.learn.catalog.assign.LearnerProfiles;
import com.xenopsoftware.learn.common.mail.Letter;
import com.xenopsoftware.learn.common.mail.Mailer;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sending the reminders, once each (T-5.6's third and fourth criteria).
 *
 * <h2>Idempotent, and where that actually lives</h2>
 *
 * <p>Not here. It lives in {@code reminder_sent}'s primary key and in {@link Reminders#claim},
 * which inserts the record BEFORE the mail is handed over. A cluster rebuild, a second replica, or
 * this method running twice a minute all find the row and do nothing. Nothing in this class needs
 * to reason about duplicates, which is the only way that reasoning stays right.
 *
 * <h2>A missed window is recorded, not sent and not forgotten</h2>
 *
 * <p>A service that has been down for a week must not deliver a week of nudges the moment it comes
 * back — that is the symptom the criterion names. It must not silently skip them either, because
 * "we never told them" is exactly what somebody asks about afterwards. So a window older than
 * {@link DueProperties#catchUp} is claimed like any other and settled as FAILED with the reason,
 * where {@link Reminders#unsent} can show it.
 *
 * <h2>A mail failure never blocks the assignment</h2>
 *
 * <p>Nothing in this pass writes to {@code assignment}, and every send is wrapped. The obligation
 * does not stop existing because a mail server did, and a learner does not become compliant because
 * one did. The failure is recorded against the reminder and the pass carries on to the next person
 * — one bad address must not stop a department's mail.
 */
@Service
public class ReminderService {

    private static final Logger LOG = LoggerFactory.getLogger(ReminderService.class);

    private final AssignmentRepository assignments;
    private final AssignmentCycleRepository cycleRepository;
    private final CycleService cycles;
    private final Audience audience;
    private final LearnerProfiles profiles;
    private final Satisfaction satisfaction;
    private final ReferenceTitles titles;
    private final Reminders reminders;
    private final Mailer mailer;
    private final DueProperties properties;

    public ReminderService(AssignmentRepository assignments,
            AssignmentCycleRepository cycleRepository, CycleService cycles, Audience audience,
            LearnerProfiles profiles, Satisfaction satisfaction, ReferenceTitles titles,
            Reminders reminders, Mailer mailer, DueProperties properties) {
        this.assignments = assignments;
        this.cycleRepository = cycleRepository;
        this.cycles = cycles;
        this.audience = audience;
        this.profiles = profiles;
        this.satisfaction = satisfaction;
        this.titles = titles;
        this.reminders = reminders;
        this.mailer = mailer;
        this.properties = properties;
    }

    /** What one pass did, for the log and for a test that needs to know. */
    public record Pass(int sent, int missed, int failed) {

        Pass plus(Pass other) {
            return new Pass(sent + other.sent, missed + other.missed, failed + other.failed);
        }

        static Pass nothing() {
            return new Pass(0, 0, 0);
        }
    }

    /**
     * The scheduled pass, over every tenant that wants reminders.
     *
     * <p>Deliberately NOT transactional. The claim has to commit before the mail is sent, or the
     * ordering the whole design rests on is inverted — a transaction spanning both would roll the
     * claim back when a send failed, and the next pass would try again, which is the duplicate
     * delivery this exists to prevent.
     */
    @Scheduled(fixedDelayString = "${catalog.due.interval:PT15M}")
    public void pass() {
        Instant now = Instant.now();
        Pass total = Pass.nothing();
        for (String tenantId : reminders.tenantsWithReminders()) {
            try {
                total = total.plus(TenantContext.callWithUnchecked(tenantId,
                    () -> sendFor(tenantId, now)));
            } catch (RuntimeException e) {
                // One company's bad data must not stop every other company's reminders.
                LOG.error("Reminder pass failed for tenant {}", tenantId, e);
            }
        }
        if (total.sent() > 0 || total.missed() > 0 || total.failed() > 0) {
            LOG.info("Reminders: {} sent, {} past the catch-up window, {} refused by the provider",
                total.sent(), total.missed(), total.failed());
        }
    }

    /**
     * One tenant's reminders, as of {@code now}.
     *
     * <p>Takes the clock as an argument so a test can ask what happens in eleven months' time
     * without waiting, and so every learner in one pass is reckoned against the same instant.
     */
    public Pass sendFor(String tenantId, Instant now) {
        Pass tally = Pass.nothing();
        for (Assignment assignment : assignments.findByRevokedAtIsNullAndDueKindNot(DueKind.NONE)) {
            List<Integer> offsets = reminders.offsetsOf(assignment.getId());
            if (offsets.isEmpty()) {
                continue;
            }
            Optional<AssignmentCycle> current = cycles.currentCycle(assignment, now);
            if (current.isEmpty()) {
                continue;
            }
            tally = tally.plus(remindFor(tenantId, assignment, current.get(), offsets, now));
        }
        return tally;
    }

    private Pass remindFor(String tenantId, Assignment assignment, AssignmentCycle cycle,
            List<Integer> offsets, Instant now) {
        List<Audience.Reached> reached = audience.of(assignment);
        if (reached.isEmpty()) {
            return Pass.nothing();
        }
        List<UUID> learnerIds = reached.stream().map(Audience.Reached::learnerId).toList();
        Map<UUID, LearnerProfiles.Profile> known = profiles.allOf(tenantId, learnerIds);
        Set<UUID> finished = satisfaction.whoHasFinished(tenantId, assignment.getReferenceType(),
            assignment.getReferenceId(), learnerIds, cycle.getOpensAt());
        String title = titles.of(tenantId, assignment.getReferenceType(),
            assignment.getReferenceId());

        Pass tally = Pass.nothing();
        for (Audience.Reached person : reached) {
            if (finished.contains(person.learnerId())) {
                continue;
            }
            LearnerProfiles.Profile profile = known.get(person.learnerId());
            if (profile == null || profile.email() == null || profile.email().isBlank()) {
                // No address. Not an error worth a stack trace on every pass -- a learner catalog
                // has heard of through a group but never through a profile event is an ordinary
                // state during a backfill.
                continue;
            }
            tally = tally.plus(
                remindPerson(tenantId, assignment, cycle, offsets, person, profile, title, now));
        }
        return tally;
    }

    private Pass remindPerson(String tenantId, Assignment assignment, AssignmentCycle cycle,
            List<Integer> offsets, Audience.Reached person, LearnerProfiles.Profile profile,
            String title, Instant now) {
        ZoneId zone = Deadlines.zoneOf(profile.timeZone());
        Optional<LocalDate> due = Deadlines.dueDateForLearner(assignment.due(),
            cycle.getOpensAt().atZone(zone).toLocalDate(), cycle.getDueOn(),
            person.reachedAt().atZone(zone).toLocalDate());
        if (due.isEmpty()) {
            return Pass.nothing();
        }
        int sent = 0;
        int missed = 0;
        int failed = 0;
        for (int offset : offsets) {
            // The hour is in the LEARNER's zone (T-5.6's last criterion). A server-side "09:00"
            // reaches a third of a global company in the middle of the night.
            ZonedDateTime when = due.get().plusDays(offset).atStartOfDay(zone)
                .plusHours(properties.sendHour());
            Instant sendAt = when.toInstant();
            if (now.isBefore(sendAt)) {
                continue;
            }
            if (!reminders.claim(tenantId, cycle.getId(), person.learnerId(), offset, now)) {
                continue;
            }
            Duration late = Duration.between(sendAt, now);
            if (late.compareTo(properties.catchUp()) > 0) {
                reminders.settle(cycle.getId(), person.learnerId(), offset, false,
                    "Window missed by " + late.toDays() + " day(s); not sent. A backlog of "
                    + "reminders delivered at once is worse than one that was never sent.");
                missed++;
                continue;
            }
            try {
                mailer.send(letterFor(profile, title, due.get(), offset, zone, now));
                reminders.settle(cycle.getId(), person.learnerId(), offset, true, null);
                sent++;
            } catch (RuntimeException e) {
                // Recorded and stepped over. One bad address must not stop a department's mail,
                // and no mail failure changes whether somebody owes the training.
                reminders.settle(cycle.getId(), person.learnerId(), offset, false, e.getMessage());
                LOG.warn("Reminder for {} on assignment {} was refused: {}", person.learnerId(),
                    assignment.getId(), e.getMessage());
                failed++;
            }
        }
        return new Pass(sent, missed, failed);
    }

    private Letter letterFor(LearnerProfiles.Profile profile, String title, LocalDate due,
            int offset, ZoneId zone, Instant now) {
        boolean overdue = Deadlines.isOverdue(due, zone, now);
        String subject = overdue
            ? "Overdue: " + title
            : title + " is due on " + due;
        String opening = profile.displayName() == null || profile.displayName().isBlank()
            ? "Hello,"
            : "Hello " + profile.displayName() + ",";
        String middle = overdue
            ? "\"" + title + "\" was due on " + due + " and has not been completed.\n\n"
                + "You can still complete it — nothing has been taken away, and finishing it now "
                + "closes the gap."
            : "\"" + title + "\" is due on " + due + ".";
        return new Letter(profile.email(), subject, opening + "\n\n" + middle
            + "\n\nDates are shown in your own timezone (" + zone.getId() + ").\n");
    }

    /** Every cycle of an assignment, for the screen that shows what happened in previous years. */
    public List<AssignmentCycle> historyOf(UUID assignmentId) {
        return cycleRepository.findByAssignmentIdOrderByCycleNumberAsc(assignmentId);
    }
}
