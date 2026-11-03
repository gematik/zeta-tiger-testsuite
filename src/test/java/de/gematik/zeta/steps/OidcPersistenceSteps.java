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

import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.KeycloakReadOnlyStore;
import io.cucumber.java.de.Dann;
import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only OIDC and SMC-B user-data observations in the Keycloak persistent-session profile.
 */
public class OidcPersistenceSteps {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Reads the committed database row of this client/session and compares its mapping with the ID
   * token.
   *
   * @param clientId registered client from the successful token request
   * @param sessionId signed access-token session identifier
   * @param identifier independent ID-token identity claim
   * @param profession independent ID-token profession claim
   * @param organization independent ID-token organization claim
   */
  @Dann("prüfe OIDC-Persistenz: Client {tigerResolvedString}, Session {tigerResolvedString}, KVNR {tigerResolvedString}, Profession {tigerResolvedString}, Organisation {tigerResolvedString}")
  public void verifyStoredUserData(
      String clientId,
      String sessionId,
      String identifier,
      String profession,
      String organization) {
    final var query = persistenceQuery(config("realmName"), clientId, sessionId);
    var row = parseStoredRow(KeycloakReadOnlyStore.read(query), clientId, sessionId);
    var userInfo = row.path("user_info");
    new SchemaValidationSteps()
        .validateJsonAgainstYamlSchema(
            JSON.writeValueAsString(userInfo), "schemas/v_2_0/schemas/zeta-user-info.yaml");
    verifyMapping(row, identifier, profession, organization);
    SerenityReportUtils.addCustomData(
        "Persisted OIDC user data",
        "Read-only transaction: realm="
            + config("realmName")
            + ", client="
            + clientId
            + ", session="
            + sessionId
            + "\n"
            + "Tables: offline_user_session, offline_client_session, client, zeta_client_data,"
            + " zeta_user_data\n"
            + "Stored identifier, professionOID, commonName and organizationName match the"
            + " independent ID-token claims.\n"
            + "Internal session-note/OPA-context fields are projected without substituting expected"
            + " values.");
  }

  static String persistenceQuery(String realm, String clientId, String sessionId) {
    return oidcPersistenceQuery(realm, clientId, sessionId, false);
  }

  private static String oidcPersistenceQuery(String realm, String clientId, String sessionId,
      boolean includeIdToken) {
    var tokenProjection = includeIdToken ? "'id_token', notes->'FEDERATED_ID_TOKEN', " : "";
    return storedUserQuery(realm, clientId, sessionId, "zetaguard.mobile.opaContext", tokenProjection + """
      'note_identifier', notes->'zetaguard.mobileuser.kvnr',
      'note_profession', notes->'zetaguard.mobileuser.profession_oid',
      'user_info', json_build_object(
        'identifier', context->'userIdentifier',
        'professionOID', context->'userProfessionOid',
        'commonName', context->'userCommonName',
        'organizationName', notes->'zetaguard.mobileuser.organization')
        """);
  }

  /**
   * Compares all claim names, JSON types and values in the persisted encrypted ID token with the
   * signed reference token.
   *
   * @param clientId client of this SDK flow
   * @param sessionId session of the verified AS access token
   * @param reference signed original ID token, independently verified by the feature
   */
  @Dann("prüfe das gespeicherte OIDC-ID-Token für Client {tigerResolvedString}, "
      + "Session {tigerResolvedString} gegen das signierte Original {tigerResolvedString}")
  public void verifyStoredIdToken(String clientId, String sessionId, String reference) {
    var row = parseStoredRow(KeycloakReadOnlyStore.read(
        oidcPersistenceQuery(config("realmName"), clientId, sessionId, true)), clientId, sessionId);
    require(row.path("id_token").isTextual(), "Persisted encrypted ID token is missing");
    var stored = OidcTokenSteps.decryptSignedToken(
        row.path("id_token").textValue(), OidcDecryptionKeyPreflight.currentKey());
    var names = verifyAllClaims(stored, reference);
    SerenityReportUtils.addCustomData("Persisted ID-token claim retention",
        "All " + names.size() + " claim names, types and values match the independently verified "
            + "original ID token in this committed client/session: " + String.join(", ", names)
            + ".");
  }

  static List<String> verifyAllClaims(String storedToken, String referenceToken) {
    JsonNode stored;
    JsonNode reference;
    try {
      stored = JSON.readTree(SignedJWT.parse(storedToken).getPayload().toString());
      reference = JSON.readTree(SignedJWT.parse(referenceToken).getPayload().toString());
    } catch (Exception e) {
      throw new AssertionError("Cannot parse the stored and independently verified ID-token claims");
    }
    require(stored.isObject() && reference.isObject() && !reference.isEmpty(),
        "Expected nonempty ID-token claim objects");
    var names = new TreeSet<>(reference.propertyNames());
    names.addAll(stored.propertyNames());
    var differences = names.stream()
        .filter(name -> !reference.path(name).equals(stored.path(name))).toList();
    require(differences.isEmpty(),
        "Stored ID-token claim names, types or values differ: " + String.join(", ", differences));
    return List.copyOf(names);
  }

