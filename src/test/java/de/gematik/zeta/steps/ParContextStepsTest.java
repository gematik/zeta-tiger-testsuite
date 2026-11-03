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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ParContextStepsTest {
  private static final Map<String, String> PAR = Map.of(
      "response_type", "code",
      "code_challenge_method", "S256",
      "code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      "redirect_uri", "https://app.example/oidc");
  private static final Map<String, String> TOKEN = Map.of(
      "code_verifier", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
      "redirect_uri", "https://app.example/oidc");

  @Test
  void onlyResponseForTheExactMarkedApiCallCanCompleteTheNewFlow() {
    var path = "/resource?cc_pkce_flow=Y123456789";
    var messages = exchange("/resource", 200, true);
    messages.addAll(exchange("/resource?cc_pkce_flow=X123456789", 200, true));
    assertFalse(ParContextSteps.completedMarkedResponse(messages, path));
    messages.addAll(exchange(path, 200, true));
    assertTrue(ParContextSteps.completedMarkedResponse(messages, path));
  }

  @Test
  void duplicateIngressObservationsOfTheSameMarkedCallRemainValid() {
    var path = "/resource?cc_pkce_flow=Y123456789";
    var messages = exchange(path, 200, true);
    messages.addAll(exchange(path, 200, true));
    assertTrue(ParContextSteps.completedMarkedResponse(messages, path));
  }

  @Test
  void markedRequestWithoutItsOwnResponseCannotPass() {
    var path = "/resource?cc_pkce_flow=Y123456789";
    assertFalse(ParContextSteps.completedMarkedResponse(exchange(path, 200, false), path));
  }

  @Test
  void failedMarkedResponseCannotBeHiddenBySuccessfulIngressCopy() {
    var path = "/resource?cc_pkce_flow=Y123456789";
    var messages = exchange(path, 200, true);
    messages.addAll(exchange(path, 500, true));
    assertThrows(AssertionError.class,
        () -> ParContextSteps.completedMarkedResponse(messages, path));
  }

  private static List<RbelElement> exchange(String path, int status, boolean paired) {
    var converter = RbelLogger.build().getRbelConverter();
    var request = converter.parseMessage(
        ("GET " + path + " HTTP/1.1\r\nHost: example.test\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    var response = RbelLogger.build().getRbelConverter().parseMessage(
        ("HTTP/1.1 " + status + " Example\r\nContent-Length: 0\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    if (paired) {
      var pair = TracingMessagePairFacet.builder().request(request)
          .responses(List.of(response)).build();
      request.addFacet(pair);
      response.addFacet(pair);
    }
    return new ArrayList<>(List.of(request, response));
  }

  @Test
  void acceptsRfc7636ReferenceVector() throws Exception {
    ParContextSteps.verifyPkce(PAR, TOKEN);
  }

  @ParameterizedTest
  @CsvSource({
      "par, response_type, token",
      "par, code_challenge_method, plain",
      "par, code_challenge, unrelated",
      "par, redirect_uri, https://foreign.example/",
      "token, redirect_uri, https://foreign.example/",
      "token, code_verifier, foreign-verifier-aaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "token, code_verifier, malformed"
  })
  void rejectsIncorrectProtocolOrBinding(String role, String field, String value) {
    var par = new HashMap<>(PAR);
    var token = new HashMap<>(TOKEN);
    (role.equals("par") ? par : token).put(field, value);
    assertThrows(AssertionError.class, () -> ParContextSteps.verifyPkce(par, token));
  }

  @ParameterizedTest
  @CsvSource({
      "par, response_type",
      "par, code_challenge_method",
      "par, code_challenge",
      "par, redirect_uri",
      "token, code_verifier",
      "token, redirect_uri"
  })
  void rejectsMissingParameters(String role, String field) {
    var par = new HashMap<>(PAR);
    var token = new HashMap<>(TOKEN);
    (role.equals("par") ? par : token).remove(field);
    assertThrows(AssertionError.class, () -> ParContextSteps.verifyPkce(par, token));
  }
}
