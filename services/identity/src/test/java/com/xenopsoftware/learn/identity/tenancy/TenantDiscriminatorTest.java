package com.xenopsoftware.learn.identity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The discriminator is applied by the persistence layer, not by each query (T-1.1).
 *
 * <p>{@link TenancyProbeRepository} is the test's sharpest instrument precisely because it is
 * ordinary: no tenant parameter, no custom query, nothing a distracted author would not write.
 * If these assertions hold for it, they hold for the repository someone writes at 6pm before a
 * release.
 */
@SpringBootTest
class TenantDiscriminatorTest extends PostgresTestHarness {

    @Autowired
    private TenancyProbeRepository repository;

    private Long acmeRowId;
    private Long globexRowId;

    @BeforeEach
    void seedBothTenants() throws Exception {
        TenantContext.callWith("acme", () -> {
            repository.deleteAll();
            return null;
        });
        TenantContext.callWith("globex", () -> {
            repository.deleteAll();
            return null;
        });
        acmeRowId = TenantContext.callWith("acme",
            () -> repository.save(new TenancyProbe("visible to acme")).getId());
        globexRowId = TenantContext.callWith("globex",
            () -> repository.save(new TenancyProbe("visible to globex")).getId());
    }

    @Test
    void insertsAreStampedWithTheBoundTenant() throws Exception {
        List<TenancyProbe> acmeSees = TenantContext.callWith("acme", () -> repository.findAll());
        assertThat(acmeSees).hasSize(1);
        assertThat(acmeSees.getFirst().getTenantId()).isEqualTo("acme");
    }

    @Test
    void aQueryThatForgotTenancyExistsIsAlreadyFiltered() throws Exception {
        // findAll(): the forgotten-WHERE-clause query, verbatim.
        List<String> acmeSees = TenantContext.callWith("acme",
            () -> repository.findAll().stream().map(TenancyProbe::getNote).toList());
        List<String> globexSees = TenantContext.callWith("globex",
            () -> repository.findAll().stream().map(TenancyProbe::getNote).toList());

        assertThat(acmeSees).containsExactly("visible to acme");
        assertThat(globexSees).containsExactly("visible to globex");
    }

    @Test
    void anotherTenantsRowIsUnreachableEvenByItsPrimaryKey() throws Exception {
        // Whether Hibernate answers a cross-tenant load with empty or with an exception is its
        // implementation detail; the property this asserts is that the data never comes back.
        String leaked = TenantContext.callWith("acme", () -> {
            try {
                return repository.findById(globexRowId).map(TenancyProbe::getNote).orElse(null);
            } catch (RuntimeException hibernateRefused) {
                return null;
            }
        });
        assertThat(leaked).isNull();
    }

    @Test
    void workWithNoTenantBoundCannotTouchTheDatabaseAtAll() {
        // No callWith: this is the scheduled job or message consumer someone forgot to bind.
        // The failure observed is earlier and louder than the tenant_id NOT NULL fence this
        // test originally expected: with the resolver configured, Hibernate refuses to open a
        // session for an unbound thread, so the mistake cannot even reach the database. The
        // NOT NULL column stays in every migration as the second, schema-owned fence.
        //
        // The same refusal applies to entities that are not tenant-scoped, which means the
        // platform side (no tenant bound, by design) currently cannot use JPA at all. That is
        // the right default until someone needs otherwise; the deliberate opt-in for it is the
        // resolver's root-tenant mechanism, and it arrives with the first platform-side reader
        // (the tenant table, T-1.2/T-1.5) rather than speculatively here.
        assertThatThrownBy(() -> repository.save(new TenancyProbe("orphan")))
            .isInstanceOf(CannotCreateTransactionException.class);
    }
}
