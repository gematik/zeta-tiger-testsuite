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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import io.restassured.http.Method;
import io.restassured.response.Response;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cucumber step definitions for JWT (JSON Web Token) operations.
 *
 * <p>This class provides step definitions for retrieving JWT tokens from specified endpoints
 * and storing them in Tiger configuration variables for use in subsequent test steps.
 *
 * <p>The implementation uses a trust-all SSL context to facilitate testing against endpoints
 * with self-signed or untrusted certificates.
 */

public class JwtSteps {

  private static final Logger log = LoggerFactory.getLogger(JwtSteps.class);
  private static final String DEFAULT_CLIENT_ID = "zeta-client";

  /**
   * Retrieves a JWT token from a specified URL and stores it in a Tiger configuration variable.
   *
   * @param url     the location of the token
   * @param varName the name of the variable
   * @throws Exception in case of an error during the communication.
   */
  @Dann("Hole JWT von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT from {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void getJwtToken(String url, String varName) throws Exception {

    getJwtToken(DEFAULT_CLIENT_ID, null, url, varName);
  }

  /**
   * Retrieves a JWT token from a specified URL and stores it in a Tiger configuration variable.
   *
   * @param clientId the manipulated client ID
   * @param url      the location of the token
   * @param varName  the name of the variable
   * @throws Exception in case of an error during the communication.
   */
  @Dann("Hole JWT für Client {tigerResolvedString} von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT for Client {tigerResolvedString} from {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void getJwtTokenForClient(String clientId, String url, String varName) throws Exception {

    getJwtToken(clientId, null, url, varName);
  }

  /**
   * Retrieves a JWT token from a specified URL and stores it in a Tiger configuration variable.
   *
   * @param additionalHeader the manipulated additional header
   * @param url              the location of the token
   * @param varName          the variable name
   * @throws Exception in case of an error during communication
   */
  @Dann("Hole JWT mit zusätzlichem Header {tigerResolvedString} von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT with additional header {tigerResolvedString} from {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void getJwtTokenWithManipulatedHeader(String additionalHeader, String url, String varName)
      throws Exception {

    getJwtToken(DEFAULT_CLIENT_ID, additionalHeader, url, varName);
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
   * @throws Exception If an error occurs during the HTTP request or response processing
   */

  @Dann("Hole JWT für Client {tigerResolvedString} mit zusätzlichem Header {tigerResolvedString} von {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @Then("Get JWT for Client {tigerResolvedString} with additional header {tigerResolvedString} from {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void getJwtToken(String clientId, String additionalHeader, String url, String varName)
      throws Exception {

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
            + "&scope=audience-target-scope"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
            + "&client_assertion=client-jwt";

    try {
      Map<String, String> params = new HashMap<>();
      params.put(urlParameters, "");
      Map<String, String> headers = new HashMap<>();
      if (null != additionalHeader && !additionalHeader.isEmpty()) {
        headers.put(additionalHeader, "true");
      }

      Response response = TigerHttpClient.givenDefaultSpec().formParams(params).headers(headers)
          .request(Method.POST, new URI(url));
      TigerGlobalConfiguration.putValue(varName, new String(response.body().asByteArray()),
          ConfigurationValuePrecedence.TEST_CONTEXT);
      log.info(String.format("Storing JwtToken '%s' in variable '%s'",
          new String(response.body().asByteArray()), varName));
    } catch (Throwable $ex) {
      throw $ex;
    }
  }

  /**
   * @throws Exception in case of an error during the communication.
   */
  @Dann("Setze {tigerResolvedString} im JWT-Token {tigerResolvedString} auf den Wert {tigerResolvedString} und signiere mit dem Key {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  public void manipulateJwtToken(String path, String token, String value, String keyFile,
      String varName) throws Exception {
    String[] atoms = path.toLowerCase().split("\\.");
    String[] parts = token.split("\\.");
    String name = atoms[0];
    String result = token;
    switch (name) {
      case "header":
        String header = token.split("\\.")[0];
        String part0 = new String(
            Base64.getUrlEncoder().encode(changeJsonValue(header, atoms[1], value).getBytes()));
        result = part0 + '.' + parts[1] + '.' + parts[2];
        break;
      case "payload":
        String claimSet = changeJsonValue(parts[1], atoms[1], value);
        result = signJwtWithRS256(loadPrivateKey(keyFile), claimSet);
        break;
      case "hash":
        if (parts.length >= 3) {
          result =
              parts[0] + '.' + parts[1] + '.' + parts[2].substring(0, 20) + "xxx" + token.substring(
                  23);
        } else {
          String set = changeJsonValue(parts[1], atoms[1], value);
          String newToken = signJwtWithRS256(loadPrivateKey(keyFile), set);
          String[] signParts = newToken.split("\\.");
          result = signParts[0] + '.' + signParts[1] + '.' + signParts[2].substring(0, 20) + "xxx"
              + token.substring(23);
        }
        break;
    }

    TigerGlobalConfiguration.putValue(varName, result,
        ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info(String.format("Storing manipulated JwtToken '%s' in variable '%s'",
        result, varName));
  }

  private String changeJsonValue(String part, String key, String value)
      throws JsonProcessingException {
    String headerJson = new String(Base64.getUrlDecoder().decode(part));

    ObjectMapper objectMapper = new ObjectMapper();
    ObjectNode node = (ObjectNode) objectMapper.readTree(headerJson);

    if (node.get(key) != null) {
      switch (node.get(key).getNodeType()) {
        case BOOLEAN -> node.put(key, Boolean.parseBoolean(value));
        case NUMBER -> node.put(key, Integer.parseInt(value));
        case MISSING, STRING -> node.put(key, value);
      }
    } else {
      node.put(key, value);
    }

    // Wieder zu String und Base64URL enkodieren
    return node.toString();
  }

  public static String signJwtWithRS256(RSAPrivateKey privateKey, String claimSet)
      throws JOSEException, ParseException {
    // Header mit Algorithmus RS256
    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();

    // Claims setzen (Beispielclaims)
    JWTClaimsSet claimsSet = JWTClaimsSet.parse(claimSet);

    // Signed JWT erstellen
    SignedJWT signedJWT = new SignedJWT(header, claimsSet);

    // Signer mit privatem RSA Schlüssel
    RSASSASigner signer = new RSASSASigner(privateKey);

    // Signieren
    signedJWT.sign(signer);

    // Serialisieren und als String zurückgeben
    return signedJWT.serialize();
  }

  public static RSAPrivateKey loadPrivateKey(String filename) throws Exception {
    String cp = filename.trim();
    final String resource = cp.startsWith("/") ? cp.substring(1) : cp;
    try (InputStream in = JwtSteps.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException(
            "Keyfile not found in the path under resources: " + resource);
      }
      String keyPEM = new String(in.readAllBytes(), StandardCharsets.UTF_8);

      // Header und Footer entfernen
      keyPEM = keyPEM.replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", ""); // Leerzeichen, Zeilenumbrüche entfernen

      // Base64-dekodieren
      byte[] encoded = Base64.getDecoder().decode(keyPEM);

      // Key-Spezifikation erstellen
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);

      // KeyFactory für RSA erzeugen und PrivateKey generieren
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return (RSAPrivateKey) kf.generatePrivate(keySpec);
    }
  }
}