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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SdkLogoutRuntimeTest {
  @Test
  void decodesOnlyTheFourPrivateFixtureRolesWithoutPublishingThem() {
    var decoded = SdkLogoutRuntime.decodeKeyCopy(keyCopy());
    assertEquals(4, decoded.size());
    assertEquals("test-key-sentinel", decoded.get("client_private_key"));
    assertThrows(UnsupportedOperationException.class, () -> decoded.put("extra", "value"));
  }

  @Test
  void incompleteDuplicateOrExtraKeyRolesCannotPass() {
    for (var value : List.of("", "client_public_key=dGVzdA==\n", keyCopy() + "client_public_key=dGVzdA==\n",
        keyCopy() + "access_token=dGVzdA==\n")) {
      assertThrows(AssertionError.class, () -> SdkLogoutRuntime.decodeKeyCopy(value));
    }
  }

  @Test
  void malformedEmptyAndOversizedCopiesAreRejectedWithoutLeakingTheInput() {
    for (var value : List.of("client_public_key=sensitive-sentinel!", "client_public_key=", "sensitive-sentinel".repeat(2000))) {
      var error = assertThrows(AssertionError.class, () -> SdkLogoutRuntime.decodeKeyCopy(value));
      assertFalse(error.toString().contains("sensitive-sentinel"));
    }
  }

  private static String keyCopy() {
    var encoded = Base64.getEncoder().encodeToString("test-key-sentinel".getBytes(StandardCharsets.UTF_8));
    return String.join("=" + encoded + "\n", "client_public_key", "client_private_key", "dpop_public_key", "dpop_private_key", "");
  }

  @Test
  void acceptsTheSanitizedProtocol() {
    assertEquals("abc", SdkLogoutRuntime.parseReport("protocol=sdk-logout-v1\nidentity=abc\n").get("identity"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "protocol=wrong", "protocol=sdk-logout-v1\nprotocol=sdk-logout-v1",
      "protocol=sdk-logout-v1\nprivate_key=secret", "protocol=sdk-logout-v1\nSDK log text",
      "protocol=sdk-logout-v1\nidentity=a b"})
  void rejectsUnexpectedOrPotentiallySensitiveOutput(String value) {
    assertThrows(AssertionError.class, () -> SdkLogoutRuntime.parseReport(value));
  }

  @Test
  void acceptsTheUnchangedReadyRuntime() {
    var pod = pod();
    assertDoesNotThrow(() -> SdkLogoutRuntime.verifyUnchanged(pod, pod.deepCopy(), "testdriver"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"containerID", "imageID", "restartCount", "ready"})
  void rejectsRuntimeDriftOrLossOfReadiness(String field) {
    var before = pod();
    var after = pod();
    ((ObjectNode) after.path("status").path("containerStatuses").get(0)).put(field, "changed");
    assertThrows(AssertionError.class, () -> SdkLogoutRuntime.verifyUnchanged(before, after, "testdriver"));
  }

  @Test
  void rejectsReplacementAndConfigurationChange() {
    var before = pod();
    var after = pod();
    ((ObjectNode) after.path("metadata")).put("uid", "another");
    assertThrows(AssertionError.class, () -> SdkLogoutRuntime.verifyUnchanged(before, after, "testdriver"));
    var changed = pod();
    ((ObjectNode) changed.path("spec")).put("changed", true);
    assertThrows(AssertionError.class, () -> SdkLogoutRuntime.verifyUnchanged(before, changed, "testdriver"));
  }

  private static ObjectNode pod() {
    return (ObjectNode) new ObjectMapper().readTree("""
        {"metadata":{"uid":"test-uid"},"spec":{"value":"test"},
         "status":{"containerStatuses":[{"name":"testdriver","ready":true,"containerID":"one",
           "imageID":"image-sha256","restartCount":0}]}}
        """);
  }
}
