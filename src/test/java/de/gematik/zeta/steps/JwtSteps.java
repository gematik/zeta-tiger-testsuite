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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.JwtVariant;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cucumber step definitions for JWT (JSON Web Token) manipulation and variant generation.
 */
@Slf4j
public class JwtSteps {

  private final SignatureVerificationSteps signatureVerificationSteps =
      new SignatureVerificationSteps();

  /**
   * Extracts the compact Access Token from a raw {@code Authorization} header and stores it in a Tiger variable.
   *
   * <p>RBEL can expose a decoded {@code DpopToken} child for well-formed Authorization values. For
   * malformed compact-serialization variants, the raw header is the authoritative protocol evidence.</p>
   *
   * @param authorizationHeader raw Authorization header value
   * @param varName             Tiger configuration variable receiving the compact token
   */
  @Und("extrahiere DPoP Access Token aus Authorization Header {tigerResolvedString} und speichere in Variable {tigerResolvedString}")
  @And("extract DPoP access token from Authorization header {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void extractDpopAccessTokenFromAuthorizationHeader(
      String authorizationHeader, String varName) {
    var token = extractCompactTokenFromAuthorizationHeader(authorizationHeader);
    TigerGlobalConfiguration.putValue(varName, token, ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Extracted DPoP Access Token from Authorization header into variable '{}'", varName);
  }

  /**
   * Verifies that a generated JWT variant has the expected local signature integrity before it is sent to the Guard.
   *
   * <p>This prevents false positives where a failed re-signature would accidentally still lead to
   * the expected remote error response. Variants that are supposed to keep a valid outer signature are verified locally, while the
   * dedicated {@code invalid_signature} variant must fail local signature verification. Pure parsing-malformation variants are
   * intentionally ignored here.</p>
   *
   * @param variant generated JWT variant
   * @param jwt     generated compact JWT/JWS variant
   */
  @Und("prüfe die JWT-Variante {jwtVariant} in {tigerResolvedString} hat lokal die erwartete Signaturintegrität")
  @And("check JWT variant {jwtVariant} in {tigerResolvedString} has the expected local signature integrity")
  public void verifyJwtVariantHasExpectedLocalSignatureIntegrity(JwtVariant variant, String jwt) {
    verifyJwtVariantLocalSignatureIntegrity(
        variant,
        jwt,
        signatureVerificationSteps::hasCryptographicallyValidEmbeddedEs256Signature,
        "");
  }

  /**
   * Verifies that a generated JWT variant has the expected local signature integrity using the public key referenced by the token's
   * {@code kid} header.
   *
   * <p>This is intended for Access Token and Refresh Token checks, where the verification key is
   * published via JWKS instead of embedded into the JOSE header.</p>
   *
   * @param variant  generated JWT variant
   * @param jwt      generated compact JWT/JWS variant
   * @param keyStore JWKS JSON containing the public key for the token {@code kid}
   */
  @Und("prüfe die JWT-Variante {jwtVariant} in {tigerResolvedString} hat lokal mit KeyStore {tigerResolvedString} die erwartete Signaturintegritaet")
  @And("check JWT variant {jwtVariant} in {tigerResolvedString} has the expected local signature integrity with keystore {tigerResolvedString}")
  public void verifyJwtVariantHasExpectedLocalSignatureIntegrityWithKeyStore(
      JwtVariant variant, String jwt, String keyStore) {
    verifyJwtVariantLocalSignatureIntegrity(
        variant,
        jwt,
        normalizedJwt -> signatureVerificationSteps.hasCryptographicallyValidEs256SignatureFromKid(
            normalizedJwt, keyStore),
        " with KeyStore");
  }

  /**
   * Verifies that a generated JWT variant has the expected local signature integrity using the public key derived from the supplied EC
   * private key.
   *
   * <p>This is intended for PoPP Token checks where the intercepted token carries a {@code kid}
   * header and no embedded JWK. The helper proves that variants which should remain cryptographically valid were signed with the configured
   * PoPP key, while {@code invalid_signature} is locally rejected.</p>
   *
   * @param variant       generated JWT variant
   * @param jwt           generated compact JWT/JWS variant
   * @param privateKeyPem PEM encoded EC private key whose public key verifies the token
   */
  @Und("prüfe die JWT-Variante {jwtVariant} in {tigerResolvedString} hat lokal mit öffentlichem Schlüssel aus privatem Schlüssel {tigerResolvedString} die erwartete Signaturintegrität")
  @And("check JWT variant {jwtVariant} in {tigerResolvedString} has the expected local signature integrity with public key from private key {tigerResolvedString}")
  public void verifyJwtVariantHasExpectedLocalSignatureIntegrityWithPublicKeyFromPrivateKey(
      JwtVariant variant, String jwt, String privateKeyPem) {
    var publicKey = deriveP256PublicKey(loadEcPrivateKey(privateKeyPem));
    verifyJwtVariantLocalSignatureIntegrity(
        variant,
        jwt,
        normalizedJwt -> signatureVerificationSteps
            .hasCryptographicallyValidEs256SignatureWithPublicKey(normalizedJwt, publicKey),
        " with public key derived from private key");
  }

  /**
   * Verifies an ES256 JWT signature with the public key derived from the supplied EC private key.
   *
   * <p>This is intended for PoPP Token checks where the token references the PoPP server key by
   * {@code kid}; the local proof must use the expected PoPP server key instead of embedded JOSE header key material.</p>
   *
   * @param jwt           compact JWT/JWS to verify
   * @param privateKeyPem PEM encoded EC private key whose public key verifies the token
   */
  @Und("verifiziere die ES256 Signatur des JWT {tigerResolvedString} mit öffentlichem Schlüssel aus privatem Schlüssel {tigerResolvedString}")
  @And("verify the ES256 signature of the JWT {tigerResolvedString} with public key from private key {tigerResolvedString}")
  public void verifyJwtSignatureWithPublicKeyFromPrivateKey(String jwt, String privateKeyPem) {
    var publicKey = deriveP256PublicKey(loadEcPrivateKey(privateKeyPem));
    assertThat(signatureVerificationSteps.hasCryptographicallyValidEs256SignatureWithPublicKey(
        normalizePossiblyFormEncodedJwt(jwt), publicKey))
        .as("JWT signature must verify with public key derived from the configured private key")
        .isTrue();
  }

  /**
   * Verifies that a generated JWT variant has the expected local signature integrity using the X.509 certificate embedded in the token's
   * {@code x5c} header.
   *
   * <p>This is intended for Subject Token checks where the exact signing certificate is carried by
   * the Subject Token itself. The cryptographic check uses the raw JWS signing input, so it can still confirm re-signatures for variants
   * whose JOSE header is intentionally not valid JSON.</p>
   *
   * @param variant generated JWT variant
   * @param jwt     generated compact JWT/JWS variant
   */
  @Und("prüfe die JWT-Variante {jwtVariant} in {tigerResolvedString} hat lokal mit eingebettetem Zertifikat die erwartete Signaturintegrität")
  @And("check JWT variant {jwtVariant} in {tigerResolvedString} has the expected local signature integrity with embedded certificate")
  public void verifyJwtVariantHasExpectedLocalSignatureIntegrityWithEmbeddedCertificate(
      JwtVariant variant, String jwt) {
    verifyJwtVariantLocalSignatureIntegrity(
        variant,
        jwt,
        signatureVerificationSteps::hasCryptographicallyValidEs256SignatureWithEmbeddedCertificate,
        " with its embedded certificate");
  }

  /**
   * Verifies local signature integrity for variants that either preserve or intentionally break the compact JWS signature.
   *
   * @param variant           generated JWT variant
   * @param jwt               generated compact JWT/JWS variant
   * @param signatureVerifier cryptographic verifier for the relevant public key source
   * @param assertionSuffix   suffix naming the verification key source in assertion messages
   */
  private void verifyJwtVariantLocalSignatureIntegrity(
      JwtVariant variant,
      String jwt,
      Predicate<String> signatureVerifier,
      String assertionSuffix) {
    var normalizedJwt = normalizePossiblyFormEncodedJwt(jwt);

    if (variant.keepsSignature()) {
      assertThat(signatureVerifier.test(normalizedJwt))
          .as("JWT variant '%s' must keep a locally valid cryptographic signature%s",
              variant.token(), assertionSuffix)
          .isTrue();
      return;
    }

    if (JwtVariant.INVALID_SIGNATURE == variant) {
      assertThat(signatureVerifier.test(normalizedJwt))
          .as("JWT variant '%s' must be locally cryptographically invalid%s",
              variant.token(), assertionSuffix)
          .isFalse();
      return;
    }

    log.debug("Skipping local signature integrity check{} for JWT variant '{}'",
        assertionSuffix, variant.token());
  }

  /**
   * Verifies that the requested JWT variant is visibly present in the intercepted compact token.
   *
   * <p>Cryptographic checks alone cannot distinguish a correctly re-signed semantic variant from
   * an unchanged valid token. This structural check makes sure the guarded endpoint actually received the requested malformed shape or JOSE
   * header change.</p>
   *
   * @param variant expected generated JWT variant
   * @param jwt     intercepted compact JWT/JWS variant
   */
  @Und("prüfe die JWT-Variante {jwtVariant} in {tigerResolvedString} ist lokal strukturell angewendet")
  @And("check JWT variant {jwtVariant} in {tigerResolvedString} is structurally applied locally")
  public void verifyJwtVariantIsStructurallyApplied(JwtVariant variant, String jwt) {
    var normalizedJwt = normalizePossiblyFormEncodedJwt(jwt);
    var parts = normalizedJwt.split("\\.", -1);

    switch (variant) {
      case TWO_SEGMENTS -> assertThat(parts).as("JWT variant '%s'", variant.token()).hasSize(2);
      case ALG_NONE -> {
        assertThat(parts).as("JWT variant '%s'", variant.token()).hasSize(3);
        assertThat(parts[2]).as("JWT signature segment").isEmpty();
        assertThat(parseJsonObject(decodeBase64UrlSegment(parts[0], "header"), "JOSE header")
            .path("alg").asText())
            .as("JWT alg header")
            .isEqualTo("none");
      }
      case JWE_LIKE_FIVE_SEGMENTS -> assertThat(parts).as("JWT variant '%s'", variant.token())
          .hasSize(5);
      case INVALID_HEADER_BASE64URL -> assertThat(parts[0]).as("JWT header segment")
          .isEqualTo("###");
      case INVALID_PAYLOAD_BASE64URL -> assertThat(parts[1]).as("JWT payload segment")
          .isEqualTo("###");
      case INVALID_SIGNATURE_BASE64URL -> assertThat(parts[2]).as("JWT signature segment")
          .isEqualTo("###");
      case INVALID_HEADER_JSON -> assertThat(decodeBase64UrlSegment(parts[0], "header"))
          .as("JWT header JSON")
          .isEqualTo("{");
      case INVALID_PAYLOAD_JSON -> assertThat(decodeBase64UrlSegment(parts[1], "payload"))
          .as("JWT payload JSON")
          .isEqualTo("{");
      case INVALID_HEADER_JSON_UNQUOTED_KEYS -> assertUnquotedJsonFieldNames(
          decodeBase64UrlSegment(parts[0], "header"), "JWT header");
      case INVALID_PAYLOAD_JSON_UNQUOTED_KEYS -> assertUnquotedJsonFieldNames(
          decodeBase64UrlSegment(parts[1], "payload"), "JWT payload");
      case UNKNOWN_HEADER_PARAMETER -> assertThat(
          parseJsonObject(decodeBase64UrlSegment(parts[0], "header"), "JOSE header")
              .path("unknown_guard_parameter").asText())
          .as("JWT header unknown_guard_parameter")
          .isEqualTo("unsupported");
      case UNSUPPORTED_CRIT -> {
        var header = parseJsonObject(decodeBase64UrlSegment(parts[0], "header"), "JOSE header");
        assertThat(header.path("crit").toString())
            .as("JWT crit header")
            .contains("\"unsupported_guard_parameter\"");
        assertThat(header.path("unsupported_guard_parameter").asText())
            .as("JWT unsupported critical header value")
            .isEqualTo("requested-by-crit");
      }
      case UNSUPPORTED_ALG -> assertThat(
          parseJsonObject(decodeBase64UrlSegment(parts[0], "header"), "JOSE header")
              .path("alg").asText())
          .as("JWT alg header")
          .isEqualTo("RS999");
      case MISSING_ALG -> assertThat(
          parseJsonObject(decodeBase64UrlSegment(parts[0], "header"), "JOSE header").has("alg"))
          .as("JWT alg header must be absent")
          .isFalse();
      case DUPLICATE_ALG_HEADERS -> assertThat(
          countRootObjectFieldNameOccurrences(
              decodeBase64UrlSegment(parts[0], "header"), "alg", "JOSE header"))
          .as("JWT root-level alg header occurrence count")
          .isGreaterThanOrEqualTo(2);
      case RESIGN_CORRECT_KEY -> {
        assertThat(parts).as("JWT variant '%s'", variant.token()).hasSize(3);
        assertThat(parseJsonObject(decodeBase64UrlSegment(parts[0], "header"), "JOSE header")
            .path("alg").asText())
            .as("JWT alg header")
            .isEqualTo("ES256");
      }
      case INVALID_SIGNATURE -> assertThat(
          parseJsonObject(decodeBase64UrlSegment(parts[1], "payload"), "JWT payload")
              .path("broken_signature_marker").asBoolean())
          .as("JWT invalid signature marker")
          .isTrue();
      case NESTED_CTY_JWT_VALID_INNER, NESTED_CTY_JWT_INVALID_INNER -> {
        var header = parseJsonObject(decodeBase64UrlSegment(parts[0], "header"), "JOSE header");
        assertThat(header.path("cty").asText()).as("JWT cty header").isEqualTo("JWT");
        assertThat(decodeBase64UrlSegment(parts[1], "payload"))
            .as("nested JWT payload")
            .contains(".");
      }
      default -> throw new AssertionError("Unknown JWT variant " + variant.token());
    }
  }

  /**
   * Decodes compact JWTs that were read from an x-www-form-urlencoded request body.
   *
   * @param jwt compact JWT as seen by RBEL or a generated variable
   * @return decoded JWT when URL decoding produces a compact token, otherwise the original value
   */
  private String normalizePossiblyFormEncodedJwt(String jwt) {
    if (jwt == null || !jwt.contains("%")) {
      return jwt;
    }

    try {
      var decoded = URLDecoder.decode(jwt, StandardCharsets.UTF_8);
      return decoded.contains(".") ? decoded : jwt;
    } catch (IllegalArgumentException e) {
      return jwt;
    }
  }

  /**
   * Extracts the compact token from an Authorization header value.
   *
   * @param authorizationHeader raw Authorization header
   * @return compact token value without scheme
   */
  String extractCompactTokenFromAuthorizationHeader(String authorizationHeader) {
    var trimmedHeader = authorizationHeader == null ? "" : authorizationHeader.trim();
    var firstWhitespace = trimmedHeader.indexOf(' ');
    var token = firstWhitespace >= 0 ? trimmedHeader.substring(firstWhitespace + 1).trim()
        : trimmedHeader;
    assertThat(token)
        .as("Authorization header must contain a compact token")
        .contains(".");
    return token;
  }

  /**
   * Decodes one Base64URL JWT segment into its raw UTF-8 text representation.
   *
   * @param segment     compact JWT segment
   * @param description human-readable segment name for error messages
   * @return decoded raw UTF-8 text
   */
  private String decodeBase64UrlSegment(String segment, String description) {
    try {
      return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new AssertionError("Failed to decode JWT " + description + " segment.", e);
    }
  }

  /**
   * Asserts that a JWT segment contains intentionally unquoted JSON object field names.
   *
   * @param rawJson     raw JSON-like segment text
   * @param description human-readable segment description
   */
  private void assertUnquotedJsonFieldNames(String rawJson, String description) {
    assertThat(rawJson)
        .as(description + " must contain unquoted JSON field names")
        .containsPattern("[\\{,]\\s*[A-Za-z_][A-Za-z0-9_\\-]*\\s*:");
  }

  /**
   * Counts field-name occurrences directly on the root JSON object, ignoring nested objects and arrays.
   *
   * @param rawJson     raw JSON object text
   * @param fieldName   field name to count on the root object
   * @param description human-readable description for error messages
   * @return number of root-object field-name occurrences
   */
  private int countRootObjectFieldNameOccurrences(String rawJson, String fieldName,
      String description) {
    try (JsonParser parser = new ObjectMapper().createParser(rawJson)) {
      var firstToken = parser.nextToken();
      if (firstToken != JsonToken.START_OBJECT) {
        throw new AssertionError(description + " must be a JSON object.");
      }

      var objectDepth = 1;
      var count = 0;
      while (objectDepth > 0) {
        var token = parser.nextToken();
        if (token == null) {
          throw new AssertionError(description + " JSON object ended unexpectedly.");
        }
        if (token == JsonToken.PROPERTY_NAME && objectDepth == 1
            && fieldName.equals(parser.currentName())) {
          count++;
        } else if (token == JsonToken.START_OBJECT) {
          objectDepth++;
        } else if (token == JsonToken.END_OBJECT) {
          objectDepth--;
        }
      }
      return count;
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse " + description + " as JSON object.", e);
    }
  }

  /**
   * Parses a JSON object and raises a dedicated assertion error if parsing fails or the root node is not an object.
   *
   * @param rawJson     JSON text to parse
   * @param description human-readable description for error messages
   * @return parsed JSON object
   */
  private ObjectNode parseJsonObject(String rawJson, String description) {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      return (ObjectNode) objectMapper.readTree(rawJson);
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse " + description + " as JSON object.", e);
    } catch (ClassCastException e) {
      throw new AssertionError(description + " must be a JSON object.", e);
    }
  }

