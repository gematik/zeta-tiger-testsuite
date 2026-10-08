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

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.zeta.model.CertificateMaterial;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cucumber step definitions and helpers for ES256 JWT signature verification.
 */
@Slf4j
public class SignatureVerificationSteps {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final java.util.regex.Pattern UNQUOTED_JSON_FIELD_NAME =
      java.util.regex.Pattern.compile("([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_-]*)\\s*:");

  /**
   * Verifies the ES256 signature of a JWT using embedded key material from the JOSE header.
   *
   * <p>If the header contains {@code jwk}, the EC public key is extracted from that JWK. Otherwise,
   * the first {@code x5c} certificate is parsed and its EC public key is used.</p>
   *
   * @param jwt compact serialized JWT
   */
  @Und("verifiziere die ES256 Signatur des JWT {tigerResolvedString}")
  @And("verify the ES256 signature of the JWT {tigerResolvedString}")
  public void verifyJwtSignature(String jwt) {
    var signedJwt = parseSignedJwt(jwt);
    var jwk = signedJwt.getHeader().getJWK();
    if (jwk != null) {
      var keyLabel = keyLabel(signedJwt, "JWK from JOSE Header");
      try {
        verifyJwtSignature(signedJwt, extractEcPublicKeyFromJwk(jwk), keyLabel);
      } catch (JOSEException e) {
        throw new AssertionError("Failed to extract EC public key for " + keyLabel + ": "
            + e.getMessage(), e);
      }
      return;
    }

    verifyJwtSignature(
        signedJwt,
        extractEcPublicKeyFromX5c(signedJwt.getHeader().getX509CertChain()),
        keyLabel(signedJwt, "X5C from JOSE Header"));
  }

  /**
   * Verifies a parsed JWT using one resolved EC public key.
   *
   * @param signedJwt parsed JWT
   * @param publicKey EC public key used for verification
   * @param keyLabel  human-readable key identifier or source label
   */
  private void verifyJwtSignature(SignedJWT signedJwt, ECPublicKey publicKey, String keyLabel) {
    assertEs256(signedJwt);
    boolean valid;
    try {
      valid = verifyWithNimbusOrBc(signedJwt, publicKey);
    } catch (GeneralSecurityException | JOSEException e) {
      throw new AssertionError("Failed to verify JWT signature using " + keyLabel + ": "
          + e.getMessage(), e);
    }

    assertThat(valid)
        .as("JWT signature must verify with public key " + keyLabel)
        .isTrue();

    log.info("JWT signature verified successfully using {}", keyLabel);
  }

  /**
   * Verifies the ES256 signature of a JWT using the EC key referenced by {@code kid}.
   *
   * @param jwt      compact serialized JWT with a {@code kid} header
   * @param keyStore JWKS JSON containing the public key for the JWT {@code kid}
   */
  @Und("verifiziere die ES256 Signatur des JWT {tigerResolvedString} mit KeyStore {tigerResolvedString}")
  @And("verify the ES256 signature of the JWT {tigerResolvedString} with keystore {tigerResolvedString}")
  public void verifyJwtSignatureFromKid(String jwt, String keyStore) {
    var signedJwt = parseSignedJwt(jwt);
    assertEs256(signedJwt);

    var kid = signedJwt.getHeader().getKeyID();
    assertThat(kid)
        .as("JWT must contain kid in header")
        .isNotBlank();

    verifyJwtSignature(signedJwt, findEcPublicKeyByKid(keyStore, kid), kid);
  }

  /**
   * Resolves the preferred label for verification logging and assertions.
   *
   * @param signedJwt parsed JWT whose {@code kid} header is preferred when present
   * @param fallback  fallback label used when the JWT has no {@code kid}
   * @return {@code kid} header value or fallback label
   */
  private String keyLabel(SignedJWT signedJwt, String fallback) {
    var kid = signedJwt.getHeader().getKeyID();
    if (kid == null || kid.isBlank()) {
      return fallback;
    }
    return kid;
  }

  /**
   * Verifies the compact JWS cryptographically using embedded public key material.
   *
   * <p>This method deliberately ignores the semantic JOSE algorithm value, because malformed JWT
   * variant checks need to verify whether the byte-level signature still matches the signing input.</p>
   *
   * @param jwt compact JWT/JWS to verify
   * @return true if the signature verifies cryptographically, otherwise false
   */
  public boolean hasCryptographicallyValidEmbeddedEs256Signature(String jwt) {
    var parts = requireCompactJwtParts(jwt);
    var rawHeader = decodeBase64UrlHeader(parts[0]);
    var header = parseJoseHeaderToleratingUnquotedFieldNames(rawHeader);

    return hasCryptographicallyValidEs256Signature(
        parts, () -> extractEcPublicKeyFromRawJoseHeader(header));
  }

