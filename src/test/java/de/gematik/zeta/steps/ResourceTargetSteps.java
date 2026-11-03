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

import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.assertj.core.api.SoftAssertions;

/** Verifies isolated target mutations across all original SDK resource requests and retries. */
public class ResourceTargetSteps {
  /** Correlates every resource request with its response after the feature's recorder window. */
  @Dann("alle SDK-Ressourcenaufrufe an {tigerResolvedString} mit allein abweichendem htu "
      + "{tigerResolvedString}, Kontrolltoken {tigerResolvedString} und JWKS "
      + "{tigerResolvedString} werden mit 403 abgelehnt")
  public void verifyHistory(String target, String wrongTarget, String control, String jwks)
      throws Exception {
    var messages = RecordedMessages.snapshot();
    var requests = new ArrayList<Observation>();
    for (var request : messages) {
      if (!"GET".equals(optional(request, "$.method"))
          || !URI.create(target).getRawPath().equals(optional(request, "$.path"))) {
        continue;
      }
      var authorization = optional(request, "$.header.[~'Authorization']");
      require(AslResourceSteps.isDpopAuthorization(authorization), "Missing resource DPoP token");
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(response != null, "Missing response to an original SDK resource request");
      requests.add(new Observation(authorization.substring(5),
          optional(request, "$.header.[~'DPoP']"),
          Integer.parseInt(optional(response, "$.responseCode")),
          request.getFacet(RbelMessageTimingFacet.class).orElseThrow()
              .getTransmissionTime().toInstant()));
    }
    verify(requests, target, wrongTarget, control, jwks);
  }

  record Observation(String access, String proof, int status, Instant sent) {}

  static void verify(List<Observation> requests, String target, String wrongTarget,
      String control, String jwks) throws Exception {
    require(!target.equals(wrongTarget), "Target mutation must differ from the requested URL");
    require(!requests.isEmpty(), "No original SDK resource requests observed");
    new SignatureVerificationSteps().verifyJwtSignatureFromKid(control, jwks);
    var original = SignedJWT.parse(control).getJWTClaimsSet();
    var ids = new HashSet<String>();
    var statuses = new SoftAssertions();
    int index = 0;
    for (var request : requests) {
      index++;
      new SignatureVerificationSteps().verifyJwtSignatureFromKid(request.access(), jwks);
      var token = SignedJWT.parse(request.access()).getJWTClaimsSet();
      // A legitimate SDK refresh may rotate tokens; route acceptance conditions must stay equal.
      for (var claim : List.of("iss", "sub", "client_id", "sid", "aud", "scope", "acr")) {
        require(original.getClaim(claim) != null
            && original.getClaim(claim).equals(token.getClaim(claim)),
            "Resource retry changed an independent acceptance condition: " + claim);
      }
      AslResourceSteps.verifyProof(request.proof(), request.access(), original.getSubject(),
          wrongTarget, "GET", request.sent());
      var proof = SignedJWT.parse(request.proof());
      require(!proof.getHeader().getJWK().isPrivate(), "Proof exposes private key material");
      require(ids.add(proof.getJWTClaimsSet().getJWTID()), "SDK retried a replayed proof");
      statuses.assertThat(request.status()).as("SDK resource request %s HTTP status", index)
          .isEqualTo(403);
    }
    statuses.assertAll();
  }

  private static String optional(RbelElement message, String path) {
    var values = message.findRbelPathMembers(path);
    return values.size() == 1 ? values.getFirst().getRawStringContent() : null;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