  /**
   * Loads an EC private key from PEM text or raw PKCS#8 Base64 content.
   *
   * @param privateKeyPem PKCS#8 or SEC1 PEM encoded private key, or raw PKCS#8 Base64 key content
   * @return parsed EC private key
   */
  private ECPrivateKey loadEcPrivateKey(String privateKeyPem) {
    var normalizedPem = normalizePrivateKeyPem(privateKeyPem);

    try (var pemParser = new PEMParser(new StringReader(normalizedPem))) {
      var parsedObject = pemParser.readObject();
      if (parsedObject == null) {
        throw new AssertionError("No PEM object found in private key input for JWT verification.");
      }
      var keyConverter = new JcaPEMKeyConverter();
      var privateKey = switch (parsedObject) {
        case PEMKeyPair pemKeyPair -> keyConverter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
        case PrivateKeyInfo privateKeyInfo -> keyConverter.getPrivateKey(privateKeyInfo);
        default -> throw new AssertionError("Unsupported EC private key format for JWT verification.");
      };
      if (privateKey instanceof ECPrivateKey ecPrivateKey) {
        return ecPrivateKey;
      }
      throw new AssertionError("Private key for JWT verification must be an EC private key.");
    } catch (IOException | IllegalArgumentException e) {
      throw new AssertionError("Failed to parse EC private key for JWT verification.", e);
    }
  }

