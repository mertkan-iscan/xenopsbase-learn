package com.xenopsoftware.learn.identity.user;

import com.xenopsoftware.learn.identity.audit.AuditLogger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * How somebody wants this product to look and read (T-10.9).
 *
 * <p><b>Its own service rather than three more arguments to {@code UserLifecycleService.update},
 * for the reason that method's neighbour already gives.</b> {@code moveTo} is separated there
 * because moving to Berlin is not an administrative act; language and theme are the same kind of
 * fact about the same person, and lumping them in with "correct this person's email address"
 * would put a self-service change behind {@code user:manage}.
 *
 * <p><b>Absent and blank are different requests, and this is the class that makes them
 * different.</b> A screen that only has a theme switcher sends only a theme, and it must not
 * silently clear the language somebody set on another device. So a null field means "leave it
 * alone" and an empty string means "I no longer have a preference" — which is a real thing to
 * want, and puts the person back into the population V14 keeps findable rather than pinning them
 * to whatever they last chose.
 */
@Service
public class UserPreferencesService {

    private final AppUserRepository repository;
    private final AuditLogger audit;
    private final UserProfilePublisher profiles;

    public UserPreferencesService(AppUserRepository repository, AuditLogger audit,
            UserProfilePublisher profiles) {
        this.repository = repository;
        this.audit = audit;
        this.profiles = profiles;
    }

    /**
     * Applies whichever preferences the caller actually named.
     *
     * @param language a BCP-47 tag, {@code ""} to clear it, or null to leave it as it is
     * @param theme    {@code light}, {@code dark}, {@code system}, {@code ""} to clear it, or
     *                 null to leave it as it is
     */
    @Transactional
    public AppUser update(UUID userId, String language, String theme) {
        AppUser user = repository.findById(userId)
            // Tenant-filtered by the persistence layer, like every other lookup here: another
            // company's person is not found rather than refused (ADR-0102).
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        Map<String, Object> changed = new LinkedHashMap<>();
        try {
            if (language != null) {
                changed.put("languageBefore", orUnset(user.getLanguage()));
                user.prefersLanguage(language);
                changed.put("languageAfter", orUnset(user.getLanguage()));
            }
            if (theme != null) {
                changed.put("themeBefore", orUnset(user.getTheme()));
                user.prefersTheme(theme);
                changed.put("themeAfter", orUnset(user.getTheme()));
            }
        } catch (IllegalArgumentException notAValue) {
            // The entity throws in the vocabulary of the domain; a caller needs the sentence and
            // a 400 rather than a stack trace and a 500.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, notAValue.getMessage());
        }

        if (changed.isEmpty()) {
            // A body that named nothing. Not an error -- a client that sends {} has asked for
            // nothing to happen, and nothing happening is the correct answer to that -- but it
            // must not produce an audit entry claiming a change, or an event telling four
            // services to re-store a profile that did not move.
            return user;
        }

        AppUser updated = repository.saveAndFlush(user);
        audit.record("user.preferences", "user", updated.getId(), changed);
        // Language reaches the services that write to people (see UserProfilePublisher); theme
        // does not travel at all.
        profiles.announce(updated);
        return updated;
    }

    /** Audit entries say "unset" rather than carrying a null nobody can read in a log line. */
    private static String orUnset(Object value) {
        return value == null ? "unset" : value.toString();
    }
}
