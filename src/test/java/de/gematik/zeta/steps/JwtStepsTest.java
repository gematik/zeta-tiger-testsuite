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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSObject;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.JwtVariant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link JwtSteps}.
 */
class JwtStepsTest {

  private final JwtSteps steps = new JwtSteps();

  @Test
  void signsProvidedPayloadAndPreservesCompleteHeader() throws Exception {
    var pem = readKey("src/test/resources/keys/popp-token-server_ecKey.pem");
    var original = JwtTestHelper.createSignedJwt(
        "{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"original-key\"}",
        "{\"sub\":\"original\"}", JwtTestHelper.loadPrivateKey(pem));
    var payload = "{\"sub\":\"replacement\",\"extra\":{\"nested\":[1,\"unchanged\"]}}";
    steps.signJwtPayload(payload, original, pem, "jwtSigningTest.result");
    var signed = TigerGlobalConfiguration.readString("jwtSigningTest.result");
    assertEquals(JWSObject.parse(original).getHeader().toJSONObject(),
        JWSObject.parse(signed).getHeader().toJSONObject());
    assertEquals(payload, JWSObject.parse(signed).getPayload().toString());
    steps.verifyJwtSignatureWithPublicKeyFromPrivateKey(signed, pem);
  }

  @Test
  void rejectsNonObjectJwtPayload() throws Exception {
    var pem = readKey("src/test/resources/keys/popp-token-server_ecKey.pem");
    var original = JwtTestHelper.createSignedJwt(pem);
    assertThrows(AssertionError.class,
        () -> steps.signJwtPayload("[]", original, pem, "jwtSigningTest.invalid"));
  }

  /**
   * Accepts ordered root algorithms and verifies the raw ES256 signature.
   *
   * @param first first algorithm value
   * @param last last algorithm value
   * @throws Exception if fixture loading or signing fails
   */
  @ParameterizedTest
  @CsvSource({"ES256,RS999", "RS999,ES256", "ES256,ES256"})
  void duplicateAlgPreservesSignature(String first, String last) throws Exception {
    var pem = readKey("src/test/resources/keys/popp-token-server_ecKey.pem");
    var jwt = JwtTestHelper.createSignedJwt(
        "{\"jwk\":{\"alg\":\"ES384\"},\"alg\":\"" + first
            + "\",\"alg\":\"" + last + "\"}", "{}",
        JwtTestHelper.loadPrivateKey(pem));

    steps.verifyJwtVariantIsStructurallyApplied(JwtVariant.DUPLICATE_ALG_HEADERS, jwt,
        "[\"" + first + "\",\"" + last + "\"]");
    steps.verifyJwtVariantHasExpectedLocalSignatureIntegrityWithPublicKeyFromPrivateKey(
        JwtVariant.DUPLICATE_ALG_HEADERS, jwt, pem);
  }

  /**
   * Rejects incorrect ordering, values, counts, types and nested substitutes.
   *
   * @param header raw JOSE header
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "{\"alg\":\"RS999\",\"alg\":\"ES256\"}",
      "{\"alg\":\"ES256\",\"alg\":\"ES384\"}",
      "{\"alg\":\"RS999\"}",
      "{\"alg\":\"ES256\",\"jwk\":{\"alg\":\"RS999\"}}",
      "{\"alg\":\"ES256\",\"keys\":[{\"alg\":\"RS999\"}]}",
      "{\"alg\":\"ES256\",\"alg\":\"RS999\",\"alg\":\"RS999\"}",
      "{\"alg\":\"ES256\",\"alg\":null}",
      "{\"alg\":\"ES256\",\"alg\":\"RS999\""
  })
  void duplicateAlgRejectsIncorrectHeaders(String header) {
    var jwt = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(header.getBytes(StandardCharsets.UTF_8)) + ".e30.signature";

    assertThrows(AssertionError.class, () -> steps.verifyJwtVariantIsStructurallyApplied(
        JwtVariant.DUPLICATE_ALG_HEADERS, jwt, "[\"ES256\",\"RS999\"]"));
  }

  /**
   * Rejects malformed or incomplete ordered algorithm parameters.
   *
   * @param parameter ordered algorithm parameter
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"[]", "[\"ES256\"]", "{}", "null", "[null,\"RS999\"]",
      "[1,\"RS999\"]", "[true,\"RS999\"]", "[\"\",\"RS999\"]",
      "[\" \",\"RS999\"]", "[\"ES256\",", "", " ", "ES256,RS999",
      "[\"ES256\",\"RS999\"] []"})
  void duplicateAlgRejectsInvalidParameters(String parameter) {
    assertThrows(AssertionError.class, () -> steps.verifyJwtVariantIsStructurallyApplied(
        JwtVariant.DUPLICATE_ALG_HEADERS, "e30.e30.signature", parameter));
  }

  /** Verifies that longer algorithm lists preserve repeated values and their order. */
  @Test
  void duplicateAlgAcceptsMultipleOrderedValues() {
    var parameter = "[\"ES256\", \"ES256\", \"RS999\"]";
    var header = "{\"alg\":\"ES256\",\"alg\":\"ES256\",\"alg\":\"RS999\"}";
    var jwt = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(header.getBytes(StandardCharsets.UTF_8)) + ".e30.signature";
    org.assertj.core.api.Assertions.assertThat(
        JwtVariant.DUPLICATE_ALG_HEADERS.manipulationValue(parameter)).isEqualTo(parameter);
    assertDoesNotThrow(() -> steps.verifyJwtVariantIsStructurallyApplied(
        JwtVariant.DUPLICATE_ALG_HEADERS, jwt, parameter));
  }

  /**
   * Verifies that public-key derivation works for PKCS#8 EC private key PEM.
   *
   * @throws Exception if fixture loading or JWT signing fails
   */
  @Test
  void verifyJwtSignatureWithPublicKeyFromPrivateKeyAcceptsPkcs8EcPrivateKey()
      throws Exception {
    var privateKeyPem = readKey("src/test/resources/keys/popp-token-server_ecKey.pem");
    var jwt = JwtTestHelper.createSignedJwt(privateKeyPem);

    assertDoesNotThrow(() -> steps.verifyJwtSignatureWithPublicKeyFromPrivateKey(jwt, privateKeyPem));
  }

  /**
   * Verifies that public-key derivation works for SEC1 EC private key PEM.
   *
   * @throws Exception if fixture loading or JWT signing fails
   */
  @Test
  void verifyJwtSignatureWithPublicKeyFromPrivateKeyAcceptsSec1EcPrivateKey()
      throws Exception {
    var privateKeyPem = readKey("src/test/resources/keys/popp-token-foreign_ecKey.pem");
    var jwt = JwtTestHelper.createSignedJwt(privateKeyPem);

    assertDoesNotThrow(() -> steps.verifyJwtSignatureWithPublicKeyFromPrivateKey(jwt, privateKeyPem));
  }

  /**
   * Reads one private-key fixture as UTF-8.
   *
   * @param keyPath fixture path
   * @return PEM content
   * @throws IOException if the fixture cannot be read
   */
  private String readKey(String keyPath) throws IOException {
    return Files.readString(Path.of(keyPath), StandardCharsets.UTF_8);
  }
}
