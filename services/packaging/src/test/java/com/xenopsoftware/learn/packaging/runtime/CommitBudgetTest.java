package com.xenopsoftware.learn.packaging.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.cache.DegradableCaches;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The write budget, and the two things about it that are load-bearing (T-4.4).
 *
 * <p>That it refuses above the limit, and that it refuses <em>nobody</em> when Valkey is gone. The
 * second is the one worth a test: a limiter that fails closed turns a cache outage into every
 * learner in the product losing their place, which is a far larger failure than the load curve it
 * exists to flatten.
 */
class CommitBudgetTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> counters = Mockito.mock(ValueOperations.class);
    private final StringRedisTemplate valkey = Mockito.mock(StringRedisTemplate.class);

    private final UUID learner = UUID.randomUUID();
    private final UUID pack = UUID.randomUUID();
    private final UUID node = UUID.randomUUID();

    private CommitBudget budgetOf(int perWindow) {
        return new CommitBudget(valkey, Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"),
            ZoneOffset.UTC), caches(), perWindow);
    }

    private static DegradableCaches caches() {
        return new DegradableCaches(Duration.ofSeconds(30), providerOf((MeterRegistry) null));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    @DisplayName("the saves inside the budget are permitted and the ones past it are not")
    void theLimitIsTheLimit() {
        Mockito.when(valkey.opsForValue()).thenReturn(counters);
        Mockito.when(counters.increment(Mockito.anyString())).thenReturn(1L, 2L, 3L, 4L);

        CommitBudget budget = budgetOf(3);

        assertThat(budget.permit(learner, pack, node)).isTrue();
        assertThat(budget.permit(learner, pack, node)).isTrue();
        assertThat(budget.permit(learner, pack, node)).isTrue();
        assertThat(budget.permit(learner, pack, node))
            .as("the fourth is past a budget of three")
            .isFalse();
    }

    @Test
    @DisplayName("the window is expired once, on its first hit")
    void theKeyCannotBePushedForwardForever() {
        Mockito.when(valkey.opsForValue()).thenReturn(counters);
        Mockito.when(counters.increment(Mockito.anyString())).thenReturn(1L, 2L, 3L);

        CommitBudget budget = budgetOf(10);
        budget.permit(learner, pack, node);
        budget.permit(learner, pack, node);
        budget.permit(learner, pack, node);

        // Re-expiring on every request would let a steady stream of them push the expiry forward
        // forever, which is the one way a fixed window becomes a key that never dies.
        Mockito.verify(valkey, Mockito.times(1)).expire(Mockito.anyString(), Mockito.eq(CommitBudget.WINDOW));
    }

    @Test
    @DisplayName("a registration counts separately from the same package in another course")
    void theRowIsWhatIsCounted() {
        Mockito.when(valkey.opsForValue()).thenReturn(counters);
        Mockito.when(counters.increment(Mockito.anyString())).thenReturn(1L);

        budgetOf(10).permit(learner, pack, node);
        budgetOf(10).permit(learner, pack, UUID.randomUUID());

        // Two keys, because the same package attached to two courses is two rows -- and throttling
        // one learner across both would punish somebody for being assigned twice.
        Mockito.verify(counters, Mockito.times(2)).increment(Mockito.anyString());
        Mockito.verify(counters, Mockito.never()).increment(Mockito.argThat(key -> !key.contains(learner.toString())));
    }

    @Test
    @DisplayName("Valkey being gone permits everything, and is asked once per cooldown")
    void itFailsOpenAndCheaply() {
        Mockito.when(valkey.opsForValue()).thenReturn(counters);
        Mockito.when(counters.increment(Mockito.anyString()))
            .thenThrow(new RedisConnectionFailureException("no route to host"));

        CommitBudget budget = budgetOf(1);

        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(budget.permit(learner, pack, node))
                .as("a learner's progress is not lost because a cache is down")
                .isTrue();
        }
        /*
         * ONCE, not twenty times. Failing open is only cheap if failing is cheap: Lettuce shares
         * one connection and an unreachable Valkey does not refuse quickly, so without the cooldown
         * every save in the product would wait out a connection timeout on a path a learner is
         * sitting in front of.
         */
        Mockito.verify(counters, Mockito.times(1)).increment(Mockito.anyString());
    }
}
