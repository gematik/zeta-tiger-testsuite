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

package de.gematik.zeta.steps;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.KeycloakReadOnlyStore;
import io.cucumber.java.de.Dann;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Independent read-only inventory for two real SDK clients of one synthetic identity.
 */
public class ClientManagementSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private JsonNode before;
  private String survivor;
  private String target;
  private JsonNode foreignBefore;
  private String foreignClient;

  /**
   * Establishes that the two separately authenticated SDK clients belong to the same identity.
   *
   * @param caller client whose RAT will authorize the operation
   * @param other distinct target client
   * @param email independently configured and verified F1
   */
  @Dann("die Clients {tigerResolvedString} und {tigerResolvedString} gehören allein zur Identität mit F1 {tigerResolvedString}")
  public void beforeDelete(String caller, String other, String email) {
    var row = read(query(config("realmName"), caller, other));
    verifyBefore(row, caller, other, email);
    survivor = caller;
    target = other;
    before = row;
  }

  /**
   * Requires actual removal of the target while the caller and the same identity/F1 survive.
   *
   * @throws InterruptedException if the bounded persistence observation is interrupted
   */
  @Dann("nur der andere Client wurde gelöscht und der aufrufende Client bleibt derselben Identität zugeordnet")
  public void afterDelete() throws InterruptedException {
    require(before != null, "Missing independent two-client inventory");
    var after =
        BoundedObservation.await(
            () -> read(query(config("realmName"), survivor, target)),
            row -> number(row, "target_count") == 0,
            java.time.Duration.ofSeconds(10),
            java.time.Duration.ofMillis(250),
            "Client deletion was not observed within the ten-second profile bound");
    verifyAfter(before, after, survivor);
    SerenityReportUtils.addCustomData(
        "Other-client deletion inventory",
        "Committed read-only before/after projections: two distinct clients of the same verified"
            + " identity, then only the caller. The target's client row is absent; identity/user"
            + " IDs and verified F1 are unchanged. Raw ZETA metadata rows before/after: "
            + before.path("raw_registration_count").asText()
            + "/"
            + after.path("raw_registration_count").asText()
            + ". Metadata without an existing client is reported separately, not counted as a live"
            + " registration.");
  }

  /**
   * Establishes an existing target of an independently authenticated, different identity.
   *
   * @param client distinct third client from its successful SDK registration
   * @param sibling second foreign client, excluding last-client protection as the rejection reason
   * @param email independently selected foreign identity's verified F1
   */
  @Dann("die fremden Clients {tigerResolvedString} und {tigerResolvedString} gehören allein zu einer anderen Identität mit F1 {tigerResolvedString}")
  public void captureForeign(String client, String sibling, String email) {
    require(before != null, "Establish the caller's own two-client inventory first");
    var row = read(query(config("realmName"), client, client));
    verifyForeignFixture(before, row, client, sibling, email);
    foreignBefore = row;
    foreignClient = client;
  }

  /**
   * Rejecting an operation must leave both identities and all four registrations unchanged.
   */
  @Dann("nach dem fremden Löschversuch bestehen beide Identitäten und alle vier Registrierungen unverändert fort")
  public void verifyRejectedState() {
    require(before != null && foreignBefore != null, "Missing independent ownership fixtures");
    verifyUnchanged(before, read(query(config("realmName"), survivor, target)));
    verifyForeignRetained();
  }

  /**
   * Checks the original foreign identity again after the own-client control request.
   */
  @Dann("die fremde Identität und ihre Registrierungen bestehen weiterhin unverändert fort")
  public void verifyForeignRetained() {
    require(foreignBefore != null, "Missing independent foreign identity fixture");
    verifyUnchanged(foreignBefore, read(query(config("realmName"), foreignClient, foreignClient)));
  }

  /**
   * Assesses both requests together so a deny-all implementation cannot pass the negative test.
   *
   * @param foreignStatus recorded status of the request targeting the foreign client
   * @param ownStatus recorded status of the control targeting another own client with the same RAT
   */
  @Dann("der fremde DELETE-Status {tigerResolvedString} ist 403 und der eigene Kontroll-DELETE-Status {tigerResolvedString} ist erfolgreich")
  public void verifyResponses(String foreignStatus, String ownStatus) {
    require(
        foreignStatus.matches("[1-5][0-9]{2}") && ownStatus.matches("[1-5][0-9]{2}"),
        "Unresolved DELETE response status");
    SerenityReportUtils.addCustomData(
        "Cross-client DELETE responses",
        "Foreign target: "
            + foreignStatus
            + "; own target control with the same RAT: "
            + ownStatus);
    verifyResponsePair(foreignStatus, ownStatus);
  }

  static void verifyResponsePair(String foreignStatus, String ownStatus) {
    var failures = new ArrayList<String>();
    if (!"403".equals(foreignStatus)) {
      failures.add("Foreign client DELETE expected 403, got " + foreignStatus);
    }
    if (ownStatus == null || !ownStatus.matches("2[0-9]{2}")) {
      failures.add("Own-client DELETE control expected 2xx, got " + ownStatus);
    }
    require(failures.isEmpty(), String.join("; ", failures));
  }

  static void verifyForeignFixture(
      JsonNode owner, JsonNode foreign, String client, String sibling, String email) {
    safe(client);
    safe(sibling);
    require(
        !client.equals(sibling),
        "The foreign target must have a distinct remaining sibling client");
    require(
        email != null && email.matches("[A-Z][0-9]{9}@[A-Za-z0-9.-]+"),
        "Expected the fresh foreign identity's F1");
    for (var field : List.of("identity_id", "user_id")) {
      require(
          foreign.path(field).isTextual()
              && !foreign.path(field).asText().isBlank()
              && !foreign.get(field).equals(owner.path(field)),
          "The foreign target does not belong to an independent identity: " + field);
    }
    require(
        foreign.path("email").isTextual()
            && email.equalsIgnoreCase(foreign.path("email").asText())
            && !email.equalsIgnoreCase(owner.path("email").asText()),
        "Wrong foreign identity's F1");
    require(
        foreign.path("email_verified").isBoolean() && foreign.path("email_verified").booleanValue(),
        "Foreign F1 is not verified");
    require(
        clients(foreign).equals(Set.of(client, sibling))
            && java.util.Collections.disjoint(clients(owner), clients(foreign))
            && number(foreign, "registration_count") == 2
            && number(foreign, "target_count") == 1,
        "Expected two existing clients of the distinct foreign identity; the target must not be its"
            + " last client");
  }

  static void verifyUnchanged(JsonNode first, JsonNode last) {
    for (var field :
        List.of(
            "identity_id",
            "user_id",
            "email",
            "email_verified",
            "client_ids",
            "registration_count",
            "target_count")) {
      require(
          first.hasNonNull(field) && first.get(field).equals(last.get(field)),
          "Rejected DELETE changed identity/client state: " + field);
    }
  }

  static void verifyBefore(JsonNode row, String caller, String other, String email) {
    safe(caller);
    safe(other);
    require(!caller.equals(other), "The deletion target must be another client");
    require(
        email != null && email.matches("[A-Za-z][0-9]{9}@[A-Za-z0-9.-]+"),
        "Expected the fresh synthetic identity's F1");
    for (var field : List.of("identity_id", "user_id")) {
      require(
          row.path(field).isTextual() && !row.path(field).asText().isBlank(),
          "Missing identity mapping: " + field);
    }
    require(
        row.path("email").isTextual() && email.equalsIgnoreCase(row.path("email").asText()),
        "Wrong bound F1");
    require(
        row.path("email_verified").isBoolean() && row.path("email_verified").booleanValue(),
        "F1 has not been verified");
    require(
        clients(row).equals(Set.of(caller, other))
            && number(row, "registration_count") == 2
            && number(row, "target_count") == 1,
        "The caller and target are not the complete same-identity client inventory");
  }

  static void verifyAfter(JsonNode first, JsonNode last, String caller) {
    for (var field : List.of("identity_id", "user_id", "email", "email_verified")) {
      require(
          first.hasNonNull(field) && first.get(field).equals(last.get(field)),
          "Identity/F1 changed or disappeared: " + field);
    }
    require(
        clients(last).equals(Set.of(caller))
            && number(last, "registration_count") == 1
            && number(last, "target_count") == 0,
        "The target alone must be removed and the original caller must remain");
  }

  static String query(String realm, String caller, String other) {
    safe(realm);
    safe(caller);
    safe(other);
    // ZETA identity IDs map to realm-scoped Keycloak usernames, not user_entity.id.
    return """
    BEGIN TRANSACTION READ ONLY;
    SET LOCAL statement_timeout = '5s';
    SELECT json_build_object('identity_id', z.id, 'user_id', u.id, 'email', u.email, 'email_verified', u.email_verified,
      'client_ids', (SELECT coalesce(json_agg(c.client_id ORDER BY c.client_id), '[]'::json)
        FROM zeta_client_data d JOIN client c ON c.id=d.id WHERE d.user_id=z.id AND c.realm_id=r.id),
      'registration_count', (SELECT count(*) FROM zeta_client_data d JOIN client c ON c.id=d.id
        WHERE d.user_id=z.id AND c.realm_id=r.id),
      'raw_registration_count', (SELECT count(*) FROM zeta_client_data d WHERE d.user_id=z.id),
      'target_count', (SELECT count(*) FROM client c WHERE c.realm_id=r.id AND c.client_id='%s'))
    FROM zeta_user_data z JOIN user_entity u ON u.username=z.id JOIN realm r ON r.id=u.realm_id
    WHERE r.name='%s' AND EXISTS (SELECT 1 FROM zeta_client_data d JOIN client c ON c.id=d.id
      WHERE d.user_id=z.id AND c.realm_id=r.id AND c.client_id='%s');
    COMMIT;
    """
        .formatted(other, realm, caller);
  }

  static JsonNode parse(String output) {
    var rows = lines(output);
    require(
        rows.size() == 1,
        "Expected one committed identity belonging to the original caller; found " + rows.size());
    JsonNode row;
    try {
      row = JSON.readTree(rows.getFirst());
    } catch (RuntimeException failure) {
      throw new AssertionError("Malformed identity inventory projection");
    }
    require(row != null && row.isObject(), "Identity inventory is not an object");
    return row;
  }

  private static Set<String> clients(JsonNode row) {
    var list = row.path("client_ids");
    require(list.isArray(), "Missing client inventory");
    var result = new HashSet<String>();
    for (var item : list) {
      require(
          item.isTextual() && !item.asText().isBlank() && result.add(item.asText()),
          "Invalid/duplicate client inventory entry");
    }
    return Set.copyOf(result);
  }

  private static long number(JsonNode row, String field) {
    require(row.path(field).isIntegralNumber(), "Missing/noninteger inventory field: " + field);
    return row.path(field).longValue();
  }

  private static JsonNode read(String sql) {
    return parse(KeycloakReadOnlyStore.read(sql));
  }

  private static List<String> lines(String value) {
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
  }

  private static void safe(String value) {
    require(
        value != null && value.matches("[A-Za-z0-9_.-]{1,128}"),
        "Unsafe/unresolved client inventory selector");
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
