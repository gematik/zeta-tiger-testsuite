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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Independent proof generation using keys provisioned by the software testdriver. */
public final class RevocationProofs {
  private final ECKey clientKey;
  private final ECKey dpopKey;
  private final JWTClaimsSet originalAssertion;
  private final URI tokenEndpoint;

  /**
   * Binds test keys to a successful client's signed assertion and access-token confirmation.
   *
   * @param clientKey client instance key pair
   * @param dpopKey DPoP key pair
   * @param assertion successful original client assertion
   * @param accessToken associated access token
   * @param tokenEndpoint discovered token endpoint
   * @throws JOSEException if a key or signature is invalid
   * @throws ParseException if a captured JWT is malformed
   */
  public RevocationProofs(ECKey clientKey, ECKey dpopKey, String assertion, String accessToken, URI tokenEndpoint)
      throws JOSEException, ParseException {
    this.clientKey = clientKey;
    this.dpopKey = dpopKey;
    this.tokenEndpoint = tokenEndpoint;
    var signed = SignedJWT.parse(assertion);
    originalAssertion = signed.getJWTClaimsSet();
    require(signed.verify(new ECDSAVerifier(clientKey.toECPublicKey())), "Stored client key does not match the successful assertion");
    require(originalAssertion.getIssuer().equals(originalAssertion.getSubject()), "Assertion client identity is inconsistent");
    require(originalAssertion.getAudience().contains(tokenEndpoint.toString()), "Assertion belongs to a different token endpoint");
    var confirmation = SignedJWT.parse(accessToken).getJWTClaimsSet().getJSONObjectClaim("cnf");
    require(confirmation != null && dpopKey.computeThumbprint().toString().equals(confirmation.get("jkt")),
        "Stored DPoP key does not match the issued access token");
    // Also detects mismatched public/private storage entries before any remote negative test.
    sign(new JWTClaimsSet.Builder().jwtID(UUID.randomUUID().toString()).build(), dpopKey, dpopKey, "dpop+jwt", true);
    sign(new JWTClaimsSet.Builder().jwtID(UUID.randomUUID().toString()).build(), clientKey, clientKey, "JWT", true);
  }

