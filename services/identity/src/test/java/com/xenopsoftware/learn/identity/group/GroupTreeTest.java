package com.xenopsoftware.learn.identity.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The tree rules against a real Postgres (T-1.3): the descendant set, the guards that keep it a
 * tree, a move that leaves membership alone, and a delete that cannot orphan anyone.
 */
@SpringBootTest
class GroupTreeTest extends PostgresTestHarness {

    @Autowired
    private GroupService groups;

    @Autowired
    private GroupHierarchy hierarchy;

    @Autowired
    private GroupMembershipRepository memberships;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTables() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }

    /**
     * This class creates app_user rows, and group_membership now has a real foreign key to
     * them — so leaving them behind breaks any other class in the module that clears app_user.
     * The class that made the rows removes them, in foreign-key order.
     */
    @org.junit.jupiter.api.AfterEach
    void removeWhatThisClassCreated() {
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void theSubtreeIsEveryDescendantAndTheGroupItself() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            UUID engineering = groups.create("Engineering", company).getId();
            UUID platform = groups.create("Platform", engineering).getId();
            UUID sales = groups.create("Sales", company).getId();

            assertThat(hierarchy.subtreeIds(company))
                .containsExactlyInAnyOrder(company, engineering, platform, sales);
            // A group admin scoped to Engineering reaches Platform and nothing sideways.
            assertThat(hierarchy.subtreeIds(engineering))
                .containsExactlyInAnyOrder(engineering, platform);
            assertThat(hierarchy.subtreeIds(platform)).containsExactly(platform);
            return null;
        });
    }

    @Test
    void reachIsTheUnionOfMembershipBeneathTheGroup() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            UUID engineering = groups.create("Engineering", company).getId();
            UUID sales = groups.create("Sales", company).getId();
            UUID engineer = user("engineer@acme.test");
            UUID seller = user("seller@acme.test");
            groups.addMember(engineering, engineer);
            groups.addMember(sales, seller);

            assertThat(hierarchy.reachableUserIds(Set.of(company)))
                .containsExactlyInAnyOrder(engineer, seller);
            assertThat(hierarchy.reachableUserIds(Set.of(engineering))).containsExactly(engineer);
            // The single most likely leak this product has: a group admin seeing sideways.
            assertThat(hierarchy.reachableUserIds(Set.of(sales))).doesNotContain(engineer);
            return null;
        });
    }

    @Test
    void aUserMayBelongToSeveralGroups() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            UUID engineering = groups.create("Engineering", company).getId();
            UUID onCall = groups.create("On call", company).getId();
            UUID person = user("both@acme.test");
            groups.addMember(engineering, person);
            groups.addMember(onCall, person);

            assertThat(memberships.count()).isEqualTo(2);
            assertThat(hierarchy.reachableUserIds(Set.of(engineering))).containsExactly(person);
            assertThat(hierarchy.reachableUserIds(Set.of(onCall))).containsExactly(person);
            // Adding twice is not a second membership.
            groups.addMember(onCall, person);
            assertThat(memberships.count()).isEqualTo(2);
            return null;
        });
    }

    @Test
    void movingReparentsTheSubtreeWithoutTouchingMembershipRows() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            UUID engineering = groups.create("Engineering", company).getId();
            UUID platform = groups.create("Platform", engineering).getId();
            UUID product = groups.create("Product", company).getId();
            UUID person = user("mover@acme.test");
            groups.addMember(platform, person);
            UUID membershipId = memberships.findByGroupIdAndUserId(platform, person).orElseThrow().getId();

            groups.move(engineering, product);

            // The subtree moved with its root, and the membership row is the same row.
            assertThat(hierarchy.subtreeIds(product))
                .containsExactlyInAnyOrder(product, engineering, platform);
            assertThat(hierarchy.reachableUserIds(Set.of(product))).containsExactly(person);
            assertThat(memberships.findByGroupIdAndUserId(platform, person).orElseThrow().getId())
                .isEqualTo(membershipId);
            return null;
        });
    }

    @Test
    void aMoveThatWouldMakeAGroupItsOwnAncestorIsRefused() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            UUID engineering = groups.create("Engineering", company).getId();
            UUID platform = groups.create("Platform", engineering).getId();

            // The ring an adjacency list grows if nothing checks: nothing could reach these
            // rows and no walk could leave them.
            assertThatThrownBy(() -> groups.move(company, platform))
                .isInstanceOf(GroupStructureException.class)
                .hasMessageContaining("own ancestor");
            assertThatThrownBy(() -> groups.move(company, company))
                .isInstanceOf(GroupStructureException.class);
            return null;
        });
    }

    @Test
    void theTreeCannotGrowPastTheDepthLimit() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID parent = groups.create("root", null).getId();
            for (int depth = 1; depth <= GroupHierarchy.MAX_DEPTH; depth++) {
                parent = groups.create("level-" + depth, parent).getId();
            }
            UUID deepest = parent;
            assertThatThrownBy(() -> groups.create("one too far", deepest))
                .isInstanceOf(GroupStructureException.class)
                .hasMessageContaining("maximum");
            return null;
        });
    }

    @Test
    void aMoveThatWouldPushASubtreePastTheLimitIsRefused() throws Exception {
        TenantContext.callWith("acme", () -> {
            // A three-level branch, and a trunk deep enough that hanging it there overruns.
            UUID branch = groups.create("branch", null).getId();
            UUID mid = groups.create("mid", branch).getId();
            groups.create("leaf", mid);

            UUID trunk = groups.create("trunk", null).getId();
            for (int depth = 1; depth <= GroupHierarchy.MAX_DEPTH - 1; depth++) {
                trunk = groups.create("trunk-" + depth, trunk).getId();
            }
            UUID deepTrunk = trunk;

            assertThat(hierarchy.subtreeHeight(branch)).isEqualTo(2);
            assertThatThrownBy(() -> groups.move(branch, deepTrunk))
                .isInstanceOf(GroupStructureException.class)
                .hasMessageContaining("past the maximum");
            return null;
        });
    }

    @Test
    void deletingAGroupWithPeopleOrChildrenIsRefusedWithTheCounts() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            UUID engineering = groups.create("Engineering", company).getId();
            groups.addMember(engineering, user("held@acme.test"));

            assertThatThrownBy(() -> groups.delete(company))
                .isInstanceOf(GroupStructureException.class)
                .hasMessageContaining("1 child group(s)");
            assertThatThrownBy(() -> groups.delete(engineering))
                .isInstanceOf(GroupStructureException.class)
                .hasMessageContaining("1 member(s)");
            return null;
        });
    }

    @Test
    void deletingWithRehomeMovesPeopleAndChildrenUpFirst() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            UUID engineering = groups.create("Engineering", company).getId();
            UUID platform = groups.create("Platform", engineering).getId();
            UUID person = user("rehomed@acme.test");
            groups.addMember(engineering, person);

            groups.deleteAndRehome(engineering);

            // Nobody is orphaned: the child hangs off Company and the member is in Company.
            assertThat(hierarchy.subtreeIds(company)).containsExactlyInAnyOrder(company, platform);
            assertThat(hierarchy.reachableUserIds(Set.of(company))).containsExactly(person);
            return null;
        });
    }

    @Test
    void theNativeWalkIsTenantScopedEvenThoughTheDiscriminatorCannotSeeIt() throws Exception {
        UUID acmeRoot = TenantContext.callWith("acme", () -> {
            UUID root = groups.create("Shared name", null).getId();
            groups.create("Acme child", root);
            groups.addMember(root, user("acme-person@acme.test"));
            return root;
        });

        TenantContext.callWith("globex", () -> {
            // Same shape, other tenant. The recursive queries are native SQL, which Hibernate
            // @TenantId does NOT filter -- so this is the test that the hand-written tenant_id
            // predicate is actually there and actually correct.
            UUID root = groups.create("Shared name", null).getId();
            groups.create("Globex child", root);

            assertThat(hierarchy.subtreeIds(acmeRoot))
                .as("another tenant root must resolve to nothing, not to its subtree")
                .isEmpty();
            assertThat(hierarchy.reachableUserIds(Set.of(acmeRoot))).isEmpty();
            assertThat(hierarchy.subtreeIds(root)).hasSize(2);
            return null;
        });
    }

    @Test
    void siblingsCannotShareAName() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            groups.create("Engineering", company);
            assertThatThrownBy(() -> groups.create("engineering", company))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            return null;
        });
    }

    @Test
    void rootsAndChildrenReadBackAsWritten() throws Exception {
        TenantContext.callWith("acme", () -> {
            UUID company = groups.create("Company", null).getId();
            groups.create("Engineering", company);
            groups.create("Sales", company);

            assertThat(groups.roots()).extracting(UserGroup::getName).containsExactly("Company");
            List<String> children = groups.children(company).stream().map(UserGroup::getName).toList();
            assertThat(children).containsExactlyInAnyOrder("Engineering", "Sales");
            return null;
        });
    }

    /** An app_user row to be a member; identity owns them, so this is a real FK target. */
    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
            """, id, TenantContext.require(), email, email);
        return id;
    }
}
