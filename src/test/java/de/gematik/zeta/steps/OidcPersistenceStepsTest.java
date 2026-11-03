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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Negative controls for transaction binding and independent persisted OIDC claim mapping. */
class OidcPersistenceStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLIENT = "test-client-1";
  private static final String SESSION = "session_1";
  private static final String KVNR = "M404230279";
  private static final String PROFESSION = "1.2.276.0.76.4.49";
  private static final String ORGANIZATION = "109500969";

  @Test
  void committedProjectionMatchesAllIndependentClaims() {
    var row = OidcPersistenceSteps.parseStoredRow(JSON.writeValueAsString(validRow()), CLIENT, SESSION);
    assertDoesNotThrow(() -> OidcPersistenceSteps.verifyMapping(row, KVNR, PROFESSION, ORGANIZATION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"client_id", "context_client_id", "session_id"})
  void anotherTransactionCannotProvidePersistenceEvidence(String field) {
    var row = validRow().put(field, "another-context");
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.parseStoredRow(JSON.writeValueAsString(row), CLIENT, SESSION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"identifier", "professionOID", "commonName", "organizationName"})
  void everyMappedFieldMustBePresentTextualAndCorrect(String field) {
    var row = validRow();
    var info = (ObjectNode) row.path("user_info");
    info.remove(field);
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(row, KVNR, PROFESSION, ORGANIZATION));
    info.putNull(field);
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(row, KVNR, PROFESSION, ORGANIZATION));
    info.put(field, 123);
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(row, KVNR, PROFESSION, ORGANIZATION));
    info.put(field, "different-value");
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(row, KVNR, PROFESSION, ORGANIZATION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"note_identifier", "note_profession"})
  void inconsistentPersistentNotesCannotPassWithCorrectContext(String field) {
    var row = validRow().put(field, "different-value");
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(row, KVNR, PROFESSION, ORGANIZATION));
  }

  @Test
  void emptyOrAmbiguousDatabaseResultsFail() {
    var row = JSON.writeValueAsString(validRow());
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.parseStoredRow("\n", CLIENT, SESSION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.parseStoredRow(row + "\n" + row, CLIENT, SESSION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "{}", "{\"user_info\":null}"})
  void invalidProjectionCannotPass(String projection) {
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.parseStoredRow(projection, CLIENT, SESSION));
  }

  @Test
  void malformedDatabaseOutputDoesNotDiscloseParserContent() {
    var sentinel = "sensitive-sentinel";
    var error = assertThrows(AssertionError.class,
        () -> OidcPersistenceSteps.parseStoredRow("{\"" + sentinel + "\":", CLIENT, SESSION));
    assertFalse(error.toString().contains(sentinel));
    assertNull(error.getCause());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"${unresolved}", "x'; SELECT 'x", "x\n", "a b"})
  void unresolvedOrUnsafeDatabaseSelectorsAreRejected(String selector) {
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.persistenceQuery(selector, CLIENT, SESSION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.persistenceQuery("zeta-guard", selector, SESSION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.persistenceQuery("zeta-guard", CLIENT, selector));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"${unresolved}", " "})
  void independentClaimsMustBeResolved(String claim) {
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(validRow(), claim, PROFESSION, ORGANIZATION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(validRow(), KVNR, claim, ORGANIZATION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifyMapping(validRow(), KVNR, PROFESSION, claim));
  }

  @Test
  void smcbUsesIndependentCommonNameAndOptionalOrganization() {
    var row = smcbRow();
    assertDoesNotThrow(() -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ORGANIZATION));
    ((ObjectNode) row.path("user_info")).remove("organizationName");
    assertDoesNotThrow(() -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ""));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ORGANIZATION));
    ((ObjectNode) row.path("user_info")).put("commonName", "1-TEST");
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"identifier", "commonName", "professionOID", "organizationName"})
  void smcbRejectsMissingMistypedAndWrongCertificateFields(String field) {
    var row = smcbRow();
    var info = (ObjectNode) row.path("user_info");
    info.remove(field);
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ORGANIZATION));
    info.put(field, 123);
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ORGANIZATION));
    info.put(field, "unrelated");
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ORGANIZATION));
  }

  @Test
  void smcbRejectsInventedOrganizationAndUnresolvedReferences() {
    var row = smcbRow();
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, ""));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "${missing}", "Testpraxis", PROFESSION, ORGANIZATION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "", PROFESSION, ORGANIZATION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", null, ORGANIZATION));
    assertThrows(AssertionError.class, () -> OidcPersistenceSteps.verifySmcbMapping(
        row, "1-TEST", "Testpraxis", PROFESSION, "${missing}"));
  }

  @Test
  void bothStorageProfilesRetainBoundedReadOnlyTransactionAndSessionBinding() {
    for (var query : new String[] {
        OidcPersistenceSteps.persistenceQuery("realm", CLIENT, SESSION),
        OidcPersistenceSteps.smcbPersistenceQuery("realm", CLIENT, SESSION)}) {
      assertTrue(query.startsWith("BEGIN TRANSACTION READ ONLY;"));
      assertTrue(query.contains("SET LOCAL statement_timeout = '10s'"));
      assertTrue(query.contains("r.name='realm' AND c.client_id='" + CLIENT
          + "' AND s.user_session_id='" + SESSION + "' AND s.offline_flag='0'"));
      assertFalse(query.contains("FEDERATED_ACCESS_TOKEN"));
    }
    assertThrows(AssertionError.class,
        () -> OidcPersistenceSteps.smcbPersistenceQuery("realm", "x'", SESSION));
  }

  @Test
  void retainedIdTokenComparesAllClaimsWithoutNormalizingJsonTypes() {
    var original = "{\"sub\":\"user\",\"aud\":[\"rp\"],\"amr\":[\"method\"],"
        + "\"urn:telematik:claims:display_name\":\"Testname\",\"iat\":12}";
    assertEquals(5, OidcPersistenceSteps.verifyAllClaims(jwt(original), jwt(original)).size());
    for (var changed : new String[] {
        original.replace("[\"rp\"]", "\"rp\""),
        original.replace("12", "\"12\""),
        original.replace("Testname", "anderer Name"),
        original.replace("\"amr\":[\"method\"],", ""),
        original.replace("{", "{\"extra\":true,")}) {
      assertThrows(AssertionError.class,
          () -> OidcPersistenceSteps.verifyAllClaims(jwt(changed), jwt(original)));
    }
  }

  @Test
  void malformedOrEmptyRetainedClaimsFailWithoutLeakingTokenContents() {
    var error = assertThrows(AssertionError.class,
        () -> OidcPersistenceSteps.verifyAllClaims("sensitive-sentinel", jwt("{}")));
    assertFalse(error.toString().contains("sensitive-sentinel"));
    assertNull(error.getCause());
    assertThrows(AssertionError.class,
        () -> OidcPersistenceSteps.verifyAllClaims(jwt("{}"), jwt("{}")));
  }

  private static String jwt(String payload) {
    // Parsing-only fixture: live signature verification is a separate required feature step.
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8))
        + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".AQ";
  }

  private static ObjectNode smcbRow() {
    var row = validRow();
    ((ObjectNode) row.path("user_info")).put("identifier", "1-TEST")
        .put("commonName", "Testpraxis");
    return row;
  }

  private static ObjectNode validRow() {
    var row = JSON.createObjectNode().put("client_id", CLIENT).put("context_client_id", CLIENT).put("session_id", SESSION)
        .put("note_identifier", KVNR).put("note_profession", PROFESSION);
    row.putObject("user_info").put("identifier", KVNR).put("professionOID", PROFESSION)
        .put("commonName", KVNR).put("organizationName", ORGANIZATION);
    return row;
  }
}
