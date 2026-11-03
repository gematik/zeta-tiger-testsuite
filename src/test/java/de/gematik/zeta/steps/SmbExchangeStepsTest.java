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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.RecordedMessages;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SmbExchangeStepsTest {
  @ParameterizedTest
  @ValueSource(strings = {"none", "client_assertion"})
  void selectedRequestUsesRecordedTimeAfterItsCredentialsHaveExpired(String fault) throws Exception {
    var sent = Instant.ofEpochSecond(1700001000);
    var fixture = new Fixture(sent);
    if ("client_assertion".equals(fault)) {
      fixture.badAssertion();
    }
    assertDoesNotThrow(() -> fixture.verifySelectedRequest(fault, sent));
  }

  @ParameterizedTest
  @ValueSource(strings = {"subject", "assertion", "dpop"})
  void selectedRequestRejectsCredentialsAlreadyExpiredOrStaleWhenSent(String credential)
      throws Exception {
    var sent = Instant.ofEpochSecond(1700001000);
    var fixture = new Fixture(sent);
    switch (credential) {
      case "subject" -> fixture.replaceSubjectClaim("exp", sent.getEpochSecond());
      case "assertion" -> fixture.changeAssertion("assertion_expired");
      case "dpop" -> fixture.changeProofClaim("iat", sent.minusSeconds(61).getEpochSecond());
      default -> throw new AssertionError("Unknown credential");
    }
    assertThrows(AssertionError.class, () -> fixture.verifySelectedRequest("none", sent));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void selectedRequestRequiresRecordedTransmissionTime(boolean selected) {
    var retriever = mock(RbelMessageRetriever.class);
    when(retriever.getCurrentRequest()).thenReturn(selected ? mock(RbelElement.class) : null);
    try (var singleton = mockStatic(RbelMessageRetriever.class)) {
      singleton.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      var error = assertThrows(AssertionError.class,
          () -> new SmbExchangeSteps().verifyIsolatedRequest("none", "unused"));
      assertEquals(selected ? "Missing SDK request transmission time" : "Missing SDK request",
          error.getMessage());
    }
  }

  @Test
  void acceptsExactResourceFragmentAcrossAllRetries() {
    String body = "resource=https%3A%2F%2Fexample.org%2Fapi%2F%23cc-invalid-target&scope=openid+profile";
    SmbExchangeSteps.verifyResourceFragmentTargets(
        List.of(body, body), "https://example.org/api/", "openid profile");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "resource=https%3A%2F%2Fother.example%2F%23cc-invalid-target&scope=openid",
      "resource=https%3A%2F%2Fexample.org%2Fapi%2F&scope=openid",
      "resource=https%3A%2F%2Fexample.org%2Fapi%2F%23other&scope=openid",
      "resource=https%3A%2F%2Fexample.org%2Fapi%2F%23cc-invalid-target&scope=profile",
      "resource=https%3A%2F%2Fexample.org%2Fapi%2F%23cc-invalid-target&scope=openid&audience=other",
      "scope=openid",
      "resource=https%3A%2F%2Fexample.org%2Fapi%2F%23cc-invalid-target",
      "resource=https%3A%2F%2Fexample.org%2Fapi%2F%23cc-invalid-target&scope=openid&scope=profile"
  })
  void rejectsChangedTargetOrScopeEvenInLaterRetry(String altered) {
    String valid = "resource=https%3A%2F%2Fexample.org%2Fapi%2F%23cc-invalid-target&scope=openid";
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyResourceFragmentTargets(
        List.of(valid, altered), "https://example.org/api/", "openid"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/api/", "https://example.org/api/#fragment", "${UNRESOLVED}"})
  void rejectsInvalidIndependentResource(String resource) {
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyResourceFragmentTargets(
        List.of("resource=anything&scope=openid"), resource, "openid"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "bad\"scope", "${UNRESOLVED}"})
  void rejectsInvalidIndependentScope(String scope) {
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyResourceFragmentTargets(
        List.of("resource=anything&scope=openid"), "https://example.org/api/", scope));
  }

  @Test
  void rejectsMissingResourceHistory() {
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyResourceFragmentTargets(
        List.of(), "https://example.org/api/", "openid"));
  }

  private static final Instant NOW = Instant.ofEpochSecond(1800000000);
  private static final String ENDPOINT = "https://as.example/token";

  @AfterEach
  void clearSchemaFailures() {
    SoftAssertionsContext.reset();
  }

  @ParameterizedTest
  @ValueSource(longs = {-120, -1, 0})
  void expiredSubjectRequiresOnlyExpirationAndPreservesIndependentCredentials(long expiration)
      throws Exception {
    var fixture = new Fixture();
    fixture.replaceSubjectClaim("iat", NOW.minusSeconds(180).getEpochSecond());
    fixture.replaceSubjectClaim("exp", NOW.plusSeconds(expiration).getEpochSecond());
    fixture.replaceSubjectClaim("nbf", NOW.minusSeconds(150).getEpochSecond());
    fixture.verify("subject_expired");
    assertThrows(AssertionError.class, () -> fixture.verify("none"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"valid", "missing_exp", "missing_iat", "iat_after_exp",
      "iat_equals_exp", "nbf_equals_exp", "nbf_after_now", "bad_signature", "bad_nonce",
      "bad_client_binding", "bad_dpop_binding", "bad_subject_audience",
      "expired_assertion", "bad_assertion", "bad_dpop_signature", "stale_dpop"})
  void expiredSubjectCannotHideAnotherFaultOrAnUnexpiredSubject(String fault) throws Exception {
    var fixture = new Fixture();
    fixture.replaceSubjectClaim("iat", NOW.minusSeconds(180).getEpochSecond());
    fixture.replaceSubjectClaim("exp", NOW.minusSeconds(120).getEpochSecond());
    switch (fault) {
      case "valid" -> fixture.replaceSubjectClaim("exp", NOW.plusSeconds(60).getEpochSecond());
      case "missing_exp" -> fixture.replaceSubjectClaim("exp", null);
      case "missing_iat" -> fixture.replaceSubjectClaim("iat", null);
      case "iat_after_exp" -> fixture.replaceSubjectClaim("iat", NOW.getEpochSecond());
      case "iat_equals_exp" -> fixture.replaceSubjectClaim("iat", NOW.minusSeconds(120).getEpochSecond());
      case "nbf_equals_exp" -> fixture.replaceSubjectClaim("nbf", NOW.minusSeconds(120).getEpochSecond());
      case "nbf_after_now" -> fixture.replaceSubjectClaim("nbf", NOW.plusSeconds(60).getEpochSecond());
      case "bad_signature" -> fixture.form.put("subject_token",
          unsignedJti(fixture.form.get("subject_token"), "cc-invalid-subject"));
      case "bad_nonce" -> fixture.replaceSubjectClaim("nonce", "cc-unissued-subject-nonce");
      case "bad_client_binding" -> fixture.replaceSubjectClaim("client_key",
          Map.of("jkt", fixture.proof.computeThumbprint().toString()));
      case "bad_dpop_binding" -> fixture.replaceSubjectClaim("dpop_key",
          Map.of("jkt", fixture.client.computeThumbprint().toString()));
      case "bad_subject_audience" -> fixture.replaceSubjectClaim("aud", List.of("https://other.example/token"));
      case "expired_assertion" -> fixture.changeAssertion("assertion_expired");
      case "bad_assertion" -> fixture.badAssertion();
      case "bad_dpop_signature" -> fixture.dpop = unsignedJti(fixture.dpop, "cc-invalid-proof");
      case "stale_dpop" -> fixture.changeProofClaim("iat", NOW.minusSeconds(61).getEpochSecond());
      default -> throw new AssertionError("Unknown counterexample");
    }
    assertThrows(AssertionError.class, () -> fixture.verify("subject_expired"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"access_token", "refresh_token", "zeta_attestation_token", "status", "error", "retry"})
  void expiredSubjectErrorContractRejectsTokensWrongStatusAndFaultyRetries(String defect)
      throws Exception {
    var fixture = new Fixture();
    fixture.replaceSubjectClaim("iat", NOW.minusSeconds(180).getEpochSecond());
    fixture.replaceSubjectClaim("exp", NOW.minusSeconds(120).getEpochSecond());
    var validResponse = "{\"error\":\"invalid_grant\"}";
    var response = switch (defect) {
      case "error" -> "{\"error\":\"invalid_client\"}";
      case "status", "retry" -> validResponse;
      default -> "{\"error\":\"invalid_grant\",\"" + defect + "\":\"unexpected-token\"}";
    };
    var first = fixture.observation(400, validResponse);
    SmbExchangeSteps.verifyRejectedExchanges(List.of(first), "client", fixture.jwks,
        "subject_expired", 400, "invalid_grant", ENDPOINT);
    if ("retry".equals(defect)) {
      fixture.replaceSubjectClaim("exp", NOW.plusSeconds(60).getEpochSecond());
    }
    var second = fixture.observation("status".equals(defect) ? 403 : 400, response);
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyRejectedExchanges(
        List.of(first, second), "client", fixture.jwks, "subject_expired", 400, "invalid_grant", ENDPOINT));
  }

  @Test
  void validGenericUriAndOmittedOptionalScopeRemainAccepted() throws Exception {
    var fixture = new Fixture();
    fixture.form.put("resource", "urn:example:resource");
    fixture.form.put("scope", "");
    fixture.verify(true);
    fixture.form.put("resource", "/relative/resource");
    assertThrows(AssertionError.class, () -> fixture.verify(true));
  }

  @Test
  void resourceFragmentRequiresResourceContractAndExactlyOneInvalidProof() throws Exception {
    var fixture = new Fixture();
    assertThrows(AssertionError.class, () -> fixture.verify("resource_fragment"));
    fixture.form.put("resource", "https://resource.example/pep");
    fixture.verify(true);
    assertThrows(AssertionError.class, () -> fixture.verify("resource_fragment"));
    fixture.form.put("resource", "https://resource.example/pep#cc-invalid-target");
    fixture.verify("resource_fragment");
    assertThrows(AssertionError.class, () -> fixture.verify(true));
    fixture.form.put("audience", "https://resource.example/pep");
    assertThrows(AssertionError.class, () -> fixture.verify("resource_fragment"));
    fixture.form.remove("audience");
    fixture.badAssertion();
    assertThrows(AssertionError.class, () -> fixture.verify("resource_fragment"));
  }

  @Test
  void malformedScopeRequiresExactlyTheDeclaredFaultAndValidCredentials() throws Exception {
    for (var marker : List.of("\"", "\\")) {
      var fixture = new Fixture();
      fixture.form.put("scope", "read write");
      fixture.verify(true);
      assertThrows(AssertionError.class, () -> fixture.verify("invalid_scope"));
      fixture.form.put("scope", "read write" + marker);
      fixture.verify("invalid_scope");
      assertThrows(AssertionError.class, () -> fixture.verify(true));
      fixture.badAssertion();
      assertThrows(AssertionError.class, () -> fixture.verify("invalid_scope"));
    }
    var doubled = new Fixture();
    doubled.form.put("scope", "read\"\"");
    assertThrows(AssertionError.class, () -> doubled.verify("invalid_scope"));
  }

  @Test
  void emptyRequiredTypeStillRequiresEveryIndependentProofValid() throws Exception {
    var fixture = new Fixture();
    assertThrows(AssertionError.class, () -> fixture.verify("empty_subject_type"));
    fixture.form.put("subject_token_type", "");
    fixture.verify("empty_subject_type");
    assertThrows(AssertionError.class, () -> fixture.verify(true));
    fixture.badAssertion();
    assertThrows(AssertionError.class, () -> fixture.verify("empty_subject_type"));
  }

  @Test
  void errorSchemaIsCheckedEvenWhenCodeAndStatusAreCorrect() throws Exception {
    var fixture = new Fixture();
    fixture.badAssertion();
    var malformed = fixture.observation(401,
        "{\"error\":\"invalid_client\",\"error_description\":42}");
    assertThrows(AssertionError.class, () -> {
      fixture.verifyHistory(List.of(malformed));
      SoftAssertionsContext.assertAll();
    });
  }

  @Test
  void requiresAnIsolatedBadAssertionAndStillValidSmcbAndDpopBindings() throws Exception {
    var fixture = new Fixture();
    fixture.verify(true);
    fixture.badAssertion();
    fixture.verify(false);
    assertThrows(AssertionError.class, () -> fixture.verify(true));
    fixture.form.put("subject_token", corrupt(fixture.form.get("subject_token")));
    assertThrows(AssertionError.class, () -> fixture.verify(false));
  }

  @Test
  void wrongDcrKeyDpopSignatureOrExpiredOtherCredentialCannotPass() throws Exception {
    var fixture = new Fixture();
    fixture.badAssertion();
    fixture.dpop = corrupt(fixture.dpop);
    assertThrows(AssertionError.class, () -> fixture.verify(false));
    var wrongKey = new Fixture();
    wrongKey.jwks = new JWKSet(new ECKeyGenerator(Curve.P_256).generate().toPublicJWK()).toString();
    assertThrows(AssertionError.class, () -> wrongKey.verify(true));
    var expired = new Fixture();
    assertThrows(
        AssertionError.class,
        () ->
            SmbExchangeSteps.verifyProofs(
                expired.body(), expired.dpop, expired.jwks, true, NOW.plusSeconds(600), ENDPOINT));
    var foreign = new Fixture();
    assertThrows(
        AssertionError.class,
        () ->
            SmbExchangeSteps.verifyProofs(
                foreign.body(), foreign.dpop, foreign.jwks, true, NOW, ENDPOINT + "/foreign"));
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.form("client_id=a&client_id=b"));
  }

  @Test
  void validlySignedWrongSubjectBindingsAndNonceCannotPass() throws Exception {
    for (var field : List.of("client_key", "dpop_key")) {
      var fixture = new Fixture();
      fixture.replaceSubjectClaim(
          field,
          Map.of("jkt", new ECKeyGenerator(Curve.P_256).generate().computeThumbprint().toString()));
      assertThrows(AssertionError.class, () -> fixture.verify(true));
    }
    var nonce = new Fixture();
    nonce.replaceSubjectClaim("nonce", "different-fresh-nonce");
    assertThrows(AssertionError.class, () -> nonce.verify(true));
    var audience = new Fixture();
    audience.replaceSubjectClaim("aud", List.of(ENDPOINT + "/foreign"));
    assertThrows(AssertionError.class, () -> audience.verify(true));
  }

  @Test
  void isolatedSignatureCasesRejectIntactSignaturesAndIndependentFailures() throws Exception {
    for (var fault : List.of("dpop_signature", "subject_signature")) {
      var fixture = new Fixture();
      assertThrows(AssertionError.class, () -> fixture.verify(fault));
      if ("dpop_signature".equals(fault)) {
        fixture.dpop = unsignedJti(fixture.dpop, "cc-invalid-dpop");
      } else {
        var original = fixture.form.get("subject_token");
        var invalid = corrupt(original);
        assertEquals(original.substring(0, original.lastIndexOf('.')),
            invalid.substring(0, invalid.lastIndexOf('.')));
        fixture.form.put("subject_token", invalid);
      }
      fixture.verify(fault);
      assertThrows(AssertionError.class, () -> fixture.verify(true));
      fixture.badAssertion();
      assertThrows(AssertionError.class, () -> fixture.verify(fault));
    }
  }

  @Test
  void eachSignedBindingMutationRequiresTheOtherBindingAndAllSignaturesValid() throws Exception {
    for (var field : List.of("client_key", "dpop_key")) {
      var fixture = new Fixture();
      assertThrows(AssertionError.class, () -> fixture.verify(field));
      var other = new ECKeyGenerator(Curve.P_256).generate().computeThumbprint().toString();
      fixture.replaceSubjectClaim(field, Map.of("jkt", other));
      fixture.verify(field);
      assertThrows(AssertionError.class, () -> fixture.verify(true));
      fixture.replaceSubjectClaim(
          "client_key".equals(field) ? "dpop_key" : "client_key", Map.of("jkt", other));
      assertThrows(AssertionError.class, () -> fixture.verify(field));
      var signature = new Fixture();
      signature.replaceSubjectClaim(field, Map.of("jkt", other));
      signature.form.put("subject_token", corrupt(signature.form.get("subject_token")));
      assertThrows(AssertionError.class, () -> signature.verify(field));
    }
  }

  @Test
  void correctlySignedMarkedJwtAndUnknownFaultCannotPretendToBeSignatureFailure() throws Exception {
    var fixture = new Fixture();
    fixture.replaceSubjectClaim("jti", "cc-invalid-subject");
    assertThrows(AssertionError.class, () -> fixture.verify("subject_signature"));
    assertThrows(AssertionError.class, () -> fixture.verify("ignored-unknown-fault"));
  }

  @Test
  void earlierRetryCannotIssueTokensOrUseWrongStatusEvenWhenLastResponseIsCorrect() throws Exception {
    var fixture = new Fixture();
    fixture.badAssertion();
    var correct = fixture.observation(401, "{\"error\":\"invalid_client\"}");
    fixture.verifyHistory(List.of(correct, correct));
    var earlier = fixture.observation(200, "{\"error\":\"invalid_request\",\"access_token\":null}");
    var error = assertThrows(AssertionError.class,
        () -> fixture.verifyHistory(List.of(earlier, correct)));
    assertTrue(error.getMessage().contains("Exchange 1 HTTP status"));
    assertTrue(error.getMessage().contains("Exchange 1 OAuth error"));
    assertTrue(error.getMessage().contains("Exchange 1 must omit access_token"));
  }

  @Test
  void missingObservationForeignClientOrAdditionalInvalidCredentialCannotProveRejection()
      throws Exception {
    var fixture = new Fixture();
    fixture.badAssertion();
    assertThrows(AssertionError.class, () -> fixture.verifyHistory(List.of()));
    var correct = fixture.observation(401, "{\"error\":\"invalid_client\"}");
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyRejectedExchanges(
        List.of(correct), "foreign", fixture.jwks, "client_assertion", 401,
        "invalid_client", ENDPOINT));
    fixture.dpop = corrupt(fixture.dpop);
    var invalid = fixture.observation(401, "{\"error\":\"invalid_client\"}");
    assertThrows(AssertionError.class, () -> fixture.verifyHistory(List.of(invalid, correct)));
  }

  static Stream<Arguments> independentDpopHeaderAndJtiCases() {
    return Stream.of("none", "client_assertion", "subject_signature", "dpop_signature",
        "client_key", "dpop_key").flatMap(fault ->
        Stream.of("missing_typ", "wrong_typ", "missing_jti", "empty_jti", "private_jwk")
            .map(counterexample -> Arguments.of(fault, counterexample)));
  }

  @ParameterizedTest(name = "{0}: reject independent {1}")
  @MethodSource("independentDpopHeaderAndJtiCases")
  void isolatedFaultCannotHideIndependentDpopHeaderOrJtiFault(
      String fault, String counterexample) throws Exception {
    var fixture = new Fixture();
    switch (fault) {
      case "client_assertion" -> fixture.badAssertion();
      case "subject_signature" -> fixture.form.put("subject_token",
          unsignedJti(fixture.form.get("subject_token"), "cc-invalid-subject"));
      case "dpop_signature" -> fixture.dpop = unsignedJti(fixture.dpop, "cc-invalid-dpop");
      case "client_key", "dpop_key" -> fixture.replaceSubjectClaim(fault,
          Map.of("jkt", new ECKeyGenerator(Curve.P_256).generate().computeThumbprint().toString()));
      default -> {
        // The unmodified fixture is the positive control for the none case.
      }
    }
    fixture.verify(fault);
    var original = SignedJWT.parse(fixture.dpop);
    if ("private_jwk".equals(counterexample)) {
      // Nimbus already rejects private JWKs during parsing. Build the actual raw wire form
      // instead of failing prematurely in JWSHeader.Builder.jwk().
      var rawHeader = new LinkedHashMap<>(original.getHeader().toJSONObject());
      rawHeader.put("jwk", fixture.proof.toJSONObject());
      var signingInput = Base64URL.encode(JSONObjectUtils.toJSONString(rawHeader)) + "."
          + Base64URL.encode(original.getJWTClaimsSet().toString());
      var signature = new ECDSASigner(fixture.proof).sign(original.getHeader(),
          signingInput.getBytes(StandardCharsets.US_ASCII));
      fixture.dpop = signingInput + "." + signature;
      if ("dpop_signature".equals(fault)) {
        fixture.dpop = corrupt(fixture.dpop);
      }
      assertThrows(java.text.ParseException.class, () -> fixture.verify(fault));
      return;
    }
    var header = new JWSHeader.Builder(original.getHeader());
    var claims = new JWTClaimsSet.Builder(original.getJWTClaimsSet());
    switch (counterexample) {
      case "missing_typ" -> header.type(null);
      case "wrong_typ" -> header.type(new JOSEObjectType("JWT"));
      case "missing_jti" -> claims.jwtID(null);
      case "empty_jti" -> claims.jwtID("");
      default -> throw new AssertionError("Unknown counterexample");
    }
    var changed = new SignedJWT(header.build(), claims.build());
    changed.sign(new ECDSASigner(fixture.proof));
    // Preserve the declared bad signature; every other case remains correctly signed.
    fixture.dpop = "dpop_signature".equals(fault)
        ? corrupt(changed.serialize()) : changed.serialize();
    assertThrows(AssertionError.class, () -> fixture.verify(fault));
  }

  private static String unsignedJti(String token, String jti) throws Exception {
    var original = SignedJWT.parse(token);
    var claims = new JWTClaimsSet.Builder(original.getJWTClaimsSet()).jwtID(jti).build();
    return original.getHeader().toBase64URL()
        + "." + Base64URL.encode(claims.toString()) + "." + original.getSignature();
  }

  static Stream<Arguments> independentCredentialCases() {
    return Stream.of("none", "client_assertion", "subject_signature", "dpop_signature",
        "client_key", "dpop_key", "empty_subject_type", "invalid_scope", "resource_fragment")
        .flatMap(fault -> Stream.of("assertion_missing_typ", "assertion_wrong_typ",
            "assertion_missing_jti", "assertion_empty_jti", "assertion_missing_jwk",
            "assertion_foreign_jwk", "assertion_wrong_alg", "subject_missing_typ",
            "subject_wrong_typ", "subject_missing_jti", "subject_empty_jti",
            "subject_missing_iss", "subject_wrong_iss", "subject_missing_sub",
            "subject_empty_sub", "subject_missing_iat")
            .map(counterexample -> Arguments.of(fault, counterexample)));
  }

  @ParameterizedTest(name = "{0}: reject independent {1}")
  @MethodSource("independentCredentialCases")
  void declaredFaultCannotHideAnInvalidIndependentSubjectOrAssertion(
      String fault, String counterexample) throws Exception {
    var f = new Fixture();
    switch (fault) {
      case "client_assertion" -> f.badAssertion();
      case "subject_signature" -> f.form.put("subject_token",
          unsignedJti(f.form.get("subject_token"), "cc-invalid-subject"));
      case "dpop_signature" -> f.dpop = unsignedJti(f.dpop, "cc-invalid-dpop");
      case "client_key", "dpop_key" -> f.replaceSubjectClaim(fault,
          Map.of("jkt", new ECKeyGenerator(Curve.P_256).generate().computeThumbprint().toString()));
      case "empty_subject_type" -> f.form.put("subject_token_type", "");
      case "invalid_scope" -> f.form.put("scope", "valid\"");
      case "resource_fragment" -> f.form.put("resource",
          "https://resource.example/pep#cc-invalid-target");
      default -> {
        // The unchanged fixture is the independent positive control.
      }
    }
    f.verify(fault);
    boolean assertion = counterexample.startsWith("assertion_");
    var parameter = assertion ? "client_assertion" : "subject_token";
    var original = SignedJWT.parse(f.form.get(parameter));
    var header = new LinkedHashMap<>(original.getHeader().toJSONObject());
    var claims = new LinkedHashMap<>(original.getJWTClaimsSet().toJSONObject());
    switch (counterexample.substring(counterexample.indexOf('_') + 1)) {
      case "missing_typ" -> header.remove("typ");
      case "wrong_typ" -> header.put("typ", "dpop+jwt");
      case "missing_jti" -> claims.remove("jti");
      case "empty_jti" -> claims.put("jti", "");
      case "missing_jwk" -> header.remove("jwk");
      case "foreign_jwk" -> header.put("jwk",
          new ECKeyGenerator(Curve.P_256).generate().toPublicJWK().toJSONObject());
      case "wrong_alg" -> header.put("alg", "ES384");
      case "missing_iss" -> claims.remove("iss");
      case "wrong_iss" -> claims.put("iss", "foreign-client");
      case "missing_sub" -> claims.remove("sub");
      case "empty_sub" -> claims.put("sub", "");
      case "missing_iat" -> claims.remove("iat");
      default -> throw new AssertionError("Unknown independent credential counterexample");
    }
    var signingInput = Base64URL.encode(JSONObjectUtils.toJSONString(header)) + "."
        + Base64URL.encode(JSONObjectUtils.toJSONString(claims));
    // Preserve a valid mathematical ES256 signature, including a declared wrong alg header.
    var signature = new ECDSASigner(assertion ? f.client : f.subjectKey)
        .sign(new JWSHeader(JWSAlgorithm.ES256),
            signingInput.getBytes(StandardCharsets.US_ASCII));
    var changed = signingInput + "." + signature;
    if ((assertion && "client_assertion".equals(fault))
        || (!assertion && "subject_signature".equals(fault))) {
      changed = corrupt(changed);
    }
    f.form.put(parameter, changed);
    assertThrows(AssertionError.class, () -> f.verify(fault));
  }

  @Test
  void nonceOriginUsesTheMatchingAsReplyDespiteUnrelatedLaterTraffic() {
    var endpoint = URI.create("https://as.example/nonce");
    var correct = new SmbExchangeSteps.NonceObservation("GET", endpoint, 200, "nonce",
        NOW.minusSeconds(2), NOW.minusSeconds(1));
    var unrelated = new SmbExchangeSteps.NonceObservation("GET", endpoint, 200, "other",
        NOW.minusMillis(500), NOW.minusMillis(100));
    SmbExchangeSteps.verifyNonceIssuedBefore(List.of(correct, unrelated), "nonce", NOW, endpoint);
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "wrong_status", "wrong_method", "wrong_nonce", "foreign_host",
      "wrong_path", "future_request", "future_response", "inverted_times", "missing_request_time",
      "missing_response_time", "missing_body"})
  void nonceEqualityCannotReplaceAnEarlierSuccessfulReplyFromTheActualAs(String fault) {
    var endpoint = URI.create("https://as.example/nonce");
    var target = switch (fault) {
      case "foreign_host" -> URI.create("https://foreign.example/nonce");
      case "wrong_path" -> URI.create("https://as.example/other");
      default -> endpoint;
    };
    var requested = switch (fault) {
      case "future_request" -> NOW.plusSeconds(1);
      case "inverted_times" -> NOW.minusMillis(500);
      case "missing_request_time" -> null;
      default -> NOW.minusSeconds(2);
    };
    var received = switch (fault) {
      case "future_request", "future_response" -> NOW.plusSeconds(2);
      case "missing_response_time" -> null;
      default -> NOW.minusSeconds(1);
    };
    var nonce = switch (fault) {
      case "wrong_nonce" -> "another-nonce";
      case "missing_body" -> null;
      default -> "nonce";
    };
    var observed = new SmbExchangeSteps.NonceObservation(
        "wrong_method".equals(fault) ? "POST" : "GET", target,
        "wrong_status".equals(fault) ? 400 : 200, nonce, requested, received);
    var observations = "absent".equals(fault)
        ? List.<SmbExchangeSteps.NonceObservation>of() : List.of(observed);
    assertThrows(AssertionError.class,
        () -> SmbExchangeSteps.verifyNonceIssuedBefore(observations, "nonce", NOW, endpoint));
  }

  private static String corrupt(String token) {
    var parts = token.split("\\.");
    var bytes = Base64URL.from(parts[2]).decode();
    bytes[0] ^= 1;
    return parts[0] + "." + parts[1] + "." + Base64URL.encode(bytes);
  }

  static Stream<Arguments> signedAssertionCounterexamples() {
    return Stream.of("assertion_typ", "assertion_iss", "assertion_sub", "assertion_aud",
        "assertion_expired").flatMap(fault -> Stream.of("none", "signature", "dcr_key",
            "subject_signature", "dpop_signature", "subject_expired", "subject_issuer",
            "assertion_jti", "other_assertion_claim")
            .map(other -> Arguments.of(fault, other)));
  }

  @ParameterizedTest(name = "{0}: independently check {1}")
  @MethodSource("signedAssertionCounterexamples")
  void signedAssertionMutationRequiresExactlyItsOwnFault(String fault, String other)
      throws Exception {
    var f = new Fixture();
    f.verify(true);
    assertThrows(AssertionError.class, () -> f.verify(fault));
    f.changeAssertion(fault);
    f.verify(fault);
    assertThrows(AssertionError.class, () -> f.verify(true));
    switch (other) {
      case "none" -> {
        // A correctly signed isolated claim change reaches response verification for every retry.
        var rejection = f.observation(401, "{\"error\":\"invalid_client\"}");
        SmbExchangeSteps.verifyRejectedExchanges(List.of(rejection, rejection), "client", f.jwks,
            fault, 401, "invalid_client", ENDPOINT);
        var wrong = f.observation(200, "{\"access_token\":\"unexpected\"}");
        assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyRejectedExchanges(
            List.of(wrong, rejection), "client", f.jwks, fault, 401, "invalid_client", ENDPOINT));
        return;
      }
      case "signature" -> f.form.put("client_assertion", corrupt(f.form.get("client_assertion")));
      case "dcr_key" -> f.jwks = new JWKSet(
          new ECKeyGenerator(Curve.P_256).generate().toPublicJWK()).toString();
      case "subject_signature" -> f.form.put("subject_token", corrupt(f.form.get("subject_token")));
      case "dpop_signature" -> f.dpop = corrupt(f.dpop);
      case "subject_expired" -> f.replaceSubjectClaim("exp", NOW.minusSeconds(1).getEpochSecond());
      case "subject_issuer" -> f.replaceSubjectClaim("iss", "another-client");
      case "assertion_jti" -> f.changeAssertion("assertion_jti");
      case "other_assertion_claim" -> f.changeAssertion(
          "assertion_iss".equals(fault) ? "assertion_sub" : "assertion_iss");
      default -> throw new AssertionError("Unknown counterexample");
    }
    assertThrows(AssertionError.class, () -> f.verify(fault));
  }

  static Stream<Arguments> registrationCounterexamples() {
    return Stream.of("assertion_typ", "assertion_iss", "assertion_sub", "assertion_aud",
        "assertion_expired", "assertion_missing_iss", "assertion_missing_sub",
        "assertion_missing_aud", "assertion_missing_exp", "assertion_missing_jti")
        .flatMap(fault -> Stream.of("none", "missing_initial", "unknown_retry",
            "foreign_key", "private_key", "future_dcr", "reversed_dcr", "wrong_endpoint",
            "wrong_status", "duplicate_dcr", "initial_not_first", "bad_retry_signature",
            "old_client_only").map(other -> Arguments.of(fault, other)));
  }

  @ParameterizedTest(name = "{0}: registration counterexample {1}")
  @MethodSource("registrationCounterexamples")
  void automaticReregistrationRequiresTheSameKeyAndItsOwnPriorDcr(String fault, String other)
      throws Exception {
    var f = new Fixture();
    f.changeAssertion(fault);
    var first = f.observation(401, "{\"error\":\"invalid_client\"}");
    f.reregister("second-client", fault);
    if ("bad_retry_signature".equals(other)) {
      f.form.put("client_assertion", corrupt(f.form.get("client_assertion")));
    }
    var second = new SmbExchangeSteps.ExchangeObservation(f.body(), f.dpop,
        "{\"error\":\"invalid_client\"}", 401, NOW.plusSeconds(1));
    var endpoint = URI.create("https://as.example/register");
    var initial = new SmbExchangeSteps.RegistrationObservation("client", f.jwks, endpoint, 201,
        NOW.minusSeconds(3), NOW.minusSeconds(2));
    var secondKey = switch (other) {
      case "foreign_key" -> new JWKSet(new ECKeyGenerator(Curve.P_256).generate().toPublicJWK())
          .toString();
      case "private_key" -> new JWKSet(f.client).toString(false);
      default -> f.jwks;
    };
    var retry = new SmbExchangeSteps.RegistrationObservation(
        "unknown_retry".equals(other) ? "unrelated-client" : "second-client", secondKey,
        "wrong_endpoint".equals(other) ? URI.create("https://foreign.example/register") : endpoint,
        "wrong_status".equals(other) ? 400 : 201,
        "reversed_dcr".equals(other) ? NOW.plusSeconds(1) : NOW.minusSeconds(1),
        "future_dcr".equals(other) ? NOW.plusSeconds(2) : NOW);
    var registrations = "missing_initial".equals(other) ? List.of(retry)
        : "duplicate_dcr".equals(other) ? List.of(initial, retry, retry) : List.of(initial, retry);
    var exchanges = List.of(first, second);
    if ("none".equals(other)) {
      SmbExchangeSteps.verifyRegisteredExchanges(registrations, exchanges, "client", f.jwks,
          fault, 401, "invalid_client", endpoint, ENDPOINT);
    } else if ("old_client_only".equals(other)) {
      assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyRejectedExchanges(
          exchanges, "client", f.jwks, fault, 401, "invalid_client", ENDPOINT));
    } else {
      assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyRegisteredExchanges(
          registrations, exchanges, "initial_not_first".equals(other) ? "second-client" : "client",
          f.jwks, fault, 401, "invalid_client", endpoint, ENDPOINT));
    }
  }

  static Stream<Arguments> missingAssertionCounterexamples() {
    return Stream.of("iss", "sub", "aud", "exp", "jti")
        .flatMap(claim -> Stream.of("none", "signature", "dcr_key", "subject_signature",
            "dpop_signature", "subject_expired", "subject_issuer", "other_missing", "null", "empty")
            .map(other -> Arguments.of(claim, other)));
  }

  @ParameterizedTest(name = "missing assertion {0}: reject independent {1}")
  @MethodSource("missingAssertionCounterexamples")
  void missingAssertionClaimRequiresActualAbsenceAndOtherwiseValidCredentials(
      String claim, String other) throws Exception {
    var f = new Fixture();
    var fault = "assertion_missing_" + claim;
    f.verify(true);
    assertThrows(AssertionError.class, () -> f.verify(fault));
    f.changeAssertion(fault);
    f.verify(fault);
    assertThrows(AssertionError.class, () -> f.verify(true));
    switch (other) {
      case "none" -> {
        var rejected = f.observation(401, "{\"error\":\"invalid_client\"}");
        SmbExchangeSteps.verifyRejectedExchanges(List.of(rejected, rejected), "client", f.jwks,
            fault, 401, "invalid_client", ENDPOINT);
        var accepted = f.observation(200, "{\"access_token\":\"unexpected\"}");
        assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyRejectedExchanges(
            List.of(accepted, rejected), "client", f.jwks, fault, 401, "invalid_client", ENDPOINT));
        return;
      }
      case "signature" -> f.form.put("client_assertion", corrupt(f.form.get("client_assertion")));
      case "dcr_key" -> f.jwks = new JWKSet(
          new ECKeyGenerator(Curve.P_256).generate().toPublicJWK()).toString();
      case "subject_signature" -> f.form.put("subject_token", corrupt(f.form.get("subject_token")));
      case "dpop_signature" -> f.dpop = corrupt(f.dpop);
      case "subject_expired" -> f.replaceSubjectClaim("exp", NOW.minusSeconds(1).getEpochSecond());
      case "subject_issuer" -> f.replaceSubjectClaim("iss", "another-client");
      case "other_missing" -> f.changeAssertion(
          "assertion_missing_" + ("jti".equals(claim) ? "iss" : "jti"));
      case "null", "empty" -> {
        var original = SignedJWT.parse(f.form.get("client_assertion"));
        var claims = new LinkedHashMap<>(original.getJWTClaimsSet().toJSONObject());
        claims.put(claim, "null".equals(other) ? null : "");
        var input = original.getHeader().toBase64URL() + "."
            + Base64URL.encode(JSONObjectUtils.toJSONString(claims));
        var signature = new ECDSASigner(f.client).sign(original.getHeader(),
            input.getBytes(StandardCharsets.US_ASCII));
        f.form.put("client_assertion", input + "." + signature);
      }
      default -> throw new AssertionError("Unknown missing-claim counterexample");
    }
    assertThrows(AssertionError.class, () -> f.verify(fault));
  }

  static Stream<Arguments> subjectNonceCounterexamples() {
    return Stream.of("subject_nonce_empty", "subject_nonce_unknown")
        .flatMap(fault -> Stream.of("none", "intact", "absent", "null", "wrong_marker",
            "subject_signature", "assertion_signature", "dpop_signature", "client_key",
            "dpop_key", "subject_expired", "subject_issuer", "subject_target", "dpop_nonce")
            .map(other -> Arguments.of(fault, other)));
  }

  @ParameterizedTest(name = "{0}: independent {1}")
  @MethodSource("subjectNonceCounterexamples")
  void subjectNonceExceptionRequiresExactlyItsDeclaredFault(String fault, String other)
      throws Exception {
    var f = new Fixture();
    f.replaceSubjectClaim("nonce", "subject_nonce_empty".equals(fault)
        ? "" : "cc-unissued-subject-nonce");
    switch (other) {
      case "none" -> {
        f.verify(fault);
        assertThrows(AssertionError.class, () -> f.verify("none"));
        return;
      }
      case "intact" -> f.replaceSubjectClaim("nonce", "nonce");
      case "absent" -> f.replaceSubjectClaim("nonce", null);
      case "null" -> {
        var original = SignedJWT.parse(f.form.get("subject_token"));
        var payload = new LinkedHashMap<>(original.getPayload().toJSONObject());
        payload.put("nonce", null);
        var input = original.getHeader().toBase64URL() + "."
            + Base64URL.encode(JSONObjectUtils.toJSONString(payload));
        var signature = new ECDSASigner(f.subjectKey)
            .sign(original.getHeader(), input.getBytes(StandardCharsets.US_ASCII));
        f.form.put("subject_token", input + "." + signature);
      }
      case "wrong_marker" -> f.replaceSubjectClaim("nonce", "another-unissued-value");
      case "subject_signature" -> f.form.put("subject_token", corrupt(f.form.get("subject_token")));
      case "assertion_signature" -> f.badAssertion();
      case "dpop_signature" -> f.dpop = corrupt(f.dpop);
      case "client_key", "dpop_key" -> f.replaceSubjectClaim(other,
          Map.of("jkt", new ECKeyGenerator(Curve.P_256).generate().computeThumbprint().toString()));
      case "subject_expired" -> f.replaceSubjectClaim("exp", NOW.minusSeconds(1).getEpochSecond());
      case "subject_issuer" -> f.replaceSubjectClaim("iss", "foreign-client");
      case "subject_target" -> f.replaceSubjectClaim("aud", List.of(ENDPOINT + "/foreign"));
      case "dpop_nonce" -> {
        var original = SignedJWT.parse(f.dpop);
        var changed = new SignedJWT(original.getHeader(),
            new JWTClaimsSet.Builder(original.getJWTClaimsSet()).claim("nonce", "").build());
        changed.sign(new ECDSASigner(f.proof));
        f.dpop = changed.serialize();
      }
      default -> throw new AssertionError("Unknown counterexample");
    }
    assertThrows(AssertionError.class, () -> f.verify(fault));
  }

  static Stream<Arguments> subjectNonceContextCases() {
    return Stream.of("subject_nonce_empty", "subject_nonce_unknown")
        .flatMap(fault -> Stream.of("none", "absent", "wrong_status", "wrong_method",
            "wrong_nonce", "foreign_host", "wrong_path", "future_request", "future_response",
            "inverted_times", "missing_request_time", "missing_response_time")
            .map(other -> Arguments.of(fault, other)));
  }

  @ParameterizedTest(name = "{0}: AS history {1}")
  @MethodSource("subjectNonceContextCases")
  void invalidSubjectNonceStillNeedsGenuineEarlierDpopNonce(String fault, String other) {
    var endpoint = URI.create("https://as.example/nonce");
    var target = switch (other) {
      case "foreign_host" -> URI.create("https://foreign.example/nonce");
      case "wrong_path" -> URI.create("https://as.example/other");
      default -> endpoint;
    };
    var requested = switch (other) {
      case "future_request" -> NOW.plusSeconds(1);
      case "inverted_times" -> NOW.minusMillis(500);
      case "missing_request_time" -> null;
      default -> NOW.minusSeconds(2);
    };
    var received = switch (other) {
      case "future_request", "future_response" -> NOW.plusSeconds(2);
      case "missing_response_time" -> null;
      default -> NOW.minusSeconds(1);
    };
    var observation = new SmbExchangeSteps.NonceObservation(
        "wrong_method".equals(other) ? "POST" : "GET", target,
        "wrong_status".equals(other) ? 400 : 200,
        "wrong_nonce".equals(other) ? "foreign-nonce" : "nonce", requested, received);
    var observations = "absent".equals(other)
        ? List.<SmbExchangeSteps.NonceObservation>of() : List.of(observation);
    var subject = "subject_nonce_empty".equals(fault) ? "" : "cc-unissued-subject-nonce";
    if ("none".equals(other)) {
      SmbExchangeSteps.verifyNonceContext(observations, subject, "nonce", fault, NOW, endpoint);
    } else {
      assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyNonceContext(
          observations, subject, "nonce", fault, NOW, endpoint));
    }
  }

  @Test
  void supposedlyUnknownNonceCannotBeOneActuallyIssuedByTheAs() {
    var endpoint = URI.create("https://as.example/nonce");
    var observations = Stream.of("nonce", "cc-unissued-subject-nonce")
        .map(value -> new SmbExchangeSteps.NonceObservation("GET", endpoint, 200, value,
            NOW.minusSeconds(2), NOW.minusSeconds(1))).toList();
    assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyNonceContext(observations,
        "cc-unissued-subject-nonce", "nonce", "subject_nonce_unknown", NOW, endpoint));
  }

  static Stream<Arguments> subjectNonceRetryCases() {
    return Stream.of("subject_nonce_empty", "subject_nonce_unknown")
        .flatMap(fault -> Stream.of("none", "token", "wrong_status", "wrong_error")
            .map(other -> Arguments.of(fault, other)));
  }

  @ParameterizedTest(name = "{0}: earlier retry {1}")
  @MethodSource("subjectNonceRetryCases")
  void everyNonceRejectionNeedsTheErrorContractAndNoTokens(String fault, String other)
      throws Exception {
    var f = new Fixture();
    f.replaceSubjectClaim("nonce", "subject_nonce_empty".equals(fault)
        ? "" : "cc-unissued-subject-nonce");
    var correct = f.observation(400, "{\"error\":\"invalid_grant\"}");
    var earlier = f.observation("wrong_status".equals(other) ? 403 : 400,
        switch (other) {
          case "token" -> "{\"error\":\"invalid_grant\",\"access_token\":\"unexpected\"}";
          case "wrong_error" -> "{\"error\":\"invalid_token\"}";
          default -> "{\"error\":\"invalid_grant\"}";
        });
    if ("none".equals(other)) {
      SmbExchangeSteps.verifyRejectedExchanges(List.of(earlier, correct), "client", f.jwks,
          fault, 400, "invalid_grant", ENDPOINT);
    } else {
      assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyRejectedExchanges(
          List.of(earlier, correct), "client", f.jwks, fault, 400, "invalid_grant", ENDPOINT));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "intact", "empty", "null", "subject_nonce_absent",
      "subject_nonce_empty", "subject_signature", "assertion_signature", "dpop_signature",
      "client_key", "dpop_key", "subject_expired", "subject_issuer", "subject_target",
      "dpop_target", "dpop_method", "dpop_expired", "dcr_key", "rotated_bound_key",
      "rotated_unbound_key"})
  void missingDpopNonceRequiresEveryOtherCredentialToRemainValid(String other) throws Exception {
    var f = new Fixture();
    f.changeProofClaim("nonce", null);
    switch (other) {
      case "none" -> {
        // The nonce is the only removed claim.
      }
      case "intact" -> f.changeProofClaim("nonce", "fresh-nonce");
      case "empty" -> f.changeProofClaim("nonce", "");
      case "null" -> {
        var original = SignedJWT.parse(f.dpop);
        var payload = new LinkedHashMap<>(original.getPayload().toJSONObject());
        payload.put("nonce", null);
        var input = original.getHeader().toBase64URL() + "."
            + Base64URL.encode(JSONObjectUtils.toJSONString(payload));
        f.dpop = input + "." + new ECDSASigner(f.proof).sign(original.getHeader(),
            input.getBytes(StandardCharsets.US_ASCII));
      }
      case "subject_nonce_absent" -> f.replaceSubjectClaim("nonce", null);
      case "subject_nonce_empty" -> f.replaceSubjectClaim("nonce", "");
      case "subject_signature" -> f.form.put("subject_token", corrupt(f.form.get("subject_token")));
      case "assertion_signature" -> f.badAssertion();
      case "dpop_signature" -> f.dpop = corrupt(f.dpop);
      case "client_key", "dpop_key" -> f.replaceSubjectClaim(other,
          Map.of("jkt", new ECKeyGenerator(Curve.P_256).generate().computeThumbprint().toString()));
      case "subject_expired" -> f.replaceSubjectClaim("exp", NOW.minusSeconds(1).getEpochSecond());
      case "subject_issuer" -> f.replaceSubjectClaim("iss", "foreign-client");
      case "subject_target" -> f.replaceSubjectClaim("aud", List.of(ENDPOINT + "/foreign"));
      case "dpop_target" -> f.changeProofClaim("htu", ENDPOINT + "/foreign");
      case "dpop_method" -> f.changeProofClaim("htm", "GET");
      case "dpop_expired" -> f.changeProofClaim("iat", NOW.minusSeconds(61).getEpochSecond());
      case "dcr_key" -> f.jwks = new JWKSet(
          new ECKeyGenerator(Curve.P_256).generate().toPublicJWK()).toString();
      case "rotated_bound_key", "rotated_unbound_key" -> {
        var key = new ECKeyGenerator(Curve.P_256).generate();
        var original = SignedJWT.parse(f.dpop);
        var rotated = new SignedJWT(new JWSHeader.Builder(original.getHeader())
            .jwk(key.toPublicJWK()).build(), original.getJWTClaimsSet());
        rotated.sign(new ECDSASigner(key));
        f.dpop = rotated.serialize();
        if ("rotated_bound_key".equals(other)) {
          f.replaceSubjectClaim("dpop_key", Map.of("jkt", key.computeThumbprint().toString()));
        }
      }
      default -> throw new AssertionError("Unknown DPoP nonce counterexample");
    }
    if (List.of("none", "rotated_bound_key").contains(other)) {
      f.verify("dpop_nonce_missing");
      assertThrows(AssertionError.class, () -> f.verify("none"));
    } else {
      assertThrows(AssertionError.class, () -> f.verify("dpop_nonce_missing"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "absent", "wrong_status", "wrong_method", "wrong_nonce",
      "foreign_host", "future_response", "inverted_times", "missing_time"})
  void missingDpopNonceStillRequiresAnActualEarlierAsSubjectNonce(String other) {
    var endpoint = URI.create("https://as.example/nonce");
    var observation = new SmbExchangeSteps.NonceObservation(
        "wrong_method".equals(other) ? "POST" : "GET",
        "foreign_host".equals(other) ? URI.create("https://foreign.example/nonce") : endpoint,
        "wrong_status".equals(other) ? 400 : 200,
        "wrong_nonce".equals(other) ? "foreign-nonce" : "fresh-nonce",
        "inverted_times".equals(other) ? NOW : NOW.minusSeconds(2),
        "future_response".equals(other) ? NOW.plusSeconds(1)
            : "missing_time".equals(other) ? null : NOW.minusSeconds(1));
    var observations = "absent".equals(other)
        ? List.<SmbExchangeSteps.NonceObservation>of() : List.of(observation);
    if ("none".equals(other)) {
      SmbExchangeSteps.verifyNonceContext(observations, "fresh-nonce", null,
          "dpop_nonce_missing", NOW, endpoint);
    } else {
      assertThrows(AssertionError.class, () -> SmbExchangeSteps.verifyNonceContext(observations,
          "fresh-nonce", null, "dpop_nonce_missing", NOW, endpoint));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "missing", "duplicate", "empty", "space", "quote",
      "backslash", "unicode", "wrong_status", "wrong_error", "access_token", "refresh_token",
      "zeta_attestation_token", "bad_schema"})
  void everyChallengeIncludingEarlierRetriesMustSupplyItsCompleteContract(String other)
      throws Exception {
    var f = new Fixture();
    f.changeProofClaim("nonce", null);
    var headers = switch (other) {
      case "missing" -> List.<String>of();
      case "duplicate" -> List.of("first", "second");
      case "empty" -> List.of("");
      case "space" -> List.of("white space");
      case "quote" -> List.of("quote\"");
      case "backslash" -> List.of("back\\slash");
      case "unicode" -> List.of("ä");
      default -> List.of("opaque-server-nonce");
    };
    var response = switch (other) {
      case "wrong_error" -> "{\"error\":\"invalid_dpop_proof\"}";
      case "access_token", "refresh_token", "zeta_attestation_token" ->
          "{\"error\":\"use_dpop_nonce\",\"" + other + "\":\"unexpected\"}";
      case "bad_schema" -> "{\"error\":\"use_dpop_nonce\",\"error_description\":42}";
      default -> "{\"error\":\"use_dpop_nonce\"}";
    };
    var earlier = new SmbExchangeSteps.ExchangeObservation(f.body(), f.dpop, response,
        "wrong_status".equals(other) ? 401 : 400, NOW, headers);
    var correct = new SmbExchangeSteps.ExchangeObservation(f.body(), f.dpop,
        "{\"error\":\"use_dpop_nonce\"}", 400, NOW, List.of("next-opaque-nonce"));
    if ("none".equals(other)) {
      SmbExchangeSteps.verifyRejectedExchanges(List.of(earlier, correct), "client", f.jwks,
          "dpop_nonce_missing", 400, "use_dpop_nonce", ENDPOINT);
      SoftAssertionsContext.assertAll();
    } else {
      assertThrows(AssertionError.class, () -> {
        SmbExchangeSteps.verifyRejectedExchanges(List.of(earlier, correct), "client", f.jwks,
            "dpop_nonce_missing", 400, "use_dpop_nonce", ENDPOINT);
        SoftAssertionsContext.assertAll();
      });
    }
  }

  private static final class Fixture {
    private final ECKey client = new ECKeyGenerator(Curve.P_256).generate();
    private final ECKey proof = new ECKeyGenerator(Curve.P_256).generate();
    private final ECKey subjectKey = new ECKeyGenerator(Curve.P_256).generate();
    private final Map<String, String> form = new LinkedHashMap<>();
    private String jwks = new JWKSet(client.toPublicJWK()).toString();
    private String dpop;

    Fixture() throws Exception {
      this(NOW);
    }

    Fixture(Instant issued) throws Exception {
      var name = new X500Name("CN=Independent test SMC-B");
      var cert =
          new JcaX509v3CertificateBuilder(
                  name,
                  BigInteger.ONE,
                  Date.from(issued.minusSeconds(60)),
                  Date.from(issued.plusSeconds(600)),
                  name,
                  subjectKey.toECPublicKey())
              .build(
                  new JcaContentSignerBuilder("SHA256withECDSA")
                      .build(subjectKey.toECPrivateKey()));
      var subject =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256)
                  .type(new JOSEObjectType("JWT"))
                  .x509CertChain(List.of(Base64.encode(cert.getEncoded())))
                  .build(),
              new JWTClaimsSet.Builder()
                  .issuer("client")
                  .subject("test-smcb")
                  .jwtID("original-subject-jti")
                  .issueTime(Date.from(issued))
                  .audience(ENDPOINT)
                  .expirationTime(Date.from(issued.plusSeconds(300)))
                  .claim("nonce", "fresh-nonce")
                  .claim("client_key", Map.of("jkt", client.computeThumbprint().toString()))
                  .claim("dpop_key", Map.of("jkt", proof.computeThumbprint().toString()))
                  .build());
      subject.sign(new ECDSASigner(subjectKey));
      var assertion =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256).jwk(client.toPublicJWK())
                  .type(new JOSEObjectType("JWT")).build(),
              new JWTClaimsSet.Builder()
                  .issuer("client")
                  .subject("client")
                  .audience(ENDPOINT)
                  .jwtID("original-jti")
                  .expirationTime(Date.from(issued.plusSeconds(300)))
                  .claim("client_statement", Map.of("platform", "linux"))
                  .build());
      assertion.sign(new ECDSASigner(client));
      var dpopJwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256).jwk(proof.toPublicJWK())
                  .type(new JOSEObjectType("dpop+jwt")).build(),
              new JWTClaimsSet.Builder()
                  .jwtID("original-proof-jti")
                  .issueTime(Date.from(issued))
                  .claim("htm", "POST")
                  .claim("htu", ENDPOINT)
                  .claim("nonce", "fresh-nonce")
                  .build());
      dpopJwt.sign(new ECDSASigner(proof));
      dpop = dpopJwt.serialize();
      form.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
      form.put("subject_token_type", "urn:ietf:params:oauth:token-type:jwt");
      form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
      form.put("client_id", "client");
      form.put("subject_token", subject.serialize());
      form.put("client_assertion", assertion.serialize());
    }

    void verifySelectedRequest(String fault, Instant sent) throws Exception {
      var body = body();
      var request = message("POST /token HTTP/1.1\r\nHost: as.example\r\n"
          + "Content-Type: application/x-www-form-urlencoded\r\nDPoP: " + dpop
          + "\r\nContent-Length: " + body.length() + "\r\n\r\n" + body, sent);
      var nonce = message("GET /nonce HTTP/1.1\r\nHost: as.example\r\n\r\n", sent.minusSeconds(2));
      var response = message("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
          + "Content-Length: 11\r\n\r\nfresh-nonce", sent.minusSeconds(1));
      var pair = TracingMessagePairFacet.builder().request(nonce).responses(List.of(response)).build();
      nonce.addFacet(pair);
      response.addFacet(pair);
      var retriever = mock(RbelMessageRetriever.class);
      when(retriever.getCurrentRequest()).thenReturn(request);
      when(retriever.findElementInCurrentRequest("$.body"))
          .thenReturn(request.findRbelPathMembers("$.body").getFirst());
      when(retriever.findElementInCurrentRequest("$.header.[~'DPoP']"))
          .thenReturn(request.findRbelPathMembers("$.header.[~'DPoP']").getFirst());
      try (var singleton = mockStatic(RbelMessageRetriever.class);
          var recorded = mockStatic(RecordedMessages.class);
          var config = mockStatic(TigerGlobalConfiguration.class);
          var report = mockStatic(SerenityReportUtils.class)) {
        singleton.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
        recorded.when(RecordedMessages::snapshot).thenReturn(List.of(nonce, response, request));
        config.when(() -> TigerGlobalConfiguration.readStringOptional("zeta_base_url"))
            .thenReturn(Optional.of("as.example"));
        config.when(() -> TigerGlobalConfiguration.readStringOptional("paths.guard.tokenEndpointPath"))
            .thenReturn(Optional.of("/token"));
        config.when(() -> TigerGlobalConfiguration.readStringOptional("paths.guard.nonceEndpointPath"))
            .thenReturn(Optional.of("/nonce"));
        config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        new SmbExchangeSteps().verifyIsolatedRequest(fault, jwks);
      }
    }

    private static RbelElement message(String raw, Instant sent) {
      var message = RbelLogger.build().getRbelConverter().parseMessage(
          raw.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
      message.addOrReplaceFacet(new RbelMessageTimingFacet(sent.atZone(ZoneOffset.UTC)));
      return message;
    }

    void badAssertion() throws Exception {
      form.put("client_assertion", unsignedJti(form.get("client_assertion"), "cc-invalid-assertion"));
    }

    void changeAssertion(String fault) throws Exception {
      var original = SignedJWT.parse(form.get("client_assertion"));
      var header = new JWSHeader.Builder(original.getHeader());
      var claims = new JWTClaimsSet.Builder(original.getJWTClaimsSet());
      switch (fault) {
        case "assertion_typ" -> header.type(new JOSEObjectType("cc-invalid-assertion"));
        case "assertion_iss" -> claims.issuer("cc-invalid-issuer");
        case "assertion_sub" -> claims.subject("cc-invalid-subject");
        case "assertion_aud" -> claims.audience("https://invalid.example/token");
        case "assertion_expired" -> claims.expirationTime(Date.from(Instant.ofEpochSecond(1700000000)));
        case "assertion_jti", "assertion_missing_jti" -> claims.jwtID(null);
        case "assertion_missing_iss" -> claims.issuer(null);
        case "assertion_missing_sub" -> claims.subject(null);
        case "assertion_missing_aud" -> claims.claim("aud", null);
        case "assertion_missing_exp" -> claims.expirationTime(null);
        default -> throw new AssertionError("Unknown assertion fault");
      }
      var changed = new SignedJWT(header.build(), claims.build());
      changed.sign(new ECDSASigner(client));
      form.put("client_assertion", changed.serialize());
    }

    void reregister(String id, String fault) throws Exception {
      var original = SignedJWT.parse(form.get("client_assertion"));
      var changed = new SignedJWT(original.getHeader(),
          new JWTClaimsSet.Builder(original.getJWTClaimsSet()).issuer(id).subject(id).build());
      changed.sign(new ECDSASigner(client));
      form.put("client_id", id);
      form.put("client_assertion", changed.serialize());
      replaceSubjectClaim("iss", id);
      changeAssertion(fault);
    }

    void replaceSubjectClaim(String name, Object value) throws Exception {
      var original = SignedJWT.parse(form.get("subject_token"));
      var replacement =
          new SignedJWT(
              original.getHeader(),
              new JWTClaimsSet.Builder(original.getJWTClaimsSet()).claim(name, value).build());
      replacement.sign(new ECDSASigner(subjectKey));
      form.put("subject_token", replacement.serialize());
    }

    void changeProofClaim(String name, Object value) throws Exception {
      var original = SignedJWT.parse(dpop);
      var replacement = new SignedJWT(original.getHeader(),
          new JWTClaimsSet.Builder(original.getJWTClaimsSet()).claim(name, value).build());
      replacement.sign(new ECDSASigner(proof));
      dpop = replacement.serialize();
    }

    String body() {
      return form.entrySet().stream()
          .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
          .collect(java.util.stream.Collectors.joining("&"));
    }

    void verify(boolean valid) throws Exception {
      assertEquals(
          "client", SmbExchangeSteps.verifyProofs(body(), dpop, jwks, valid, NOW, ENDPOINT));
    }

    void verify(String fault) throws Exception {
      assertEquals(
          "client", SmbExchangeSteps.verifyProofs(body(), dpop, jwks, fault, NOW, ENDPOINT));
    }

    SmbExchangeSteps.ExchangeObservation observation(int status, String response) {
      return new SmbExchangeSteps.ExchangeObservation(body(), dpop, response, status, NOW);
    }

    void verifyHistory(List<SmbExchangeSteps.ExchangeObservation> exchanges) throws Exception {
      SmbExchangeSteps.verifyRejectedExchanges(exchanges, "client", jwks, "client_assertion",
          401, "invalid_client", ENDPOINT);
    }
  }
}
