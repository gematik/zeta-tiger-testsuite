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

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Correlates marked SDK responses and verifies PKCE and redirect bindings. */
public class ParContextSteps {

  /** Correlates the asynchronous API call without counting its duplicate ingress observations. */
  @Dann("warte auf die erfolgreiche PKCE-Testdriver-Antwort am Pfad {tigerResolvedString}"
      + " für Markierung {tigerResolvedString}")
  public void awaitMarkedFlow(String path, String marker) throws InterruptedException {
    require(marker.matches("[A-Z][0-9]{9}"), "Expected this flow's synthetic identity marker");
    var markedPath = path + "?cc_pkce_flow=" + marker;
    BoundedObservation.await(
        RecordedMessages::snapshot,
        messages -> completedMarkedResponse(messages, markedPath),
        Duration.ofSeconds(90), Duration.ofMillis(100),
        "Missing successful response for the marked PKCE testdriver call");
  }

  static boolean completedMarkedResponse(List<RbelElement> messages, String path) {
    var requests = requestsAtPath(messages, path);
    return !requests.isEmpty() && allResponsesSuccessful(messages, requests, 200);
  }

  private static List<RbelElement> requestsAtPath(List<RbelElement> messages, String path) {
    return messages.stream()
        .filter(m -> m.findRbelPathMembers("$.method").size() == 1)
        .filter(m -> m.findRbelPathMembers("$.path").stream()
            .anyMatch(p -> path.equals(p.getRawStringContent())))
        .toList();
  }

  private static boolean allResponsesSuccessful(
      List<RbelElement> messages, List<RbelElement> requests, int status) {
    boolean complete = true;
    for (var request : requests) {
      var response = new TimingGlue().findResponseForRequest(messages, request);
      if (response == null) {
        complete = false;
      } else {
        var codes = response.findRbelPathMembers("$.responseCode");
        require(codes.size() == 1 && Integer.toString(status).equals(
            codes.getFirst().getRawStringContent()), "PKCE flow response has unexpected status");
      }
    }
    return complete;
  }

  /**
   * Checks the S256 challenge against the verifier and compares both redirect URIs.
   *
   * @param par original authorization parameters
   * @param token corresponding code redemption parameters
   * @throws Exception if SHA-256 is unavailable
   * @throws AssertionError if parameters are missing or their binding is invalid
   */
  static void verifyPkce(Map<String, String> par, Map<String, String> token) throws Exception {
    require(
        "code".equals(required(par, "response_type")), "Expected PAR authorization-code request");
    require("S256".equals(required(par, "code_challenge_method")), "PAR does not use S256");
    var verifier = required(token, "code_verifier");
    require(verifier.matches("[A-Za-z0-9._~-]{43,128}"), "Malformed actual PKCE verifier");
    var challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    require(
        challenge.equals(required(par, "code_challenge")),
        "PAR challenge is not bound to its actual redemption verifier");
    require(
        required(par, "redirect_uri").equals(required(token, "redirect_uri")),
        "PAR and redemption redirect URIs differ");
  }

  private static String required(Map<String, String> values, String key) {
    require(values != null, "Missing PKCE protocol observation for " + key);
    var value = values.get(key);
    require(value != null && !value.isBlank(), "Missing context field: " + key);
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
