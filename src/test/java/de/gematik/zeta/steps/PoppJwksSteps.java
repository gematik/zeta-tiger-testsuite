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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.RecordedMessages;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** Prepares signed public PoPP JWKS fixtures and checks their observed download identity and expiry. */
public class PoppJwksSteps {
  private Fixture fixture;
  private String firstResponse;

  /**
   * Re-signs the public mock fixture with its verified existing server key before PEP startup.
   *
   * @param path signed JWKS endpoint used by the exported proxy response condition
   * @param seconds absolute JWT lifetime from fixture creation
   * @param variable variable prefix for the signed body, response condition and expiry
   * @throws Exception if the public fixture cannot be read, verified or signed
   */
  @Gegebensei("bereite ein korrekt signiertes PoPP-JWKS auf Pfad {tigerResolvedString} mit {int} Sekunden JWT-Gültigkeit in Variable {tigerResolvedString} vor")
  public void prepareShortLivedJwks(String path, int seconds, String variable) throws Exception {
    require(path.matches("/[A-Za-z0-9_./-]+"), "Invalid PoPP JWKS path");
    var configMap = config("testdata.ccPoppJwks.configMap");
    var entry = config("testdata.ccPoppJwks.signedJwksKey");
    require(configMap.matches("[a-z0-9-]{1,128}") && entry.matches("[A-Za-z0-9_.-]{1,128}"),
        "Invalid public PoPP fixture configuration");
    var result = ZetaDeploymentConfiguration.getServiceInstance().executeKubectlCommand(false,
        "-n", ZetaDeploymentConfiguration.getNamespace(), "get", "configmap", configMap, "-o", "json");
    require(result.exitCode() == 0, "Cannot read the public PoPP JWKS fixture");
    var original = new ObjectMapper().readTree(result.stdout()).path("data").path(entry).asString();
    var pem = Files.readString(Path.of(config("paths.popp.ecKeyFile")));
    prepareFixture(original.trim(), pem, seconds, Instant.now(), path, variable);
  }

  void prepareFixture(String original, String pem, int seconds, Instant now, String path,
      String variable) throws Exception {
    require(variable != null && variable.matches("[A-Za-z][A-Za-z0-9_-]*"),
        "Invalid PoPP JWKS fixture variable");
    require(path.matches("/[A-Za-z0-9_./-]+"), "Invalid PoPP JWKS path");
    fixture = shortLivedFixture(original, pem, seconds, now);
    firstResponse = null;
    TigerGlobalConfiguration.putValue(variable + ".body", fixture.compact(), ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue(variable + ".condition", responseCondition(path), ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue(variable + ".expires", String.valueOf(fixture.expires().getEpochSecond()),
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /** Remembers the initial complete response after the feature checks its body and positive SDK control. */
  @Dann("merke die aktuelle JWKS-Antwort nach vollständigem Empfang vor Ablauf der signierten Gültigkeit")
  public void rememberInitialResponseBeforeExpiry() {
    verifyCompleteResponseBeforeExpiry();
    firstResponse = RbelMessageRetriever.getInstance().getCurrentResponse().getUuid();
  }

  /** Checks a distinct complete response against the initial JWKS's absolute signed expiry. */
  @Dann("die aktuelle JWKS-Antwort ist eine neue vollständig vor Ablauf der gemerkten signierten Gültigkeit empfangene Antwort")
  public void verifyDistinctResponseBeforeExpiry() {
    require(firstResponse != null, "Observe the initial signed JWKS response first");
    var response = RbelMessageRetriever.getInstance().getCurrentResponse();
    require(response != null && !firstResponse.equals(response.getUuid()), "Expected a new JWKS download");
    verifyCompleteResponseBeforeExpiry();
  }

  private void verifyCompleteResponseBeforeExpiry() {
    require(fixture != null, "Prepare the signed PoPP JWKS fixture first");
    var response = RbelMessageRetriever.getInstance().getCurrentResponse();
    require(response != null, "Missing PoPP JWKS response");
    var received = TimingGlue.responseReceivedAt(RecordedMessages.snapshot(), response);
    require(received.isBefore(fixture.expires()), "Signed PoPP JWKS was not completely received before its JWT expiry");
  }

  static Fixture shortLivedFixture(String original, String pem, int seconds, Instant now) throws Exception {
    require(seconds > 0 && seconds <= 600, "PoPP JWKS fixture lifetime must be within 1..600 seconds");
    var jwt = SignedJWT.parse(original);
    require(JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
        && jwt.getHeader().getKeyID() != null && !jwt.getHeader().getKeyID().isBlank(),
        "Expected the PoPP server's ES256 signing identity");
    var keys = JWKSet.parse(jwt.getPayload().toString()).getKeys();
    require(!keys.isEmpty() && keys.stream().noneMatch(key -> key.isPrivate()), "Expected public PoPP token keys");
    var claims = jwt.getJWTClaimsSet();
    require(claims.getIssuer() != null && claims.getExpirationTime() != null
        && claims.getExpirationTime().toInstant().isAfter(now), "Invalid original signed PoPP JWKS validity");
    var steps = new JwtSteps();
    steps.verifyJwtSignatureWithPublicKeyFromPrivateKey(original, pem);
    var issued = now.truncatedTo(ChronoUnit.SECONDS);
    var expires = issued.plusSeconds(seconds);
    var replacement = new SignedJWT(jwt.getHeader(), new JWTClaimsSet.Builder(claims)
        .issueTime(Date.from(issued)).expirationTime(Date.from(expires)).build());
    replacement.sign(new ECDSASigner(steps.loadEcPrivateKey(pem)));
    steps.verifyJwtSignatureWithPublicKeyFromPrivateKey(replacement.serialize(), pem);
    return new Fixture(replacement.serialize(), expires);
  }

  record Fixture(String compact, Instant expires) {}

  static String responseCondition(String path) {
    return "isResponse && $.responseCode == 200 && request.method == 'GET' && request.path =~ '.*"
        + Pattern.quote(path) + "'";
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .filter(value -> !value.isBlank() && !value.contains("${"))
        .orElseThrow(() -> new AssertionError("Missing public PoPP fixture configuration: " + key));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
