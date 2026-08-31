package com.xenopsoftware.learn.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The realm file this service's identities come from, checked rather than trusted (T-1.7).
 *
 * <p><b>Every declared user must carry an explicit {@code id}, and that is the whole point of
 * this class.</b> Keycloak's declarative import cannot update a realm in place, so the obvious
 * procedure for changing one is to delete it and let the import recreate it — which regenerates
 * the internal id of every user it declares, unless the file states them. This platform stores
 * that id as {@code app_user.idp_sub}, so a regenerated realm is every person in the product
 * pointing at a subject that no longer exists. Survivable (ADR-0104 keeps ownership in our own
 * id, and {@code scripts/realm-relink.sh} repairs the link), and much better avoided.
 *
 * <p>A user added to the file without an id would break that quietly, and only for whoever ran
 * the rebuild — months later, in a hurry. Hence a test rather than a sentence in a runbook.
 */
class LocalRealmTest {

    private static JsonNode realm;

    @BeforeAll
    static void readTheRealmThisRepositoryDeclares() throws IOException {
        Path file = repositoryFile("local/keycloak/realm-xenopslearn.json");
        realm = new ObjectMapper().readTree(Files.readString(file));
    }

    @Test
    void everyDeclaredUserCarriesAnExplicitId() {
        List<String> without = new ArrayList<>();
        for (JsonNode user : realm.path("users")) {
            String id = user.path("id").asText("");
            if (id.isBlank() || !isUuid(id)) {
                without.add(user.path("username").asText("(no username)") + " -> '" + id + "'");
            }
        }
        assertThat(without)
            .as("a user without a declared id gets a fresh one every time the realm is rebuilt, "
                + "which orphans the app_user.idp_sub that points at them (T-1.7)")
            .isEmpty();
    }

    @Test
    void noTwoUsersShareAnId() {
        List<String> ids = new ArrayList<>();
        realm.path("users").forEach(user -> ids.add(user.path("id").asText("")));
        assertThat(ids).doesNotHaveDuplicates();
    }

    /**
     * Provisioning refuses a token with no email, and invitation acceptance refuses one whose
     * email is unverified (T-1.2, T-1.5). A fixture user missing either would fail at first
     * sign-in, in a way that reads as a bug in this service rather than a gap in the file.
     */
    @Test
    void everyDeclaredUserCanActuallyBeProvisioned() {
        List<String> broken = new ArrayList<>();
        for (JsonNode user : realm.path("users")) {
            String username = user.path("username").asText("(no username)");
            if (user.path("email").asText("").isBlank()) {
                broken.add(username + ": no email");
            }
            if (!user.path("emailVerified").asBoolean(false)) {
                broken.add(username + ": email not verified");
            }
            String side = user.path("attributes").path("side").path(0).asText("");
            if (!side.equals("TENANT") && !side.equals("PLATFORM")) {
                broken.add(username + ": side attribute is '" + side + "'");
            }
            boolean bound = !user.path("attributes").path("tenant_id").path(0).asText("").isBlank();
            if (side.equals("TENANT") == !bound) {
                // Tenant-side users carry a tenant; platform staff carry none and are bound to
                // the platform's own tenant by the filter instead (T-1.5).
                broken.add(username + ": side " + side + " with tenant_id " + (bound ? "set" : "absent"));
            }
        }
        assertThat(broken).isEmpty();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    /**
     * Walks up from the module directory to find a file stated relative to the repository root.
     * Surefire runs with the module as its working directory, and a test that silently found
     * nothing would assert nothing.
     */
    private static Path repositoryFile(String relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not find " + relative + " above "
            + Path.of("").toAbsolutePath() + " -- this test asserts nothing without it.");
    }
}
