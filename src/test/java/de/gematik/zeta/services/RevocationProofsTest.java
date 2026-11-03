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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies cryptography and fixture binding independently of the live authorization server. */
class RevocationProofsTest {
  private static final URI TOKEN = URI.create("https://as.example/token");
  private static final URI REVOKE = URI.create("https://as.example/revoke");
  private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
  private ECKey client;
  private ECKey dpop;
  private String original;
  private String access;
  private RevocationProofs proofs;

  @BeforeEach
  void setup() throws Exception {
    client = new ECKeyGenerator(Curve.P_256).generate();
    dpop = new ECKeyGenerator(Curve.P_256).generate();
    original = sign(new JWTClaimsSet.Builder().issuer("client-1").subject("client-1").audience(TOKEN.toString())
        .claim("client_statement", Map.of("posture_type", "software", "sub", "client-1", "attestation_timestamp", 1,
            "posture", Map.of("attestation_challenge", "old", "product_id", "test-product"))).build());
    access = sign(new JWTClaimsSet.Builder().claim("cnf", Map.of("jkt", dpop.computeThumbprint().toString())).build());
    proofs = new RevocationProofs(client, dpop, original, access, TOKEN);
  }

  @Test
  void assertionHasFreshIdentityAndNoAttestationOrPrivateKey() throws Exception {
    var jwt = SignedJWT.parse(proofs.assertion("valid", NOW));
    assertTrue(jwt.verify(new ECDSAVerifier(client.toECPublicKey())));
    assertEquals("JWT", jwt.getHeader().getType().toString());
    assertEquals(JWSAlgorithm.ES256, jwt.getHeader().getAlgorithm());
    assertFalse(jwt.getHeader().getJWK().isPrivate());
    var claims = jwt.getJWTClaimsSet();
    assertEquals("client-1", claims.getIssuer());
    assertEquals("client-1", claims.getSubject());
    assertEquals(TOKEN.toString(), claims.getAudience().getFirst());
    assertEquals(NOW, claims.getIssueTime().toInstant());
    assertEquals(NOW.plusSeconds(60), claims.getExpirationTime().toInstant());
    assertNull(claims.getClaim("client_statement"));
    assertNotEquals(claims.getJWTID(), SignedJWT.parse(proofs.assertion("valid", NOW)).getJWTClaimsSet().getJWTID());
  }

  @Test
  void wrongSignatureKeepsTheRegisteredPublicKeyAndFailsVerification() throws Exception {
    var jwt = SignedJWT.parse(proofs.assertion("wrong_signature", NOW));
    assertEquals(client.computeThumbprint(), jwt.getHeader().getJWK().computeThumbprint());
    assertFalse(jwt.verify(new ECDSAVerifier(client.toECPublicKey())));
    assertEquals("client-1", jwt.getJWTClaimsSet().getSubject());
  }

  @Test
  void expiredAssertionRemainsCorrectlySignedButOutsideClockSkew() throws Exception {
    var jwt = SignedJWT.parse(proofs.assertion("expired", NOW));
    assertTrue(jwt.verify(new ECDSAVerifier(client.toECPublicKey())));
    assertEquals(NOW.minusSeconds(300), jwt.getJWTClaimsSet().getExpirationTime().toInstant());
  }

  @Test
  void wrongClientChangesOnlyIdentityBinding() throws Exception {
    var jwt = SignedJWT.parse(proofs.assertion("wrong_client", NOW));
    assertTrue(jwt.verify(new ECDSAVerifier(client.toECPublicKey())));
    assertNotEquals("client-1", jwt.getJWTClaimsSet().getSubject());
    assertEquals(jwt.getJWTClaimsSet().getIssuer(), jwt.getJWTClaimsSet().getSubject());
  }

  @Test
  void dpopBindsFreshProofToRevocationWithoutNonce() throws Exception {
    var jwt = SignedJWT.parse(proofs.dpop("POST", REVOKE, null, NOW));
    assertTrue(jwt.verify(new ECDSAVerifier(dpop.toECPublicKey())));
    assertFalse(jwt.getHeader().getJWK().isPrivate());
    assertEquals("dpop+jwt", jwt.getHeader().getType().toString());
    assertEquals("POST", jwt.getJWTClaimsSet().getStringClaim("htm"));
    assertEquals(REVOKE.toString(), jwt.getJWTClaimsSet().getStringClaim("htu"));
    assertNull(jwt.getJWTClaimsSet().getClaim("nonce"));
    assertNull(jwt.getJWTClaimsSet().getClaim("ath"));
    var next = SignedJWT.parse(proofs.dpop("POST", TOKEN, "nonce", NOW));
    assertNotEquals(jwt.getJWTClaimsSet().getJWTID(), next.getJWTClaimsSet().getJWTID());
    assertEquals("nonce", next.getJWTClaimsSet().getStringClaim("nonce"));
  }

