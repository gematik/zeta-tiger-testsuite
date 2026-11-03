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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SdkIdpOnlyRuntimeTest {
  @Test
  void acceptsOnlyScalarProtocolEvidence() {
    assertEquals(
        "123",
        SdkIdpOnlyRuntime.parseReport("protocol=sdk-idp-only-v1\nowner_otp_message_id=123\n")
            .get("owner_otp_message_id"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "access_token=sentinel",
        "client_private_key=sentinel",
        "owner_client=sentinel\nowner_client=again",
        "owner_client=secret!",
        "protocol=wrong"
      })
  void rejectsUnexpectedOrDuplicateOutputWithoutExposingIt(String value) {
    var error =
        assertThrows(
            AssertionError.class,
            () -> SdkIdpOnlyRuntime.parseReport("protocol=sdk-idp-only-v1\n" + value));
    assertFalse(error.getMessage().contains("sentinel"));
    assertFalse(error.getMessage().contains("secret"));
  }
}