  /**
   * Loads an explicitly paired P-256 software test key without logging its encoding.
   *
   * @param publicPem X.509 public-key PEM
   * @param privatePem PKCS#8 private-key PEM
   * @return key pair
   * @throws GeneralSecurityException for invalid key material
   */
  public static ECKey loadKey(String publicPem, String privatePem) throws GeneralSecurityException {
    var factory = KeyFactory.getInstance("EC");
    var publicKey = (ECPublicKey) factory.generatePublic(new X509EncodedKeySpec(decodePem(publicPem)));
    var privateKey = (ECPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(decodePem(privatePem)));
    return new ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey).build();
  }

  private static byte[] decodePem(String value) {
    return Base64.getDecoder().decode(value.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
  }

  /**
   * Creates a fresh RFC 7523 assertion without hardware/software attestation for revocation.
   *
   * @param variant valid, expired, wrong_client, or wrong_signature
   * @param now issuance time
   * @return compact signed JWT
   * @throws JOSEException on signing failure
   */
  public String assertion(String variant, Instant now) throws JOSEException {
    require(variant.equals("valid") || variant.equals("expired") || variant.equals("wrong_client") || variant.equals("wrong_signature"),
        "Unknown assertion variant");
    var expired = variant.equals("expired");
    var claims = baseClaims(expired ? now.minusSeconds(330) : now, expired ? now.minusSeconds(300) : now.plusSeconds(60));
    if (variant.equals("wrong_client")) {
      var foreignClient = UUID.randomUUID().toString();
      claims.issuer(foreignClient).subject(foreignClient);
    }
    var signingKey = variant.equals("wrong_signature") ? new ECKeyGenerator(Curve.P_256).generate() : clientKey;
    return sign(claims.build(), signingKey, clientKey, "JWT", !variant.equals("wrong_signature"));
  }

  private JWTClaimsSet.Builder baseClaims(Instant issued, Instant expires) {
    return new JWTClaimsSet.Builder().issuer(originalAssertion.getIssuer()).subject(originalAssertion.getSubject())
        .audience(tokenEndpoint.toString()).issueTime(Date.from(issued)).expirationTime(Date.from(expires))
        .jwtID(UUID.randomUUID().toString());
  }

  /**
   * Renews the software attestation for a control refresh with the independently acquired nonce.
   *
   * @param nonce base64url nonce returned by the AS
   * @param now issuance time
   * @return fresh compact assertion
   * @throws JOSEException on signing failure
   * @throws ParseException for unsupported original statement data
   * @throws GeneralSecurityException if SHA-256 is unavailable
   */
  public String refreshAssertion(String nonce, Instant now) throws JOSEException, ParseException, GeneralSecurityException {
    var statement = new HashMap<>(originalAssertion.getJSONObjectClaim("client_statement"));
    require("software".equals(statement.get("posture_type")), "Control refresh requires the software-attestation test profile");
    @SuppressWarnings("unchecked")
    var posture = new HashMap<>((Map<String, Object>) statement.get("posture"));
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(clientKey.computeThumbprint().decode());
    posture.put("attestation_challenge", Base64URL.encode(digest.digest(Base64URL.from(nonce).decode())).toString());
    if (posture.containsKey("nonce")) {
      posture.put("nonce", nonce);
    }
    statement.put("posture", posture);
    statement.put("attestation_timestamp", now.getEpochSecond());
    return sign(baseClaims(now, now.plusSeconds(60)).claim("client_statement", statement).build(), clientKey, clientKey, "JWT", true);
  }

  /**
   * Creates a fresh proof bound to the actual HTTP method and external URL.
   *
   * @param method HTTP method
   * @param endpoint actual external endpoint
   * @param nonce optional nonce; null for revocation
   * @param now issuance time
   * @return compact proof
   * @throws JOSEException on signing failure
   */
  public String dpop(String method, URI endpoint, String nonce, Instant now) throws JOSEException {
    require(endpoint.getRawQuery() == null && endpoint.getRawFragment() == null, "DPoP endpoint must omit query and fragment");
    var claims = new JWTClaimsSet.Builder().jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
        .claim("htm", method).claim("htu", endpoint.toString());
    if (nonce != null) {
      claims.claim("nonce", nonce);
    }
    return sign(claims.build(), dpopKey, dpopKey, "dpop+jwt", true);
  }

  /**
   * Binds a GET proof to the exact access token used on the protected resource.
   *
   * @param endpoint external protected resource URL
   * @param accessToken compact access token
   * @param now issuance time
   * @return compact proof containing the access-token hash
   * @throws Exception if proof creation fails
   */
  public String resourceProof(URI endpoint, String accessToken, Instant now) throws Exception {
    return resourceProof("GET", endpoint, accessToken, null, now);
  }

  /**
   * Binds a protected operation to the exact token, HTTP method and freshly obtained AS nonce.
   *
   * @param method actual protected HTTP method
   * @param endpoint actual protected URL
   * @param accessToken original compact access token
   * @param nonce AS nonce, or null if the endpoint does not require one
   * @param now issuance time
   * @return freshly signed proof containing the access-token hash
   * @throws Exception if proof creation fails
   */
  public String resourceProof(String method, URI endpoint, String accessToken, String nonce, Instant now) throws Exception {
    var claims = SignedJWT.parse(dpop(method, endpoint, nonce, now)).getJWTClaimsSet();
    var hash = Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII)));
    return sign(new JWTClaimsSet.Builder(claims).claim("ath", hash.toString()).build(), dpopKey, dpopKey, "dpop+jwt", true);
  }

  /**
   * Returns the registered client identity for the form parameter.
   *
   * @return client ID
   */
  public String clientId() {
    return originalAssertion.getSubject();
  }

  private static String sign(JWTClaimsSet claims, ECKey signingKey, ECKey headerKey, String type, boolean expectedValid)
      throws JOSEException {
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(type))
        .jwk(headerKey.toPublicJWK()).build(), claims);
    jwt.sign(new ECDSASigner(signingKey));
    require(jwt.verify(new ECDSAVerifier(headerKey.toECPublicKey())) == expectedValid, "Unexpected local proof signature integrity");
    return jwt.serialize();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