  /**
   * Derives the P-256 public key corresponding to one EC private key.
   *
   * @param privateKey parsed EC private key
   * @return EC public key on the same curve
   */
  private ECPublicKey deriveP256PublicKey(ECPrivateKey privateKey) {
    var curveSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
    assertThat(curveSpec)
        .as("P-256 curve parameters must be available")
        .isNotNull();

    var point = curveSpec.getG().multiply(privateKey.getS()).normalize();
    var publicPoint = new java.security.spec.ECPoint(
        point.getAffineXCoord().toBigInteger(),
        point.getAffineYCoord().toBigInteger());

    try {
      return (ECPublicKey) KeyFactory.getInstance("EC")
          .generatePublic(new ECPublicKeySpec(publicPoint, privateKey.getParams()));
    } catch (GeneralSecurityException e) {
      throw new AssertionError("Failed to derive EC public key for JWT verification.", e);
    }
  }

  /**
   * Normalizes PEM input so the loader accepts both full PEM blocks and raw Base64 payloads.
   *
   * @param privateKeyPem raw private key content from the test context
   * @return canonical PEM representation
   */
  String normalizePrivateKeyPem(String privateKeyPem) {
    var trimmed = privateKeyPem == null ? "" : privateKeyPem.trim();
    if (trimmed.isEmpty()) {
      throw new AssertionError("Private key for JWT verification must not be empty.");
    }
    if (trimmed.contains("-----BEGIN PRIVATE KEY-----")
        || trimmed.contains("-----BEGIN EC PRIVATE KEY-----")) {
      return trimmed;
    }
    return "-----BEGIN PRIVATE KEY-----\n" + trimmed + "\n-----END PRIVATE KEY-----";
  }

}