  /**
   * Compares committed SMC-B data with the certificate of the successful SDK token exchange.
   *
   * @param clientId client of the signed access token
   * @param sessionId session of the signed access token
   * @param identifier certificate admission registration number
   * @param commonName certificate subject common name
   * @param profession certificate admission profession OID
   * @param organization optional certificate subject organization name
   */
  @Dann("prüfe SMC-B-Persistenz: Client {tigerResolvedString}, Session {tigerResolvedString}, "
      + "Telematik-ID {tigerResolvedString}, Common Name {tigerResolvedString}, "
      + "Profession {tigerResolvedString}, Organisation {tigerResolvedString}")
  public void verifyStoredSmcbData(String clientId, String sessionId, String identifier,
      String commonName, String profession, String organization) {
    var row = parseStoredRow(KeycloakReadOnlyStore.read(
        smcbPersistenceQuery(config("realmName"), clientId, sessionId)), clientId, sessionId);
    new SchemaValidationSteps().validateJsonAgainstYamlSchema(
        JSON.writeValueAsString(row.path("user_info")), "schemas/v_2_0/schemas/zeta-user-info.yaml");
    verifySmcbMapping(row, identifier, commonName, profession, organization);
    SerenityReportUtils.addCustomData("Persisted SMC-B user data",
        "Read-only committed session observation bound to the signed client/session. "
            + "All certificate fields match zetaguard.smcbContext; an absent certificate "
            + "organization must also be absent in stored user data. "
            + "No expected values are substituted into the database projection.");
  }

  static String smcbPersistenceQuery(String realm, String clientId, String sessionId) {
    return storedUserQuery(realm, clientId, sessionId, "zetaguard.smcbContext", """
      'user_info', jsonb_strip_nulls(jsonb_build_object(
        'identifier', context->'telematikID',
        'professionOID', context->'professionOID',
        'commonName', context->'subjectCommonName',
        'organizationName', context->'subjectOrganisation'))
        """);
  }

  private static String storedUserQuery(String realm, String clientId, String sessionId,
      String contextNote, String projection) {
    for (var value : new String[] {realm, clientId, sessionId}) {
      require(
          value != null && value.matches("[A-Za-z0-9_.-]{1,128}"),
          "Persistence selectors must be resolved safe realm/client/session identifiers");
    }
    // Select only the tested fields. The explicit retention check additionally reads the encrypted
    // ID token, never access/refresh tokens or entire session notes. No row/token values are logged.
    return """
    BEGIN TRANSACTION READ ONLY;
    SET LOCAL statement_timeout = '10s';
    SELECT json_build_object(
      'client_id', c.client_id, 'session_id', s.user_session_id,
      'context_client_id', context->'clientId',
      %s)
    FROM offline_user_session s
    JOIN offline_client_session cs ON cs.user_session_id=s.user_session_id
      AND cs.offline_flag=s.offline_flag AND cs.realm_id=s.realm_id
    JOIN client c ON c.id=cs.client_id AND c.realm_id=s.realm_id
    JOIN realm r ON r.id=s.realm_id
    JOIN zeta_client_data cd ON cd.id=c.id
    JOIN zeta_user_data ud ON ud.id=cd.user_id
    CROSS JOIN LATERAL (SELECT s.data::jsonb->'notes' AS notes) n
    CROSS JOIN LATERAL (SELECT (notes->>'%s')::jsonb AS context) x
    WHERE r.name='%s' AND c.client_id='%s' AND s.user_session_id='%s' AND s.offline_flag='0';
    COMMIT;
    """
        .formatted(projection, contextNote, realm, clientId, sessionId);
  }

  static JsonNode parseStoredRow(String output, String clientId, String sessionId) {
    var rows = nonemptyLines(output);
    require(
        rows.size() == 1,
        "Expected exactly one committed user-data row for the selected client and session; found "
            + rows.size());
    JsonNode row;
    try {
      row = JSON.readTree(rows.getFirst());
    } catch (RuntimeException e) {
      throw new AssertionError("Could not parse the selected persisted user-data projection");
    }
    require(row != null && row.isObject(), "Persisted user-data projection is not an object");
    equalText(row, "client_id", clientId);
    equalText(row, "context_client_id", clientId);
    equalText(row, "session_id", sessionId);
    require(row.path("user_info").isObject(), "Persisted user-info projection is missing");
    return row;
  }

  static void verifyMapping(
      JsonNode row, String identifier, String profession, String organization) {
    require(
        identifier != null && identifier.matches("[A-Z][0-9]{9}"),
        "Expected a resolved synthetic ID-token KVNR");
    require(
        profession != null && !profession.isBlank() && !profession.contains("${"),
        "Expected a resolved ID-token profession");
    require(
        organization != null && !organization.isBlank() && !organization.contains("${"),
        "Expected a resolved ID-token organization");
    equalText(row, "note_identifier", identifier);
    equalText(row, "note_profession", profession);
    var userInfo = row.path("user_info");
    equalText(userInfo, "identifier", identifier);
    equalText(userInfo, "professionOID", profession);
    equalText(userInfo, "commonName", identifier);
    equalText(userInfo, "organizationName", organization);
  }

  static void verifySmcbMapping(JsonNode row, String identifier, String commonName,
      String profession, String organization) {
    for (var value : new String[] {identifier, commonName, profession}) {
      require(value != null && !value.isBlank() && !value.contains("${"),
          "Expected resolved nonempty certificate reference fields");
    }
    require(organization != null && !organization.contains("${"),
        "Expected a resolved optional certificate organization");
    var info = row.path("user_info");
    equalText(info, "identifier", identifier);
    equalText(info, "commonName", commonName);
    equalText(info, "professionOID", profession);
    if (organization.isEmpty()) {
      require(!info.has("organizationName"),
          "Stored organization must be absent when absent in the certificate");
    } else {
      equalText(info, "organizationName", organization);
    }
  }

  private static void equalText(JsonNode node, String name, String expected) {
    require(
        node.path(name).isTextual() && expected.equals(node.path(name).textValue()),
        "Persisted field "
            + name
            + " does not match its independent transaction/ID-token reference");
  }

  private static List<String> nonemptyLines(String text) {
    return text.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
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