  /**
   * Verifies the compact JWS cryptographically using the public key referenced by {@code kid}.
   *
   * <p>This method deliberately ignores the semantic JOSE algorithm value, because malformed JWT
   * variant checks need to verify whether the byte-level signature still matches the signing input. It is used for access and refresh
   * tokens that reference their verification key through {@code kid} instead of embedding {@code jwk} or {@code x5c}.</p>
   *
   * @param jwt      compact JWT/JWS to verify
   * @param keyStore JWKS JSON containing the public key for the JWT {@code kid}
   * @return true if the signature verifies cryptographically, otherwise false
   */
  public boolean hasCryptographicallyValidEs256SignatureFromKid(String jwt, String keyStore) {
    var parts = requireCompactJwtParts(jwt);
    var rawHeader = decodeBase64UrlHeader(parts[0]);
    var header = parseJoseHeaderToleratingUnquotedFieldNames(rawHeader);
    var kid = header.path("kid").asString();
    if (kid.isBlank()) {
      return false;
    }

    return hasCryptographicallyValidEs256Signature(
        parts, () -> findEcPublicKeyByKid(keyStore, kid));
  }

  /**
   * Verifies the compact JWS cryptographically using an already resolved public key.
   *
   * <p>This method deliberately ignores the semantic JOSE algorithm value, because malformed JWT
   * variant checks need to verify whether the byte-level signature still matches the signing input. It is useful for tokens whose JOSE
   * header references a key by {@code kid}, while the test setup already knows the expected PoPP public key.</p>
   *
   * @param jwt       compact JWT/JWS to verify
   * @param publicKey EC public key used for verification
   * @return true if the signature verifies cryptographically, otherwise false
   */
  public boolean hasCryptographicallyValidEs256SignatureWithPublicKey(
      String jwt, ECPublicKey publicKey) {
    assertThat(publicKey)
        .as("JWT verification public key must be present")
        .isNotNull();

    var parts = requireCompactJwtParts(jwt);
    return hasCryptographicallyValidEs256Signature(parts, () -> publicKey);
  }

  /**
   * Verifies the compact JWS cryptographically using the token's embedded {@code x5c} certificate.
   *
   * <p>This method deliberately ignores the semantic JOSE algorithm value, because malformed JWT
   * variant checks need to verify whether the byte-level signature still matches the signing input. It also tolerates intentionally
   * unquoted JOSE header field names when extracting {@code x5c}, so malformed-header Subject Token variants can still prove that the
   * re-signature used the private key belonging to the certificate carried by that exact token.</p>
   *
   * @param jwt compact JWT/JWS to verify
   * @return true if the signature verifies cryptographically, otherwise false
   */
  public boolean hasCryptographicallyValidEs256SignatureWithEmbeddedCertificate(String jwt) {
    var parts = requireCompactJwtParts(jwt);
    var certificate = extractFirstEmbeddedX5cCertificate(parts[0]);
    return hasCryptographicallyValidEs256Signature(
        parts, () -> extractEcPublicKeyFromCertificate(certificate));
  }

  /**
   * Verifies compact JWS signature bytes with a lazily resolved EC public key.
   *
   * @param parts             compact JWT/JWS segments
   * @param publicKeySupplier resolver for the verification public key
   * @return true if the signature verifies cryptographically, otherwise false
   */
  private boolean hasCryptographicallyValidEs256Signature(
      String[] parts, Callable<ECPublicKey> publicKeySupplier) {
    var signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);

    try {
      var publicKey = publicKeySupplier.call();
      var signature = Base64.getUrlDecoder().decode(parts[2]);
      return verifyRawEcdsaSignature(publicKey, signingInput, signature);
    } catch (AssertionError | JOSEException e) {
      return false;
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new AssertionError("Failed to verify JWT cryptographic signature locally: "
          + e.getMessage(), e);
    } catch (Exception e) {
      if (e instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new AssertionError("Failed to resolve JWT verification key locally: "
          + e.getMessage(), e);
    }
  }

