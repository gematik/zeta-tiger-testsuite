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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.zeta.services.DriverControlClient;
import io.restassured.builder.ResponseBuilder;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RevocationMutationTest {
  @ParameterizedTest
  @CsvSource({"own,valid", "revoked,valid", "expired,valid", "access,valid",
      "own,missing", "own,wrong_signature", "own,expired", "own,wrong_client",
      "unknown,valid", "unknown,missing", "unknown,wrong_signature"})
  void onlyUnknownTokensAreSubstitutedAndAssertionFaultsRemainIsolated(
      String kind, String fault) throws Exception {
    try (var proxies = mockConstruction(TigerProxyManipulationsSteps.class);
        var config = mockStatic(TigerGlobalConfiguration.class);
        var http = mockStatic(TigerHttpClient.class)) {
      config.when(() -> TigerGlobalConfiguration.readStringOptional(anyString()))
          .thenReturn(Optional.of("http://driver.example/storage"));
      var request = mock(RequestSpecification.class);
      when(request.get(anyString())).thenReturn(new ResponseBuilder().setStatusCode(200)
          .setBody("{\"client_private_key\":\"fixture-key\"}").build());
      http.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      var steps = new RevocationSteps();
      set(steps, "driver", mock(DriverControlClient.class));
      set(steps, "revocationEndpoint", URI.create("https://guard.example/revoke"));
      steps.prepareMutation(kind, fault);

      var calls = mockingDetails(proxies.constructed().getFirst()).getInvocations();
      var tokenRules = calls.stream().filter(call ->
          call.getMethod().getName().equals("setTigerProxyRegexManipulation")
              && call.getArgument(2).equals("(^|&)token=[^&]*")).toList();
      assertEquals(kind.equals("unknown") ? 1 : 0, tokenRules.size());
      if (kind.equals("unknown")) {
        assertEquals("$.body", tokenRules.getFirst().getArgument(1));
        assertTrue(((String) tokenRules.getFirst().getArgument(3))
            .matches("\\$1token=[a-f0-9-]{72}"));
      }
      assertEquals(fault.equals("valid") ? 0 : 1, calls.size() - tokenRules.size());
    }
  }

  private static void set(RevocationSteps steps, String name, Object value) throws Exception {
    var field = RevocationSteps.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(steps, value);
  }
}