  @Test
  void refreshRenewsNonceBoundSoftwareChallengeWithoutMutatingTemplate() throws Exception {
    var nonceBytes = new byte[] {1, 2, 3, 4};
    var nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
    var jwt = SignedJWT.parse(proofs.refreshAssertion(nonce, NOW));
    assertTrue(jwt.verify(new ECDSAVerifier(client.toECPublicKey())));
    var statement = jwt.getJWTClaimsSet().getJSONObjectClaim("client_statement");
    assertEquals(NOW.getEpochSecond(), ((Number) statement.get("attestation_timestamp")).longValue());
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(client.computeThumbprint().decode());
    var expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(nonceBytes));
    var posture = (Map<?, ?>) statement.get("posture");
    assertEquals(expected, posture.get("attestation_challenge"));
    assertEquals("test-product", posture.get("product_id"));
    var originalPosture = (Map<?, ?>) SignedJWT.parse(original).getJWTClaimsSet().getJSONObjectClaim("client_statement").get("posture");
    assertEquals("old", originalPosture.get("attestation_challenge"));
  }

  @Test
  void resourceProofCarriesHashOfExactTokenAndFreshGetBinding() throws Exception {
    var jwt = SignedJWT.parse(proofs.resourceProof(REVOKE, access, NOW));
    assertTrue(jwt.verify(new ECDSAVerifier(dpop.toECPublicKey())));
    var hash = MessageDigest.getInstance("SHA-256").digest(access.getBytes(StandardCharsets.US_ASCII));
    assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(hash), jwt.getJWTClaimsSet().getStringClaim("ath"));
    assertEquals("GET", jwt.getJWTClaimsSet().getStringClaim("htm"));
    assertEquals(REVOKE.toString(), jwt.getJWTClaimsSet().getStringClaim("htu"));
    assertNull(jwt.getJWTClaimsSet().getClaim("nonce"));
  }

  @Test
  void protectedPostBindsTheSameKeyToMethodNonceAndExactToken() throws Exception {
    var post = SignedJWT.parse(proofs.resourceProof("POST", REVOKE, access, "fresh-nonce", NOW));
    var claims = post.getJWTClaimsSet();
    assertTrue(post.verify(new ECDSAVerifier(dpop.toECPublicKey())));
    assertEquals("POST", claims.getStringClaim("htm"));
    assertEquals(REVOKE.toString(), claims.getStringClaim("htu"));
    assertEquals("fresh-nonce", claims.getStringClaim("nonce"));
    var get = SignedJWT.parse(proofs.resourceProof(REVOKE, access, NOW)).getJWTClaimsSet();
    assertEquals(get.getStringClaim("ath"), claims.getStringClaim("ath"));
    var changed = SignedJWT.parse(proofs.resourceProof("POST", REVOKE, "other-token", "fresh-nonce", NOW)).getJWTClaimsSet();
    assertNotEquals(changed.getStringClaim("ath"), claims.getStringClaim("ath"));
    assertNotEquals(get.getJWTID(), claims.getJWTID());
  }

  @Test
  void refusesUnrelatedClientKey() throws Exception {
    var other = new ECKeyGenerator(Curve.P_256).generate();
    assertThrows(AssertionError.class, () -> new RevocationProofs(other, dpop, original, access, TOKEN));
  }

  @Test
  void refusesUnrelatedDpopKey() throws Exception {
    var other = new ECKeyGenerator(Curve.P_256).generate();
    assertThrows(AssertionError.class, () -> new RevocationProofs(client, other, original, access, TOKEN));
  }

  @Test
  void refusesWrongAuthorizationServer() {
    assertThrows(AssertionError.class, () -> new RevocationProofs(client, dpop, original, access, REVOKE));
  }

  @Test
  void refusesMismatchedPrivateKeyEvenWithCorrectPublicKey() throws Exception {
    var other = new ECKeyGenerator(Curve.P_256).generate();
    var mismatch = new ECKey.Builder(Curve.P_256, dpop.toECPublicKey()).privateKey(other.toECPrivateKey()).build();
    assertThrows(AssertionError.class, () -> new RevocationProofs(client, mismatch, original, access, TOKEN));
  }

  @Test
  void importsTheActualPkcs8AndX509StorageFormats() throws Exception {
    var loaded = RevocationProofs.loadKey(pem("PUBLIC KEY", client.toECPublicKey().getEncoded()),
        pem("PRIVATE KEY", client.toECPrivateKey().getEncoded()));
    assertEquals(client.computeThumbprint(), loaded.computeThumbprint());
    assertEquals(client.toECPrivateKey().getS(), loaded.toECPrivateKey().getS());
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://as.example/revoke?q=1", "https://as.example/revoke#fragment"})
  void rejectsAmbiguousDpopUrls(String url) {
    assertThrows(AssertionError.class, () -> proofs.dpop("POST", URI.create(url), null, NOW));
  }

  private String sign(JWTClaimsSet claims) throws Exception {
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).jwk(client.toPublicJWK()).build(), claims);
    jwt.sign(new ECDSASigner(client));
    return jwt.serialize();
  }

  private String pem(String label, byte[] value) {
    return "-----BEGIN " + label + "-----\n" + Base64.getEncoder().encodeToString(value) + "\n-----END " + label + "-----";
  }
}