  /**
   * Checks that the JOSE header declares ES256.
   *
   * @param signedJwt parsed JWT whose algorithm is checked
   */
  private void assertEs256(SignedJWT signedJwt) {
    assertThat(signedJwt.getHeader().getAlgorithm())
        .as("JWT must use ES256")
        .isEqualTo(JWSAlgorithm.ES256);
  }

  /**
   * Verifies a JWT signature through Nimbus and falls back to Bouncy Castle if Nimbus cannot handle the EC key.
   *
   * @param signedJwt parsed JWT
   * @param publicKey EC public key used for verification
   * @return true if the signature verifies
   * @throws GeneralSecurityException if Bouncy Castle cannot verify the signature
   * @throws JOSEException            if Nimbus cannot verify and the Bouncy Castle fallback is unavailable
   */
  private boolean verifyWithNimbusOrBc(SignedJWT signedJwt, ECPublicKey publicKey)
      throws GeneralSecurityException, JOSEException {
    try {
      JWSVerifier verifier = new ECDSAVerifier(publicKey);
      return signedJwt.verify(verifier);
    } catch (JOSEException nimbusError) {
      try {
        return verifyWithBcEcdsa(signedJwt, publicKey);
      } catch (GeneralSecurityException bcError) {
        nimbusError.addSuppressed(bcError);
        throw nimbusError;
      }
    }
  }

  /**
   * Extracts an EC public key from a JOSE header JWK.
   *
   * @param jwk JOSE header or JWKS key
   * @return extracted EC public key
   * @throws JOSEException if the JWK cannot be converted to an EC public key
   */
  private ECPublicKey extractEcPublicKeyFromJwk(JWK jwk) throws JOSEException {
    assertThat(jwk)
        .as("JWT must use Elliptic Curve key (EC)")
        .isInstanceOf(ECKey.class);

    var ecKey = (ECKey) jwk;
    return ecKey.toECPublicKey();
  }

  /**
   * Extracts an EC public key from the first x5c certificate in a certificate chain.
   *
   * @param certChain certificate chain from an {@code x5c} element
   * @return extracted EC public key
   */
  private ECPublicKey extractEcPublicKeyFromX5c(
      List<com.nimbusds.jose.util.Base64> certChain) {
    assertThat(certChain)
        .as("JWT must contain an x5c certificate chain")
        .isNotNull()
        .isNotEmpty();

    var certificate = CertificateMaterial.parseCertificate(
        certChain.getFirst().toString(), "x5c certificate");
    return requireEcPublicKey(certificate, "x5c certificate");
  }

  /**
   * Extracts an EC public key from a Base64 DER or PEM encoded certificate.
   *
   * @param certificate Base64 DER or PEM encoded X.509 certificate
   * @return extracted EC public key
   */
  private ECPublicKey extractEcPublicKeyFromCertificate(String certificate) {
    var x509Certificate = CertificateMaterial.parseCertificate(certificate, "certificate");
    return requireEcPublicKey(x509Certificate, "provided certificate");
  }

  /**
   * Asserts that one certificate contains an EC public key.
   *
   * @param certificate certificate whose public key is checked
   * @param description human-readable certificate description
   * @return EC public key from the certificate
   */
  private ECPublicKey requireEcPublicKey(X509Certificate certificate, String description) {
    assertThat(certificate.getPublicKey())
        .as(description + " must contain an EC public key")
        .isInstanceOf(ECPublicKey.class);

    return (ECPublicKey) certificate.getPublicKey();
  }

  /**
   * Resolves an EC public key from a JWKS response using a {@code kid}.
   *
   * @param keyStore JWKS JSON containing public keys or certificates
   * @param kid      key identifier to resolve
   * @return extracted EC public key for the requested {@code kid}
   */
  private ECPublicKey findEcPublicKeyByKid(String keyStore, String kid) {
    assertThat(keyStore)
        .as("KeyStore must contain JWKS JSON")
        .isNotBlank();

    try {
      var jwkSet = JWKSet.parse(keyStore);
      for (JWK jwk : jwkSet.getKeys()) {
        if (kid.equals(jwk.getKeyID())) {
          try {
            return extractEcPublicKeyFromJwkOrX5c(jwk);
          } catch (AssertionError | JOSEException e) {
            throw new AssertionError("Failed to resolve EC public key for kid '" + kid + "': "
                + e.getMessage(), e);
          }
        }
      }
    } catch (ParseException e) {
      throw new AssertionError("Failed to parse cert response JSON: " + e.getMessage(), e);
    }
    throw new AssertionError("Key for kid '" + kid + "' must be present in cert response");
  }

