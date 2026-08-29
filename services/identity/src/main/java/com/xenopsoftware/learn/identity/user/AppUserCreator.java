package com.xenopsoftware.learn.identity.user;

import com.xenopsoftware.learn.identity.authz.SystemRoleSeeder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the {@code app_user} row in its own transaction (T-1.2, hardened by T-2.6).
 *
 * <p>REQUIRES_NEW, and it is load-bearing rather than tidy. A person existing is not contingent
 * on whatever action they were attempting: if the row were created inside the caller's
 * transaction, then a refused action would take the person with it — and worse, anything writing
 * an audit entry in a second transaction would <b>block forever</b> on a foreign key pointing at
 * that uncommitted row while the caller waited for the audit to finish. T-2.6's refusal audit is
 * exactly that shape, and it hung the build before this existed.
 *
 * <p>Its own bean because self-invocation does not pass through the proxy: calling a
 * {@code @Transactional} method from inside the same class would silently do nothing.
 */
@Component
public class AppUserCreator {

    private final AppUserRepository repository;
    private final SystemRoleSeeder systemRoles;

    public AppUserCreator(AppUserRepository repository, SystemRoleSeeder systemRoles) {
        this.repository = repository;
        this.systemRoles = systemRoles;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppUser create(String email, String displayName, String idpSub) {
        // saveAndFlush so the row is real before this returns -- callers reach it with raw SQL
        // that does not see Hibernate pending inserts, and the constraint violation that
        // arbitrates the first-login race must surface here rather than at some later commit.
        AppUser created = repository.saveAndFlush(new AppUser(email, displayName, idpSub));
        // A new person in a tenant nobody has logged into yet: project its role templates now
        // rather than at the next restart (T-2.7).
        systemRoles.ensureSeededFor(created.getTenantId());
        return created;
    }
}
