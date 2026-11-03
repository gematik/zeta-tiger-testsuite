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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only committed state of the three explicitly selected synthetic SDK clients.
 */
public final class IdpOnlyInventory {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Returns identity, factor status and public registration keys; never credentials.
   */
  public JsonNode read(Map<String, String> evidence) {
    var ids =
        List.of(
            evidence.get("owner_client"),
            evidence.get("attacker_client"),
            evidence.get("control_client"));
    ids.forEach(IdpOnlyInventory::safe);
    var realm = config("realmName");
    safe(realm);
    var sql =
        """
        BEGIN TRANSACTION READ ONLY;
        SET LOCAL statement_timeout = '5s';
        SELECT coalesce(json_agg(row_to_json(v)), '[]'::json) FROM (
          SELECT c.client_id, d.client_registration_status AS status, d.user_id AS identity_id,
            u.id AS user_id, u.email, u.email_verified,
            (SELECT json_object_agg(a.name, a.value) FROM client_attributes a
              WHERE a.client_id=c.id AND a.name IN ('jwks.string','jwt.credential.public.key')) AS public_keys
          FROM client c JOIN realm r ON r.id=c.realm_id
          LEFT JOIN zeta_client_data d ON d.id=c.id
          LEFT JOIN zeta_user_data z ON z.id=d.user_id
          LEFT JOIN user_entity u ON u.username=z.id AND u.realm_id=r.id
          WHERE r.name='%s' AND (c.client_id IN ('%s','%s','%s') OR d.user_id IN (
            SELECT known.user_id FROM zeta_client_data known JOIN client selected ON selected.id=known.id
            WHERE selected.realm_id=r.id AND selected.client_id IN ('%s','%s','%s'))) ORDER BY c.client_id
        ) v;
        COMMIT;
        """
            .formatted(
                realm, ids.get(0), ids.get(1), ids.get(2), ids.get(0), ids.get(1), ids.get(2));
    var rows = KeycloakReadOnlyStore.read(sql).lines().filter(line -> !line.isBlank()).toList();
    require(rows.size() == 1, "Ambiguous IDP-only inventory result");
    return JSON.readTree(rows.getFirst());
  }

  /**
   * Reads one client's committed public registration keys in the configured realm.
   *
   * @param client registered client ID
   * @return the unique committed client row
   */
  public JsonNode readClient(String client) {
    safe(client);
    var realm = config("realmName");
    safe(realm);
    var sql = """
        BEGIN TRANSACTION READ ONLY;
        SET LOCAL statement_timeout = '5s';
        SELECT coalesce(json_agg(row_to_json(v)), '[]'::json) FROM (
          SELECT c.client_id,
            (SELECT json_object_agg(a.name, a.value) FROM client_attributes a
              WHERE a.client_id=c.id AND a.name IN ('jwks.string','jwt.credential.public.key')) AS public_keys
          FROM client c JOIN realm r ON r.id=c.realm_id
          WHERE r.name='%s' AND c.client_id='%s'
        ) v;
        COMMIT;
        """.formatted(realm, client);
    var rows = KeycloakReadOnlyStore.read(sql).lines().filter(line -> !line.isBlank()).toList();
    require(rows.size() == 1, "Ambiguous public F2 inventory result");
    return row(JSON.readTree(rows.getFirst()), client);
  }

  /**
   * Checks that only the two contexts with the original-factor proof are confirmed.
   */
  public static void verifyInitial(JsonNode state, Map<String, String> evidence, String email) {
    require(
        state != null && state.isArray() && state.size() == 3,
        "Expected exactly three selected registrations");
    var owner = row(state, evidence.get("owner_client"));
    require(
        owner.path("identity_id").isString()
            && !owner.path("identity_id").asString().isBlank()
            && owner.path("user_id").isString()
            && !owner.path("user_id").asString().isBlank(),
        "Missing committed original identity");
    for (var role : List.of("owner", "attacker", "control")) {
      var current = row(state, evidence.get(role + "_client"));
      require(
          owner.path("identity_id").equals(current.path("identity_id"))
              && owner.path("user_id").equals(current.path("user_id")),
          "SDK clients belong to different identities");
      require(
          current.path("email").isString()
              && email.equalsIgnoreCase(current.path("email").asString())
              && current.path("email_verified").isBoolean()
              && current.path("email_verified").booleanValue(),
          "Original verified F1 changed");
      require(
          (role.equals("attacker") ? "OTP_PENDING" : "CONFIRMED")
              .equals(current.path("status").asString()),
          "Unexpected registration state for " + role);
      require(
          current.path("public_keys").isObject() && !current.path("public_keys").isEmpty(),
          "Missing original public-key binding");
    }
  }

  /**
   * Provisional pending state may remain; successful recovery or factor replacement may not.
   */
  public static void verifyUnchanged(JsonNode before, JsonNode after) {
    require(
        before != null
            && before.isArray()
            && after != null
            && after.isArray()
            && before.size() == 3
            && (after.size() == 2 || after.size() == 3),
        "Missing original-registration before/after inventory");
    for (var current : after) {
      row(before, current.path("client_id").asString());
    }
    for (var original : before) {
      if ("OTP_PENDING".equals(original.path("status").asString())
          && after
              .valueStream()
              .noneMatch(current -> original.path("client_id").equals(current.path("client_id")))) {
        // Removing only a provisional registration also prevents unauthorized recovery.
        continue;
      }
      require(
          original.equals(row(after, original.path("client_id").asString())),
          "Identity, factor or registration changed after the bypass attempt");
    }
  }

  /**
   * Selects an unambiguous existing registration by its actual SDK client ID.
   */
  public static JsonNode row(JsonNode state, String client) {
    var matches =
        state
            .valueStream()
            .filter(value -> client.equals(value.path("client_id").asString()))
            .toList();
    require(matches.size() == 1, "Missing or duplicate selected registration");
    return matches.getFirst();
  }

  private static void safe(String value) {
    require(
        value != null && value.matches("[A-Za-z0-9_.-]{1,128}"),
        "Unsafe identity inventory selector");
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.keycloak." + key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
