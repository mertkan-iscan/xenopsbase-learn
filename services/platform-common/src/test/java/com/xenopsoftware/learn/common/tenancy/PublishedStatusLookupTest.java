package com.xenopsoftware.learn.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.cache.DegradableCaches;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The status fast path, and the cost of it being permissive (T-1.4, #126).
 *
 * <p>The behaviour under test is not "a Valkey outage does not refuse anyone" — that was always
 * true and is what the class is for. It is that an outage costs ONE connection attempt per
 * cooldown window. Without that, this read sits on every request in every service but identity
 * and a cache nobody can reach becomes a service nobody can use: `reporting` served about one
 * request per second and its own liveness probe eventually restarted it.
 */
class PublishedStatusLookupTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> entries = Mockito.mock(ValueOperations.class);
    private final StringRedisTemplate valkey = Mockito.mock(StringRedisTemplate.class);

    private PublishedStatusLookup lookupBackedBy(StringRedisTemplate template) {
        return new PublishedStatusLookup(providerOf(template), caches());
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
    void thePublishedEntryIsTheAnswer() {
        Mockito.when(valkey.opsForValue()).thenReturn(entries);
        Mockito.when(entries.get("status:tenant:acme")).thenReturn("SUSPENDED");

        assertThat(lookupBackedBy(valkey).statusOf("acme", "sub")).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void noEntryMeansActive() {
        Mockito.when(valkey.opsForValue()).thenReturn(entries);
        Mockito.when(entries.get("status:tenant:acme")).thenReturn(null);

        assertThat(lookupBackedBy(valkey).statusOf("acme", "sub")).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void anEntryThisVersionDoesNotUnderstandMeansActive() {
        Mockito.when(valkey.opsForValue()).thenReturn(entries);
        Mockito.when(entries.get("status:tenant:acme")).thenReturn("PENDING_SOMETHING");

        assertThat(lookupBackedBy(valkey).statusOf("acme", "sub")).isEqualTo(AccountStatus.ACTIVE);
    }

    /** The defect this issue is about: the second request must not wait for Valkey again. */
    @Test
    void anUnreachableValkeyIsAskedOncePerWindowRatherThanOncePerRequest() {
        Mockito.when(valkey.opsForValue()).thenReturn(entries);
        Mockito.when(entries.get(Mockito.anyString()))
            .thenThrow(new RedisConnectionFailureException("connection refused"));
        PublishedStatusLookup lookup = lookupBackedBy(valkey);

        for (int request = 0; request < 200; request++) {
            assertThat(lookup.statusOf("acme", "sub")).isEqualTo(AccountStatus.ACTIVE);
        }

        Mockito.verify(entries, Mockito.times(1)).get("status:tenant:acme");
    }

    /**
     * A service with no Valkey at all must not pay a connection attempt per request either, and
     * the reason it is answering ACTIVE has to be readable somewhere.
     */
    @Test
    void aServiceWithNoValkeyIsPermissiveAndSaysSo() {
        DegradableCaches caches = caches();

        PublishedStatusLookup lookup =
            new PublishedStatusLookup(providerOf((StringRedisTemplate) null), caches);

        assertThat(lookup.statusOf("acme", "sub")).isEqualTo(AccountStatus.ACTIVE);
        assertThat(caches.all()).singleElement()
            .satisfies(cache -> assertThat(cache.report()).containsEntry("mode", "absent"));
    }
}
