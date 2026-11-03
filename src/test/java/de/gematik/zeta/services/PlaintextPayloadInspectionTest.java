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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Regression tests for encoded and literal message disclosure detection. */
class PlaintextPayloadInspectionTest {
  private static final String MESSAGE = "private-message-5da4a573";
  private static final String EXPECTED = "{\"message\":\"" + MESSAGE + "\"}";

  @ParameterizedTest
  @ValueSource(strings = {"literal", "base64", "base64url", "hex", "percent", "nested"})
  void rejectsCleartextCopies(String encoding) {
    var bytes = EXPECTED.getBytes(StandardCharsets.UTF_8);
    var encoded = switch (encoding) {
      case "base64" -> Base64.getEncoder().encodeToString(bytes);
      case "base64url" -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      case "hex" -> HexFormat.of().formatHex(bytes);
      case "percent" -> URLEncoder.encode(EXPECTED, StandardCharsets.UTF_8);
      case "nested" -> Base64.getEncoder().encodeToString(
          Base64.getEncoder().encode(bytes));
      default -> MESSAGE;
    };
    var observed = "{\"ciphertext\":\"opaque\",\"data\":{\"copy\":\"" + encoded + "\"}}";
    assertThatThrownBy(() -> PlaintextPayloadInspection.assertAbsent(observed, EXPECTED))
        .isInstanceOf(AssertionError.class).hasMessageContaining("message text");
  }

  @Test
  void checksEveryOriginalTextValue() {
    assertThatThrownBy(() -> PlaintextPayloadInspection.assertAbsent(
        "{\"extra\":\"registered_client_activity\"}",
        "{\"type\":\"registered_client_activity\",\"marker\":\"other-secret\"}"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void rejectsVacuousExpectedPayload() {
    assertThatThrownBy(() -> PlaintextPayloadInspection.assertAbsent("{}", "{}"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void acceptsOpaqueCiphertextAndUnrelatedMetadata() {
    assertThatCode(() -> PlaintextPayloadInspection.assertAbsent(
        "{\"ciphertext\":\"s+zPwtPp8nxsAtOaZjN3Bw==\",\"device\":{\"pushkey\":\"own-pusher\"}}",
        EXPECTED)).doesNotThrowAnyException();
  }
}
