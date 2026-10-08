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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwtSteps}.
 */
class JwtStepsTest {

  private final JwtSteps steps = new JwtSteps();

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
