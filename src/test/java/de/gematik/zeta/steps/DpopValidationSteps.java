/*-
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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;

/**
 * DPoP-specific Cucumber step definitions for DPoP (Demonstration of Proof-of-Possession)
 * validation.
 */
@Slf4j
public class DpopValidationSteps {

  /**
   * Calculates the JKT (SHA-256 thumbprint) from a DPoP JWT's public key.
   *
   * <p>The JKT is calculated according to RFC 7638 (JWK Thumbprint).
   *
   * @param dpopJwt the DPoP JWT containing the public key in the jwk header
   * @param varName the variable name to store the calculated JKT
   */
  @Und("berechne JKT aus DPoP JWT {tigerResolvedString} und speichere in Variable {tigerResolvedString}")
  @And("calculate JKT from DPoP JWT {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void calculateJktFromDpopJwt(String dpopJwt, String varName) {
    SignedJWT signedJwt;
    try {
      signedJwt = SignedJWT.parse(dpopJwt);
    } catch (ParseException e) {
      throw new AssertionError("Failed to parse DPoP JWT: " + e.getMessage(), e);
    }
    JWK jwk = signedJwt.getHeader().getJWK();
    assertThat(jwk).as("DPoP JWT must contain jwk in header").isNotNull();

    // Calculate JKT (SHA-256 thumbprint) according to RFC 7638
    String jkt;
    try {
      jkt = jwk.computeThumbprint("SHA-256").toString();
    } catch (Exception e) {
      throw new AssertionError("Failed to calculate JKT from DPoP JWT: " + e.getMessage(), e);
    }
    TigerGlobalConfiguration.putValue(varName, jkt, ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Calculated JKT from DPoP JWT public key: {}", jkt);
  }


  /**
   * Verifies the signature of a DPoP JWT using the public key from the jwk header.
   *
   * <p>According to RFC 9449, the JWT signature must verify with the public key contained in the
   * jwk JOSE Header Parameter to demonstrate proof of possession. For gematik requirements, only
   * ES256 (ECDSA with P-256 and SHA-256) is supported.
   *
   * @param dpopJwt the DPoP JWT to verify
   */
  @Und("verifiziere Signatur von DPoP JWT {tigerResolvedString}")
  @And("verify signature of DPoP JWT {tigerResolvedString}")
  public void verifyDpopSignature(String dpopJwt) {
    SignedJWT signedJwt;
    try {
      signedJwt = SignedJWT.parse(dpopJwt);
    } catch (ParseException e) {
      throw new AssertionError("Failed to parse DPoP JWT: " + e.getMessage(), e);
    }
    JWK jwk = signedJwt.getHeader().getJWK();
    assertThat(jwk).as("DPoP JWT must contain jwk in header").isNotNull();

    // Gematik requirement: Only ECC (ES256) is allowed
    assertThat(jwk)
        .as("DPoP JWT must use Elliptic Curve key (EC) for gematik requirements")
        .isInstanceOf(ECKey.class);

    JWSVerifier verifier;
    boolean valid;
    try {
      verifier = new ECDSAVerifier((ECKey) jwk);
      valid = signedJwt.verify(verifier);
    } catch (Exception e) {
      throw new AssertionError("Failed to verify DPoP JWT signature: " + e.getMessage(), e);
    }

    assertThat(valid)
        .as("DPoP JWT signature must verify with public key from jwk header (RFC 9449)")
        .isTrue();

    log.info("DPoP JWT signature verified successfully");
  }

  /**
   * Validates that the jwk in the DPoP JWT header does not contain private key components.
   *
   * <p>According to RFC 9449, the jwk JOSE Header Parameter must not contain a private key.
   * This step checks for common private key parameters (d, p, q, dp, dq, qi for RSA/EC).
   *
   * @param dpopHeader the decoded DPoP JWT header as JSON string
   */
  @Und("prüfe dass jwk in {tigerResolvedString} keine privaten Key-Teile enthält")
  @And("check that jwk in {tigerResolvedString} does not contain private key parts")
  public void validateJwkNoPrivateKey(String dpopHeader) {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode header;
    try {
      header = mapper.readTree(dpopHeader);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse DPoP header JSON: " + e.getMessage());
    }

    assertThat(header.has("jwk")).as("DPoP header must contain jwk").isTrue();
    JsonNode jwk = header.get("jwk");

    // Check for private key parameters (RFC 7517, RFC 7518)
    String[] privateKeyParams = {"d", "p", "q", "dp", "dq", "qi"};
    for (String param : privateKeyParams) {
      assertThat(jwk.has(param))
          .as("jwk must not contain private key parameter '" + param + "' (RFC 9449)")
          .isFalse();
    }

    log.info("jwk validation successful: no private key parameters found");
  }

  /**
   * Calculates the SHA-256 hash of an input string and stores it as base64url-encoded value.
   *
   * <p>This is used to calculate the ath (access token hash) claim for DPoP proofs according to
   * RFC 9449.
   *
   * @param input   the string to hash (typically an access token)
   * @param varName the variable name to store the hash
   */
  @Und("berechne SHA256 Hash von {tigerResolvedString} und speichere in Variable {tigerResolvedString}")
  @And("calculate SHA256 hash of {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void calculateSha256Hash(String input, String varName) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.US_ASCII));
      String ath = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

      TigerGlobalConfiguration.putValue(varName, ath, ConfigurationValuePrecedence.TEST_CONTEXT);
      log.info("Calculated SHA-256 hash (ath): {}", ath);
    } catch (Exception e) {
      throw new AssertionError("Failed to calculate SHA-256 hash: " + e.getMessage());
    }
  }
}
