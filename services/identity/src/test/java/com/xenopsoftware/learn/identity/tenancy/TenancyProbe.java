package com.xenopsoftware.learn.identity.tenancy;

import com.xenopsoftware.learn.common.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Test-only entity over the test-only {@code tenancy_probe} table (V900): the smallest possible
 * tenant-scoped entity, so {@link TenantOwned}'s discriminator can be proved against a real
 * Postgres before T-1.2's domain tables exist to prove it on.
 */
@Entity
@Table(name = "tenancy_probe")
public class TenancyProbe extends TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note")
    private String note;

    protected TenancyProbe() {}

    public TenancyProbe(String note) {
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getNote() {
        return note;
    }
}
