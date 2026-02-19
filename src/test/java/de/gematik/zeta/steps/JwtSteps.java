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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSHeader.Builder;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.restassured.http.Method;
import io.restassured.response.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.teletrust.TeleTrusTObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Cucumber step definitions for JWT (JSON Web Token) operations.
 *
 * <p>This class provides step definitions for retrieving JWT tokens from specified endpoints
 * and storing them in Tiger configuration variables for use in subsequent test steps.
 *
 * <p>The implementation uses a trust-all SSL context to facilitate testing against endpoints
 * with self-signed or untrusted certificates.
 */
@Slf4j
public class JwtSteps {

  private static final String DEFAULT_CLIENT_ID = "zeta-client";

  /**
   * Signs the provided JWT claim set with RS256 and returns the compact serialized token.
   *
   * @param privateKey RSA private key used for signing
   * @param claimSet   JSON string representation of the claims
   * @return serialized JWT string
   */
  public static String signJwtWithRs256(RSAPrivateKey privateKey, String claimSet) {
    // Header with RS256 algorithm
    JWSHeader header = new Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();
    // Try to parse provided claims
    JWTClaimsSet claimsSet;
    try {
      claimsSet = JWTClaimsSet.parse(claimSet);
    } catch (ParseException e) {
      throw new AssertionError("Parsing of the provided claims failed for " + claimSet);
    }
    // Create signed JWT instance
    SignedJWT signedJwt = new SignedJWT(header, claimsSet);
    // Initialize signer with private RSA key
    RSASSASigner signer = new RSASSASigner(privateKey);
    // Sign token
    try {
      signedJwt.sign(signer);
    } catch (JOSEException e) {
      throw new AssertionError("Signing operation failed with " + e.getClass().getSimpleName());
    }
    // Serialize and return JWT
    return signedJwt.serialize();
  }

