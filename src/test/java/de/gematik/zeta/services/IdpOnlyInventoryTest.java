/*
 * #%L
 * ZETA Testsuite
 * %%
 * (C) achelos GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class IdpOnlyInventoryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, String> CLIENTS =
      Map.of("owner_client", "c1", "attacker_client", "c2", "control_client", "c3");

  @Test
  void singleClientReadIsRealmScopedAndRejectsMissingDuplicateAndForeignRows() {
    try (var configuration = org.mockito.Mockito.mockStatic(
        de.gematik.test.tiger.common.config.TigerGlobalConfiguration.class);
        var store = org.mockito.Mockito.mockStatic(KeycloakReadOnlyStore.class)) {
      configuration.when(() -> de.gematik.test.tiger.common.config.TigerGlobalConfiguration
          .readStringOptional("zetaDeploymentConfig.keycloak.realmName"))
          .thenReturn(java.util.Optional.of("test-realm"));
      configuration.when(() -> de.gematik.test.tiger.common.config.TigerGlobalConfiguration
          .resolvePlaceholders("test-realm")).thenReturn("test-realm");
      var response = new java.util.concurrent.atomic.AtomicReference<>("[{\"client_id\":\"selected\"}]");
      store.when(() -> KeycloakReadOnlyStore.read(org.mockito.ArgumentMatchers.anyString()))
          .thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertTrue(sql.stripLeading().startsWith("BEGIN TRANSACTION READ ONLY;"));
            org.junit.jupiter.api.Assertions.assertTrue(sql.contains("r.name='test-realm' AND c.client_id='selected'"));
            return response.get();
          });
      var inventory = new IdpOnlyInventory();
      org.junit.jupiter.api.Assertions.assertEquals("selected", inventory.readClient("selected").path("client_id").asString());
      for (var invalid : List.of("[]", "[{\"client_id\":\"foreign\"}]",
          "[{\"client_id\":\"selected\"},{\"client_id\":\"selected\"}]")) {
        response.set(invalid);
        assertThrows(AssertionError.class, () -> inventory.readClient("selected"));
      }
    }
  }

  @Test
  void singleClientReadRejectsSqlInjectionBeforeDatabaseAccess() {
    try (var store = org.mockito.Mockito.mockStatic(KeycloakReadOnlyStore.class)) {
      assertThrows(AssertionError.class, () -> new IdpOnlyInventory().readClient("x' OR true--"));
      store.verifyNoInteractions();
    }
  }

  private static ArrayNode state() {
    var rows = JSON.createArrayNode();
    for (var client : List.of("c1", "c2", "c3")) {
      var row =
          rows.addObject()
              .put("client_id", client)
              .put("identity_id", "i1")
              .put("user_id", "u1")
              .put("email", "A123456789@example.org")
              .put("email_verified", true)
              .put("status", client.equals("c2") ? "OTP_PENDING" : "CONFIRMED");
      row.putObject("public_keys").put("jwks.string", "public-" + client);
    }
    return rows;
  }

  @Test
  void acceptsOnlyOriginalIdentityAndUnconfirmedNewClient() {
    assertDoesNotThrow(
        () -> IdpOnlyInventory.verifyInitial(state(), CLIENTS, "A123456789@example.org"));
    assertDoesNotThrow(() -> IdpOnlyInventory.verifyUnchanged(state(), state()));
  }

  @Test
  void mayDiscardOnlyTheProvisionalRegistration() {
    var after = state();
    after.remove(1);
    assertDoesNotThrow(() -> IdpOnlyInventory.verifyUnchanged(state(), after));
    var removedOwner = state();
    removedOwner.remove(0);
    assertThrows(
        AssertionError.class, () -> IdpOnlyInventory.verifyUnchanged(state(), removedOwner));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"identity_id", "user_id", "email", "email_verified", "status", "public_keys"})
  void missingPrerequisiteCannotPass(String field) {
    var rows = state();
    ((ObjectNode) rows.get(1)).remove(field);
    assertThrows(
        AssertionError.class,
        () -> IdpOnlyInventory.verifyInitial(rows, CLIENTS, "A123456789@example.org"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity_id", "user_id", "email", "status", "public_keys"})
  void successfulRebindingOrKeyReplacementCannotPass(String field) {
    for (int index = 0; index < 3; index++) {
      var after = state();
      ((ObjectNode) after.get(index)).put(field, "changed");
      assertThrows(AssertionError.class, () -> IdpOnlyInventory.verifyUnchanged(state(), after));
    }
  }

  @Test
  void prematureConfirmationDifferentIdentityAndDuplicateRowsFail() {
    var confirmed = state();
    ((ObjectNode) confirmed.get(1)).put("status", "CONFIRMED");
    assertThrows(
        AssertionError.class,
        () -> IdpOnlyInventory.verifyInitial(confirmed, CLIENTS, "A123456789@example.org"));
    var foreign = state();
    ((ObjectNode) foreign.get(1)).put("identity_id", "foreign");
    assertThrows(
        AssertionError.class,
        () -> IdpOnlyInventory.verifyInitial(foreign, CLIENTS, "A123456789@example.org"));
    var duplicate = state();
    duplicate.set(2, duplicate.get(1));
    assertThrows(AssertionError.class, () -> IdpOnlyInventory.verifyUnchanged(state(), duplicate));
  }
}
