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

package de.gematik.zeta.testdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkLogoutProbeTest {
  private static final Map<String, String> BEFORE = Map.of("at:scope", "access-original", "rt:scope", "refresh-original");

  @Test
  void copiesOnlyFreshSoftwareKeysWithoutMutatingSdkStorage() {
    var storage = keyStorage();
    storage.putAll(BEFORE);
    var unchanged = Map.copyOf(storage);
    var lines = SdkLogoutProbe.encodedKeyCopy(storage).lines().toList();
    assertEquals(4, lines.size());
    for (var line : lines) {
      var pair = line.split("=", 2);
      assertEquals(storage.get(pair[0] + ":fixture"), new String(Base64.getDecoder().decode(pair[1]), StandardCharsets.UTF_8));
    }
    assertEquals(unchanged, storage);
  }

  @Test
  void missingBlankAndAmbiguousSoftwareKeysCannotBeCopied() {
    var missing = keyStorage();
    missing.remove("dpop_private_key:fixture");
    assertThrows(AssertionError.class, () -> SdkLogoutProbe.encodedKeyCopy(missing));
    var blank = keyStorage();
    blank.put("client_private_key:fixture", " ");
    assertThrows(AssertionError.class, () -> SdkLogoutProbe.encodedKeyCopy(blank));
    var ambiguous = keyStorage();
    ambiguous.put("client_private_key:other", "private-sentinel");
    var error = assertThrows(AssertionError.class, () -> SdkLogoutProbe.encodedKeyCopy(ambiguous));
    assertFalse(error.toString().contains("private-sentinel"));
  }

  private static Map<String, String> keyStorage() {
    return new HashMap<>(Map.of("client_public_key:fixture", "client-public", "client_private_key:fixture", "client-private",
        "dpop_public_key:fixture", "dpop-public", "dpop_private_key:fixture", "dpop-private"));
  }

  @Test
  void recordsRemovalWithoutMutatingSdkStorage() {
    var after = Map.of("registration", "retained");
    var result = SdkLogoutProbe.storageEvidence(BEFORE, after);
    assertEquals("0", result.get("access_entries_after"));
    assertEquals("false", result.get("access_value_retained"));
    assertEquals(1, after.size());
    assertEquals(2, BEFORE.size());
  }

  @Test
  void catchesTokensRetainedUnderAnotherKeyOrInsideSerializedData() {
    var result = SdkLogoutProbe.storageEvidence(BEFORE,
        Map.of("other", "{\"access\":\"access-original\",\"refresh\":\"refresh-original\"}"));
    assertEquals("true", result.get("access_value_retained"));
    assertEquals("true", result.get("refresh_value_retained"));
  }

  @Test
  void catchesAnotherTokenLeftInTheSameContext() {
    var result = SdkLogoutProbe.storageEvidence(BEFORE, Map.of("at:other", "another-token"));
    assertEquals("1", result.get("access_entries_after"));
    assertEquals("false", result.get("access_value_retained"));
  }

  @Test
  void rejectsMissingBlankAndAmbiguousInitialToken() {
    assertThrows(AssertionError.class, () -> SdkLogoutProbe.uniqueToken(Map.of(), "at:"));
    assertThrows(AssertionError.class, () -> SdkLogoutProbe.uniqueToken(Map.of("at:a", ""), "at:"));
    assertThrows(AssertionError.class, () -> SdkLogoutProbe.uniqueToken(Map.of("at:a", "one", "at:b", "two"), "at:"));
  }

  @Test
  void hashesTheExactUtf8Value() throws Exception {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SdkLogoutProbe.hash("abc"));
  }
}