  /**
   * Extracts an EC public key from direct JWK coordinates or from the JWK's x5c certificate chain.
   *
   * @param jwk parsed JWKS key
   * @return extracted EC public key
   * @throws JOSEException if direct JWK coordinates cannot be converted to an EC public key
   */
  private ECPublicKey extractEcPublicKeyFromJwkOrX5c(JWK jwk) throws JOSEException {
    if (hasEcPublicCoordinates(jwk)) {
      return extractEcPublicKeyFromJwk(jwk);
    }

    var certChain = jwk.getX509CertChain();
    if (certChain != null && !certChain.isEmpty()) {
      return extractEcPublicKeyFromX5c(certChain);
    }

    throw new AssertionError(
        "JWK must provide EC public key coordinates or x5c certificate chain");
  }

  /**
   * Checks whether a JWK directly contains EC public key coordinates.
   *
   * @param jwk parsed JWK
   * @return true if the JWK is an EC key with {@code x} and {@code y} coordinates
   */
  private boolean hasEcPublicCoordinates(JWK jwk) {
    return jwk instanceof ECKey ecKey && ecKey.getX() != null && ecKey.getY() != null;
  }

  /**
   * Parses a compact serialized JWT into a {@link SignedJWT}.
   *
   * @param jwt compact serialized JWT
   * @return parsed JWT
   */
  private SignedJWT parseSignedJwt(String jwt) {
    try {
      return SignedJWT.parse(jwt);
    } catch (ParseException e) {
      throw new AssertionError("Failed to parse JWT: " + e.getMessage(), e);
    }
  }

  /**
   * Verifies a JWT ECDSA signature using Bouncy Castle.
   *
   * @param signedJwt parsed JWT
   * @param publicKey EC public key used for verification
   * @return true if the signature verifies
   * @throws GeneralSecurityException if the provider cannot verify the signature
   */
  private boolean verifyWithBcEcdsa(SignedJWT signedJwt, ECPublicKey publicKey)
      throws GeneralSecurityException {
    return verifyRawEcdsaSignature(
        publicKey,
        signedJwt.getSigningInput(),
        signedJwt.getSignature().decode());
  }

  /**
   * Verifies raw JWS signing input and JOSE ECDSA signature bytes.
   *
   * @param publicKey     EC public key used for verification
   * @param signingInput  ASCII signing input bytes
   * @param joseSignature JOSE ECDSA signature bytes
   * @return true if the signature verifies
   * @throws GeneralSecurityException if the provider cannot verify the signature
   */
  private boolean verifyRawEcdsaSignature(
      ECPublicKey publicKey, byte[] signingInput, byte[] joseSignature)
      throws GeneralSecurityException {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    var signature = Signature.getInstance("SHA256withECDSA", "BC");
    signature.initVerify(publicKey);
    signature.update(signingInput);
    return signature.verify(jwsEcdsaSignatureToDer(joseSignature));
  }

  /**
   * Converts a JWS ECDSA signature from raw {@code R || S} format into DER encoding.
   *
   * @param jwsSignature raw JWS signature bytes
   * @return DER-encoded ECDSA signature
   */
  private byte[] jwsEcdsaSignatureToDer(byte[] jwsSignature) {
    if (jwsSignature.length % 2 != 0) {
      throw new AssertionError("Invalid JWS ECDSA signature length: " + jwsSignature.length);
    }
    var partLen = jwsSignature.length / 2;
    var bytesPartR = new byte[partLen];
    var bytesPartS = new byte[partLen];
    System.arraycopy(jwsSignature, 0, bytesPartR, 0, partLen);
    System.arraycopy(jwsSignature, partLen, bytesPartS, 0, partLen);
    var r = new BigInteger(1, bytesPartR);
    var s = new BigInteger(1, bytesPartS);
    var v = new ASN1EncodableVector();
    v.add(new ASN1Integer(r));
    v.add(new ASN1Integer(s));
    try {
      return new DERSequence(v).getEncoded();
    } catch (IOException e) {
      throw new AssertionError("Failed to encode ECDSA signature: " + e.getMessage(), e);
    }
  }