  /**
   * Loads an RSA private key from the classpath.
   *
   * @param filename classpath resource pointing to the PEM encoded key
   * @return parsed RSA private key
   */
  public static RSAPrivateKey loadPrivateKey(String filename) {
    String cp = filename.trim();
    final String resource = cp.startsWith("/") ? cp.substring(1) : cp;
    String alg = null;
    try (InputStream in = JwtSteps.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException(
            "Keyfile not found in the path under resources: " + resource);
      }
      String keyPem = new String(in.readAllBytes(), StandardCharsets.UTF_8);

      // Remove PEM header and footer markers
      keyPem = keyPem.replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", "");
      // Decode Base64 encoded payload
      byte[] encoded = Base64.getDecoder().decode(keyPem);
      // Build PKCS8 key specification
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
      // Create RSA key from spec
      alg = "RSA";
      KeyFactory kf = KeyFactory.getInstance(alg);
      return (RSAPrivateKey) kf.generatePrivate(keySpec);
    } catch (IOException | InvalidKeySpecException e) {
      throw new AssertionError("Reading failed for file " + filename);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("Did not find " + alg + " algorithm in KeyFactory.");
    }
  }

  /**
   * Retrieves a JWT token from a specified URL and stores it in a Tiger configuration variable.
   *
   * @param url     the location of the token
   * @param varName the name of the variable
   */
  @Dann("Hole JWT von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT from {tigerResolvedString} and store in variable {tigerResolvedString}")
  @Deprecated
  public void getJwtToken(String url, String varName) {
    getJwtToken(DEFAULT_CLIENT_ID, null, url, varName);
  }

  /**
   * Retrieves a JWT token from a specified URL and stores it in a Tiger configuration variable.
   *
   * <p>This method performs an HTTP POST request to the specified URL with predefined OAuth token
   * exchange parameters. It bypasses SSL certificate validation by using a trust-all certificate
   * strategy to facilitate testing against endpoints with self-signed certificates.
   *
   * <p>The retrieved JWT token is stored in the Tiger configuration with TEST_CONTEXT precedence,
   * making it available for subsequent test steps.
   *
   * @param clientId         the manipulated client ID
   * @param additionalHeader the HTTP header manipulation
   * @param url              The URL endpoint to request the JWT token from
   * @param varName          The name of the Tiger configuration variable to store the JWT token
   */
  @Deprecated
  @Dann("Hole JWT für Client {tigerResolvedString} mit zusätzlichem Header {tigerResolvedString} von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT for Client {tigerResolvedString} with additional header {tigerResolvedString} from {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void getJwtToken(String clientId, String additionalHeader, String url, String varName) {
    String urlParameters =
        "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
            + "&client_id=" + clientId
            + "&subject_token=eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJXdVo4bFJodHZvb1lxe"
            + "HExU3A3SHM5ZmU4b2FFSFV6RGNFckRYOUJ2OWhNIn0.eyJleHAiOjE3NTg2MTExNjgsImlhdCI6MTc1ODYxM"
            + "Dg2OCwianRpIjoib25ydHJvOjU5OWNmYjAyLTI0YTktNDViZi0xNDRlLTZjNDg5YTYxMzI2NSIsImlzcyI6I"
            + "mh0dHA6Ly9sb2NhbGhvc3Q6MTgwODAvcmVhbG1zL3NtYy1iIiwiYXVkIjpbInJlcXVlc3Rlci1jbGllbnQiL"
            + "CJhY2NvdW50Il0sInN1YiI6ImQwYWFjYzljLTJkOTMtNDM4YS1hNzAzLWI4Nzc4OTIxODNmOCIsInR5cCI6I"
            + "kJlYXJlciIsImF6cCI6InNtYy1iLWNsaWVudCIsInNpZCI6IjY5ZDgxODA4LTY2ZTYtNDlmMi04OWRiLTdiO"
            + "DBlOGU4OTlmYiIsImFjciI6IjEiLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsiZGVmYXVsdC1yb2xlcy1zb"
            + "WMtYiIsIm9mZmxpbmVfYWNjZXNzIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6e"
            + "yJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2a"
            + "WV3LXByb2ZpbGUiXX19LCJzY29wZSI6ImVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwib"
            + "mFtZSI6IlVzZXIgRXh0ZXJuYWwiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJ1c2VyIiwiZ2l2ZW5fbmFtZSI6I"
            + "lVzZXIiLCJmYW1pbHlfbmFtZSI6IkV4dGVybmFsIiwiZW1haWwiOiJ1c2VyQGJhci5mb28uY29tIn0.sHewe"
            + "6f5zk_EslSVtectqb_91U_6YpYhQoQhWNFwLINJd3ryrKNaLOeB196x5fbAfFGSk-Exa9D24K64xzETnoKrX"
            + "QRrRKi4sSJGxDqtXbkmbxr-fJvyB3Ay_0_lCZAUPNEYH2Sx5caClRnJy60eeKt3pm4JmV5nLFXh-DOYEDc5r"
            + "1NGcl1bwCt70pQJ1aKlMaiUDuC5N8CXSAuUdRc1IWzB324QNBglW4qpUY2anp-j23bnJBhLmYgVeKa_RBksJ"
            + "1-jSgwODeuO1gIR96qqc7SqjzQVgteGumr5zfR3qc5GAGGBIxYX3Jndr4lqcW2-mYffDwp7fWf4a5FJ5wgUu"
            + "w"
            + "&subject_token_type=urn:ietf:params:oauth:token-type:jwt"
            // + "&scope=audience-target-scope"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
            + "&client_assertion=client-jwt";

    Map<String, String> params = new HashMap<>();
    params.put(urlParameters, "");
    Map<String, String> headers = new HashMap<>();
    if (null != additionalHeader && !additionalHeader.isEmpty()) {
      headers.put(additionalHeader, "true");
    }

    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new AssertionError("Failed to create URI from " + url);
    }

    Response response = TigerHttpClient.givenDefaultSpec().formParams(params).headers(headers)
        .request(Method.POST, uri);
    String responseBody = new String(response.body().asByteArray(), StandardCharsets.UTF_8);
    TigerGlobalConfiguration.putValue(varName, responseBody,
        ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Storing JwtToken '{}' in variable '{}'", responseBody, varName);
  }

  /**
   * Retrieves a JWT token from a specified URL and stores it in a Tiger configuration variable.
   *
   * @param clientId the manipulated client ID
   * @param url      the location of the token
   * @param varName  the name of the variable
   */
  @Dann("Hole JWT für Client {tigerResolvedString} von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT for Client {tigerResolvedString} from {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void getJwtTokenForClient(String clientId, String url, String varName) {
    getJwtToken(clientId, null, url, varName);
  }

  /**
   * Retrieves a JWT token from a specified URL and stores it in a Tiger configuration variable.
   *
   * @param additionalHeader the manipulated additional header
   * @param url              the location of the token
   * @param varName          the variable name
   */
  @Dann("Hole JWT mit zusätzlichem Header {tigerResolvedString} von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT with additional header {tigerResolvedString} from {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void getJwtTokenWithManipulatedHeader(String additionalHeader, String url,
      String varName) {
    getJwtToken(DEFAULT_CLIENT_ID, additionalHeader, url, varName);
  }

  /**
   * Manipulates a JWT token according to the provided path and stores the result in the Tiger
   * configuration.
   *
   * @param path    dot separated target part (e.g. {@code header.alg} or {@code payload.exp})
   * @param token   original JWT token to modify
   * @param value   replacement value
   * @param keyFile classpath path to the signing key used for payload/re-sign operations
   * @param varName Tiger configuration variable to store the manipulated token in
   */
  @Dann(
      "Setze {tigerResolvedString} im JWT-Token {tigerResolvedString} auf den Wert {tigerResolvedString} "
          + "und signiere mit dem Key {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  public void manipulateJwtToken(String path, String token, String value, String keyFile,
      String varName) {
    String[] atoms = path.toLowerCase().split("\\.");
    String[] parts = token.split("\\.");
    String name = atoms[0];
    String result;
    switch (name) {
      case "header":
        String header = token.split("\\.")[0];
        String updatedHeader = changeJsonValue(header, atoms[1], value);
        String part0 = Base64.getUrlEncoder()
            .encodeToString(updatedHeader.getBytes(StandardCharsets.UTF_8));
        result = part0 + '.' + parts[1] + '.' + parts[2];
        break;
      case "payload":
        String claimSet = changeJsonValue(parts[1], atoms[1], value);
        result = signJwtWithRs256(loadPrivateKey(keyFile), claimSet);
        break;
      case "re-sign":
        String payload =
            new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        result = signJwtWithRs256(loadPrivateKey(keyFile), payload);
        break;
      case "hash":
        result =
            parts[0] + '.' + parts[1] + '.' + parts[2].substring(0, 20) + "xxx" + token.substring(
                23);
        break;
      default:
        throw new AssertionError("Unknown JWT manipulation " + name);
    }

    TigerGlobalConfiguration.putValue(varName, result,
        ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Storing manipulated JwtToken '{}' in variable '{}'", result, varName);
  }

  /**
   * Updates (or adds) a JSON value within a Base64URL encoded JWT segment.
   *
   * @param part  Base64URL encoded header or payload part of a JWT
   * @param key   JSON property to update
   * @param value replacement value expressed as text
   * @return plain JSON string containing the updated segment
   */
  private String changeJsonValue(String part, String key, String value) {
    // Decode Base64URL part into JSON text
    String headerJson = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);

    ObjectMapper objectMapper = new ObjectMapper();
    ObjectNode node;
    try {
      node = (ObjectNode) objectMapper.readTree(headerJson);
    } catch (JsonProcessingException e) {
      throw new AssertionError("Failed to parse a segment as JSON.");
    }

    if (node.get(key) != null) {
      switch (node.get(key).getNodeType()) {
        case BOOLEAN -> node.put(key, Boolean.parseBoolean(value));
        case NUMBER -> node.put(key, Integer.parseInt(value));
        case MISSING, STRING -> node.put(key, value);
        default -> throw new AssertionError(
            "Unexpected value for changing a JSON value: " + node.get(key).getNodeType());
      }
    } else {
      node.put(key, value);
    }

    return node.toString();
  }


  /**
   * Verifies the ES256 signature of a JWT using a public key provided via jwk or x5c header. For
   * gematik requirements, ES256 is supported with P-256 and brainpoolP256r1 curves.
   *
   * <p>If a jwk is present, its EC public key (P-256 or brainpoolP256r1) is used. Otherwise, the
   * first certificate in the x5c chain is parsed and its EC public key is used for verification.
   *
   * @param jwt the base64 coded JWT
   */
  @Und("verifiziere die ES256 Signatur des JWT {tigerResolvedString}")
  @And("verify the ES256 signature of the JWT {tigerResolvedString}")
  public void verifyJwtSignature(String jwt) {
    SignedJWT signedJwt = parseSignedJwt(jwt);
    assertThat(signedJwt.getHeader().getAlgorithm())
        .as("JWT must use ES256")
        .isEqualTo(JWSAlgorithm.ES256);

    JWK jwk = signedJwt.getHeader().getJWK();
    if (jwk != null) {
      assertThat(jwk)
          .as("JWT must use Elliptic Curve key (EC) for gematik requirements")
          .isInstanceOf(ECKey.class);
      assertThat(isAllowedEcCurve(((ECKey) jwk).getCurve()))
          .as("JWT must use P-256 or brainpoolP256r1 curve for gematik requirements")
          .isTrue();
      ECPublicKey jwkPublicKey;
      try {
        jwkPublicKey = ((ECKey) jwk).toECPublicKey();
      } catch (JOSEException e) {
        throw new AssertionError(
            "Failed to extract EC public key from jwk header: " + e.getMessage(), e);
      }
      verifyWithEcPublicKey(signedJwt, jwkPublicKey, "jwk header");
      return;
    }

    List<com.nimbusds.jose.util.Base64> certChain = signedJwt.getHeader().getX509CertChain();
    assertThat(certChain)
        .as("JWT must contain jwk or x5c certificate chain in header")
        .isNotNull()
        .isNotEmpty();

    X509Certificate certificate = parseCertificateFromX5c(certChain.get(0));
    assertThat(certificate.getPublicKey())
        .as("x5c certificate must provide an EC public key")
        .isInstanceOf(ECPublicKey.class);

    ECPublicKey certificatePublicKey = (ECPublicKey) certificate.getPublicKey();
    Curve certCurve = Curve.forECParameterSpec(certificatePublicKey.getParams());
    assertThat(isAllowedEcCurve(certCurve) || isBrainpoolP256r1Certificate(certificate))
        .as("x5c certificate must use P-256 or brainpoolP256r1 curve for gematik requirements")
        .isTrue();

    verifyWithEcPublicKey(signedJwt, certificatePublicKey, "x5c certificate");
  }

  /**
   * Verifies that the JWT header contains an x5c certificate chain and that the first certificate
   * is self-signed (subject equals issuer and the certificate validates with its own public key).
   *
   * @param jwt the base64 coded JWT
   */
  @Und("prüfe JWT {tigerResolvedString} verwendet ein self-signed x5c Zertifikat")
  @And("check JWT {tigerResolvedString} uses a self-signed x5c certificate")
  public void verifyJwtUsesSelfSignedX5cCertificate(String jwt) {
    SignedJWT signedJwt = parseSignedJwt(jwt);

    List<com.nimbusds.jose.util.Base64> certChain = signedJwt.getHeader().getX509CertChain();
    assertThat(certChain)
        .as("JWT must contain x5c certificate chain in header")
        .isNotNull()
        .isNotEmpty();

    assertThat(certChain)
        .as("Self-signed x5c certificate chain should contain exactly one certificate")
        .hasSize(1);
    
    X509Certificate certificate = parseCertificateFromX5c(certChain.get(0));
    assertThat(certificate.getSubjectX500Principal())
        .as("x5c certificate must be self-signed")
        .isEqualTo(certificate.getIssuerX500Principal());

    try {
      certificate.verify(certificate.getPublicKey());
    } catch (GeneralSecurityException e) {
      throw new AssertionError("x5c certificate must be self-signed: " + e.getMessage(), e);
    }
  }

  /**
   * Checks whether the curve is allowed for gematik requirements (P-256 or brainpoolP256r1).
   *
   * @param curve the JOSE curve to check
   * @return true if the curve is allowed
   */
  private boolean isAllowedEcCurve(Curve curve) {
    if (curve == null) {
      return false;
    }
    if (Curve.P_256.equals(curve)) {
      return true;
    }
    return "brainpoolP256r1".equalsIgnoreCase(curve.getName());
  }

  /**
   * Determines whether an X.509 certificate uses the brainpoolP256r1 curve by inspecting the
   * algorithm parameters.
   *
   * @param certificate the certificate to inspect
   * @return true if the certificate uses brainpoolP256r1
   */
  private boolean isBrainpoolP256r1Certificate(X509Certificate certificate) {
    try {
      SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(
          certificate.getPublicKey().getEncoded());
      ASN1Encodable params = spki.getAlgorithm().getParameters();
      if (params instanceof ASN1ObjectIdentifier) {
        return TeleTrusTObjectIdentifiers.brainpoolP256r1.equals(params);
      }
    } catch (RuntimeException e) {
      log.debug("Failed to resolve EC curve OID from certificate: {}", e.getMessage(), e);
    }
    return false;
  }

  /**
   * Parses a compact serialized JWT into a {@link SignedJWT}.
   *
   * @param jwt the compact serialized JWT
   * @return the parsed {@link SignedJWT}
   */
  private SignedJWT parseSignedJwt(String jwt) {
    try {
      return SignedJWT.parse(jwt);
    } catch (ParseException e) {
      throw new AssertionError("Failed to parse JWT: " + e.getMessage(), e);
    }
  }

  /**
   * Verifies a JWT signature using the given EC public key. If Nimbus fails (e.g., unsupported
   * curve handling), falls back to Bouncy Castle verification.
   *
   * @param signedJwt the parsed, signed JWT
   * @param publicKey the EC public key used for verification
   * @param keySource human-readable description of the key source (e.g., jwk header, x5c)
   */
  private void verifyWithEcPublicKey(SignedJWT signedJwt, ECPublicKey publicKey, String keySource) {
    boolean valid;
    try {
      JWSVerifier verifier = new ECDSAVerifier(publicKey);
      valid = signedJwt.verify(verifier);
    } catch (JOSEException e) {
      try {
        valid = verifyWithBcEcdsa(signedJwt, publicKey, keySource);
      } catch (AssertionError bcError) {
        AssertionError combined = new AssertionError(
            "Failed to verify JWT signature using " + keySource + ": " + e.getMessage(), e);
        combined.addSuppressed(bcError);
        throw combined;
      }
    }

    assertThat(valid)
        .as("JWT signature must verify with public key from " + keySource)
        .isTrue();

    log.info("JWT signature verified successfully using {}", keySource);
  }

  /**
   * Verifies a JWT ECDSA signature using Bouncy Castle (SHA256withECDSA).
   *
   * @param signedJwt the parsed, signed JWT
   * @param publicKey the EC public key used for verification
   * @param keySource human-readable description of the key source (e.g., jwk header, x5c)
   * @return true if the signature verifies
   */
  private boolean verifyWithBcEcdsa(
      SignedJWT signedJwt, ECPublicKey publicKey, String keySource) {
    try {
      if (Security.getProvider("BC") == null) {
        Security.addProvider(new BouncyCastleProvider());
      }
      Signature signature = Signature.getInstance("SHA256withECDSA", "BC");
      signature.initVerify(publicKey);
      signature.update(signedJwt.getSigningInput());
      byte[] jwsSignature = signedJwt.getSignature().decode();
      return signature.verify(jwsEcdsaSignatureToDer(jwsSignature));
    } catch (GeneralSecurityException e) {
      throw new AssertionError(
          "Failed to verify JWT signature using " + keySource + ": " + e.getMessage(), e);
    }
  }

  /**
   * Converts a JWS ECDSA signature (raw R||S) into DER encoding.
   *
   * @param jwsSignature the raw JWS signature bytes
   * @return DER-encoded ECDSA signature
   */
  private byte[] jwsEcdsaSignatureToDer(byte[] jwsSignature) {
    if (jwsSignature.length % 2 != 0) {
      throw new AssertionError("Invalid JWS ECDSA signature length: " + jwsSignature.length);
    }
    int partLen = jwsSignature.length / 2;
    byte[] bytesPartR = new byte[partLen];
    byte[] bytesPartS = new byte[partLen];
    System.arraycopy(jwsSignature, 0, bytesPartR, 0, partLen);
    System.arraycopy(jwsSignature, partLen, bytesPartS, 0, partLen);
    BigInteger r = new BigInteger(1, bytesPartR);
    BigInteger s = new BigInteger(1, bytesPartS);
    ASN1EncodableVector v = new ASN1EncodableVector();
    v.add(new ASN1Integer(r));
    v.add(new ASN1Integer(s));
    try {
      return new DERSequence(v).getEncoded();
    } catch (IOException e) {
      throw new AssertionError("Failed to encode ECDSA signature: " + e.getMessage(), e);
    }
  }

  /**
   * Parses the first x5c certificate entry from a JWT header into an {@link X509Certificate}.
   *
   * @param base64Certificate the base64-encoded certificate (x5c entry)
   * @return parsed {@link X509Certificate}
   */
  private X509Certificate parseCertificateFromX5c(
      com.nimbusds.jose.util.Base64 base64Certificate) {
    try {
      CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
      return (X509Certificate) certificateFactory.generateCertificate(
          new ByteArrayInputStream(base64Certificate.decode()));
    } catch (CertificateException e) {
      throw new AssertionError("Failed to parse x5c certificate from JWT header: "
          + e.getMessage(), e);
    }
  }

}
