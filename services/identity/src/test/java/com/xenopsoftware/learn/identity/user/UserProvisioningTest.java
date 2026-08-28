package com.xenopsoftware.learn.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * First-login provisioning and the re-link repair, against a real Postgres (T-1.2).
 *
 * <p>The concurrency test is the one that earns the container: two threads, one latch, both
 * inside the window between "no row yet" and "row inserted". An in-memory database that
 * serializes everything would pass it without testing anything.
 */
@SpringBootTest
class UserProvisioningTest extends PostgresTestHarness {

    @Autowired
    private UserProvisioningService service;

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private javax.sql.DataSource dataSource;

    @BeforeEach
    void emptyTheTenants() throws Exception {
        // app_user has dependents now -- group_membership (T-1.3) and audit_log (T-2.2) --
        // so they go first or the delete below hits a foreign key.
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("DELETE FROM audit_log");
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("DELETE FROM group_membership");
        TenantContext.callWith("acme", () -> {
            repository.deleteAll();
            return null;
        });
        TenantContext.callWith("globex", () -> {
            repository.deleteAll();
            return null;
        });
    }

    @Test
    void firstLoginCreatesTheRowAndSecondLoginFindsIt() throws Exception {
        AppUser first = TenantContext.callWith("acme",
            () -> service.provision(token("sub-1", "casey@acme.test", "Casey Acme")));
        AppUser again = TenantContext.callWith("acme",
            () -> service.provision(token("sub-1", "casey@acme.test", "Casey Acme")));

        assertThat(first.getId()).isNotNull();
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(TenantContext.callWith("acme", () -> repository.count())).isEqualTo(1L);
        assertThat(first.getTenantId()).isEqualTo("acme");
        assertThat(first.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void twoConcurrentFirstRequestsProduceOneRowAndAgreeOnIt() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> a = pool.submit(() -> TenantContext.callWith("acme", () -> {
                bothReady.countDown();
                bothReady.await();
                return service.provision(token("sub-race", "race@acme.test", "Race")).getId();
            }));
            Future<UUID> b = pool.submit(() -> TenantContext.callWith("acme", () -> {
                bothReady.countDown();
                bothReady.await();
                return service.provision(token("sub-race", "race@acme.test", "Race")).getId();
            }));

            assertThat(a.get()).isEqualTo(b.get());
            assertThat(TenantContext.callWith("acme", () -> repository.count())).isEqualTo(1L);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aKnownEmailWithAnUnknownSubIsAConflictNotATakeover() throws Exception {
        TenantContext.callWith("acme",
            () -> service.provision(token("sub-original", "casey@acme.test", "Casey")));

        // The IdP identity changed underneath the person. Provisioning must refuse -- the
        // deliberate path is relink(), below -- because accepting would let anyone who can
        // mint a token with this email claim take over the account.
        assertThatThrownBy(() -> TenantContext.callWith("acme",
            () -> service.provision(token("sub-imposter", "casey@acme.test", "Casey"))))
            .isInstanceOf(IdentityConflictException.class);
    }

    @Test
    void relinkMovesTheSubAndEveryReferenceFollowsBecauseTheIdNeverMoves() throws Exception {
        UUID originalId = TenantContext.callWith("acme",
            () -> service.provision(token("sub-before", "casey@acme.test", "Casey")).getId());

        AppUser relinked = TenantContext.callWith("acme",
            () -> service.relink("Casey@ACME.test", "sub-after"));

        // The id is the whole point: every foreign key in every service stores it, so proving
        // it unchanged proves all history followed. The old sub resolves to nothing, the new
        // one to the same person.
        assertThat(relinked.getId()).isEqualTo(originalId);
        assertThat(TenantContext.callWith("acme", () -> repository.findByIdpSub("sub-after"))
            .map(AppUser::getId)).contains(originalId);
        assertThat(TenantContext.callWith("acme", () -> repository.findByIdpSub("sub-before")))
            .isEmpty();
    }

    @Test
    void relinkRefusesASubThatAlreadyBelongsToSomeoneElse() throws Exception {
        TenantContext.callWith("acme",
            () -> service.provision(token("sub-casey", "casey@acme.test", "Casey")));
        TenantContext.callWith("acme",
            () -> service.provision(token("sub-jordan", "jordan@acme.test", "Jordan")));

        assertThatThrownBy(() -> TenantContext.callWith("acme",
            () -> service.relink("casey@acme.test", "sub-jordan")))
            .isInstanceOf(IdentityConflictException.class);
    }

    @Test
    void theSameSubProvisionsIndependentlyPerTenantContext() throws Exception {
        // Not a realistic token shape -- one sub belongs to one realm user -- but the boundary
        // property is worth pinning: nothing provisioned under one tenant is reachable from
        // another, even on the same link key.
        UUID acmeId = TenantContext.callWith("acme",
            () -> service.provision(token("sub-x", "x@acme.test", "X")).getId());

        assertThat(TenantContext.callWith("globex", () -> repository.findByIdpSub("sub-x")))
            .isEmpty();
        assertThat(TenantContext.callWith("acme", () -> repository.findByIdpSub("sub-x"))
            .map(AppUser::getId)).contains(acmeId);
    }

    private static Jwt token(String sub, String email, String name) {
        return Jwt.withTokenValue("test")
            .header("alg", "none")
            .subject(sub)
            .claim("email", email)
            .claim("name", name)
            .claim("preferred_username", email)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    }
}