  /**
   * Extracts the first {@code x5c} certificate from a compact JWT header segment.
   *
   * @param headerSegment Base64URL encoded JOSE header segment
   * @return Base64 DER encoded certificate from the first {@code x5c} entry
   */
  private String extractFirstEmbeddedX5cCertificate(String headerSegment) {
    var rawHeader = decodeBase64UrlHeader(headerSegment);
    try {
      var x5cNode = parseJoseHeaderToleratingUnquotedFieldNames(rawHeader).get("x5c");
      if (x5cNode != null && x5cNode.isArray() && !x5cNode.isEmpty()) {
        return x5cNode.get(0).asString();
      }
    } catch (AssertionError e) {
      log.debug("Falling back to raw x5c extraction for malformed JOSE header.", e);
    }

    var matcher = java.util.regex.Pattern
        .compile("(?s)(?:\"x5c\"|x5c)\\s*:\\s*\\[\\s*\"([A-Za-z0-9+/=\\r\\n\\t ]+)\"")
        .matcher(rawHeader);
    if (matcher.find()) {
      return matcher.group(1).replaceAll("\\s", "");
    }

    throw new AssertionError("JWT JOSE header must contain an x5c certificate.");
  }

  /**
   * Extracts an EC public key from raw JOSE header material.
   *
   * @param header parsed JOSE header JSON
   * @return extracted EC public key
   * @throws JOSEException if an embedded JWK cannot be converted to an EC public key
   */
  private ECPublicKey extractEcPublicKeyFromRawJoseHeader(JsonNode header)
      throws JOSEException {
    var jwkNode = header.get("jwk");
    if (jwkNode != null && !jwkNode.isNull()) {
      try {
        return extractEcPublicKeyFromJwkOrX5c(JWK.parse(jwkNode.toString()));
      } catch (ParseException e) {
        throw new AssertionError("Failed to parse embedded jwk.", e);
      }
    }

    var x5cNode = header.get("x5c");
    assertThat(x5cNode)
        .as("JWT must embed jwk or x5c for local signature checks")
        .isNotNull();
    assertThat(x5cNode.isArray() && !x5cNode.isEmpty())
        .as("JWT x5c header must contain at least one certificate")
        .isTrue();

    return extractEcPublicKeyFromX5c(
        List.of(new com.nimbusds.jose.util.Base64(x5cNode.get(0).asString())));
  }

  /**
   * Parses raw JOSE header JSON text into a {@link JsonNode}.
   *
   * @param rawJson raw JOSE header JSON text
   * @return parsed JOSE header node
   */
  private JsonNode parseJoseHeader(String rawJson) {
    try {
      return JSON.readTree(rawJson);
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse JOSE header as JSON.", e);
    }
  }

  /**
   * Parses JOSE header JSON and tolerates deliberately unquoted field names used by negative JWT variant tests.
   *
   * @param rawJson raw JOSE header JSON text
   * @return parsed JOSE header node
   */
  private JsonNode parseJoseHeaderToleratingUnquotedFieldNames(String rawJson) {
    try {
      return parseJoseHeader(rawJson);
    } catch (AssertionError strictJsonError) {
      try {
        return JSON.readTree(quoteUnquotedJsonFieldNames(rawJson));
      } catch (JacksonException e) {
        strictJsonError.addSuppressed(e);
        throw strictJsonError;
      }
    }
  }

  /**
   * Quotes JSON object field names while leaving string values unchanged.
   *
   * @param rawJson raw JSON-like object text with unquoted field names
   * @return JSON text whose object field names are quoted
   */
  private String quoteUnquotedJsonFieldNames(String rawJson) {
    return UNQUOTED_JSON_FIELD_NAME.matcher(rawJson).replaceAll("$1\"$2\":");
  }

  /**
   * Splits a compact JWT and validates that it contains exactly three segments.
   *
   * @param jwt compact JWT string
   * @return compact JWT segments
   */
  private String[] requireCompactJwtParts(String jwt) {
    var parts = jwt.split("\\.", -1);
    assertThat(parts)
        .as("JWT must be compact serialized with exactly 3 segments")
        .hasSize(3);
    return parts;
  }

  /**
   * Decodes the Base64URL JOSE header segment into UTF-8 text.
   *
   * @param segment compact JWT header segment
   * @return decoded JOSE header text
   */
  private String decodeBase64UrlHeader(String segment) {
    try {
      return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new AssertionError("Failed to decode JWT header segment.", e);
    }
  }
}
