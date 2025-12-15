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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.perf.FileUtils;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Cucumber step definitions for TigerProxy manipulation operations.
 *
 * <p>This class provides step definitions for manipulating the TigerProxy.
 *
 */
public class TigerProxyManipulationsSteps {
  private final Random random = new Random();
  private final RestTemplate restTemplate = new RestTemplate();

  /**
   * Sends a manipulation request to the TigerProxy to apply a specific modification on intercepted messages.
   * This method instructs the TigerProxy identified by the given URL to modify intercepted data
   * according to the provided message criteria. It targets a specified JWT field and updates its value
   * with the new one supplied.
   *
   * @param tigerProxy URL or identifier of the TigerProxy instance responsible for performing the manipulation
   * @param field The internal name of the JWT field whose value should be altered
   * @param value The new value to be assigned to the specified JWT field during manipulation
   */
  @Dann("Setze im TigerProxy {string} die JwtManipulation auf Feld {string} und Wert {string}")
  @Then("Set the JwtManipulation in the TigerProxy {string} to field {string} and value {string}")
  public void setTigerProxyJwtManipulation(String tigerProxy, String field, String value) {
    String url = getUrl(tigerProxy, "${paths.tigerProxy.modifyJwsTokenPath}");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    try {
      ResponseEntity<String> response = restTemplate.postForEntity(
          url, new HttpEntity<>(Map.of("field", field, "value", value), headers), String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    } catch (RestClientException e) {
      throw new AssertionError("The JWT manipulation could not be set in the TigerProxy.");
    }
  }

  /**
   * Configures a JWT manipulation on the TigerProxy without re-signing.
   *
   * @param tigerProxy URL of the TigerProxy
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für JWT in {string} das Feld {string} auf Wert {tigerResolvedString}")
  @Then("Set in TigerProxy {tigerResolvedString} for JWT in {string} the field {string} to value {tigerResolvedString}")
  public void setTigerProxyJwtManipulation(String tigerProxy, String jwtLocation, String jwtField, String value) {
    sendJwtManipulation(tigerProxy, Map.of(
        "name", "jwt-modification-" + random.nextInt(1000),
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value));
  }

  /**
   * Sends a manipulation request to the TigerProxy to modify intercepted messages based on specified criteria.
   * This method directs the TigerProxy to apply a modification targeting a particular field within the message,
   * identified by its RBel path. It updates the field's value with the provided new value during message interception.
   *
   * @param tigerProxy URL of the TigerProxy that performs the manipulation
   * @param message    Logic to identify the messages that needs to be manipulated
   * @param field      RBel path identifier of the field you want to manipulate
   * @param value      The new value to assign to the specified field
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für die Nachricht {tigerResolvedString} die Manipulation auf "
      + "Feld {string} und Wert {string}")
  @Then("Set the manipulation in the TigerProxy {tigerResolvedString} for message {tigerResolvedString} to "
      + "field {string} and value {string}")
  public void setTigerProxyManipulation(String tigerProxy, String message, String field, String value) {
    sendRbelManipulation(tigerProxy, Map.of(
        "name", "modification" + random.nextInt(100),
        "condition", message,
        "targetElement", field,
        "replaceWith", value,
        "deleteAfterNExecutions", 1)); // only use the manipulation ONCE and delete it afterwards
  }

  /**
   * Sends a manipulation request to the TigerProxy to modify intercepted messages with execution count.
   * This method directs the TigerProxy to apply a modification targeting a particular field within the message,
   * identified by its RBel path, for a specified number of executions.
   *
   * @param tigerProxy URL of the TigerProxy that performs the manipulation
   * @param message    Logic to identify the messages that needs to be manipulated
   * @param field      RBel path identifier of the field you want to manipulate
   * @param value      The new value to assign to the specified field
   * @param executions Number of times to execute before auto-clearing
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für die Nachricht {tigerResolvedString} die Manipulation auf "
      + "Feld {string} und Wert {tigerResolvedString} und {int} Ausführungen")
  @Then("Set the manipulation in the TigerProxy {tigerResolvedString} for message {tigerResolvedString} to "
      + "field {string} and value {tigerResolvedString} with {int} executions")
  public void setTigerProxyManipulationWithExecutions(String tigerProxy, String message, String field, String value,
      Integer executions) {
    sendRbelManipulation(tigerProxy, Map.of(
        "name", "modification" + random.nextInt(100),
        "condition", message,
        "targetElement", field,
        "replaceWith", value,
        "deleteAfterNExecutions", executions));
  }

  /**
   * Sends a manipulation request to the TigerProxy to modify intercepted messages using regex replacement.
   * This is useful for modifying form-data fields which cannot be directly addressed by RBel path.
   * The regex filter is applied to the target element and matching parts are replaced with the new value.
   *
   * @param tigerProxy  URL of the TigerProxy that performs the manipulation
   * @param message     Logic to identify the messages that needs to be manipulated
   * @param field       RBel path identifier of the field you want to manipulate (e.g., $.body)
   * @param regexFilter Regex pattern to find the part to replace within the target element
   * @param value       The new value to replace the matched regex with
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für die Nachricht {tigerResolvedString} die Regex-Manipulation auf "
      + "Feld {string} mit Regex {string} und Wert {tigerResolvedString}")
  @Then("Set the regex manipulation in the TigerProxy {tigerResolvedString} for message {tigerResolvedString} to "
      + "field {string} with regex {string} and value {tigerResolvedString}")
  public void setTigerProxyRegexManipulation(String tigerProxy, String message, String field, String regexFilter,
      String value) {
    sendRbelManipulation(tigerProxy, Map.of(
        "name", "regex-modification" + random.nextInt(100),
        "condition", message,
        "targetElement", field,
        "regexFilter", regexFilter,
        "replaceWith", value,
        "deleteAfterNExecutions", 1));
  }

  /**
   * Clears all existing manipulations configured in the TigerProxy instance.
   * This method instructs the TigerProxy, identified by the given URL or reference, to remove all active
   * manipulations, effectively resetting its modification rules to a clean state.
   *
   * @param tigerProxy URL or identifier of the TigerProxy instance responsible for managing manipulations
   */
  @Dann("Alle Manipulationen im TigerProxy {tigerResolvedString} werden gestoppt")
  @Then("Reset all manipulation in the TigerProxy {tigerResolvedString}")
  public void resetTigerProxyManipulation(String tigerProxy) {
    String modificationUrl = getUrl(tigerProxy, "${paths.tigerProxy.modificationPath}");
    try {
      restTemplate.delete(modificationUrl);
      ResponseEntity<String> resetResponse =
          restTemplate.postForEntity(getUrl(tigerProxy, "${paths.tigerProxy.resetJwtManipulationPath}"), null, String.class);
      assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    } catch (RestClientException e) {
      throw new AssertionError("The manipulation could not be removed in the TigerProxy.");
    }
  }


  /**
   * Sends a key from a file with the specified algorithm to the given TigerProxy instance.
   *
   * @param tigerProxy the identifier or name of the TigerProxy to send the key to
   * @param keyName the name to assign to the key being sent
   * @param file the path to the file containing the key
   * @param algorithm the algorithm used for the key
   */
  @Dann("Sende TigerProxy {tigerResolvedString} den Key {string} aus der Datei {tigerResolvedString} mit dem Algorithmus {string}")
  @Then("Send the TigerProxy {tigerResolvedString} the key {string} from file {tigerResolvedString} with algorithm {string}")
  public void sendKeyfileTigerProxy(String tigerProxy, String keyName, String file, String algorithm) {
    Key keyFile;

    try {
      keyFile = loadPrivateKey(FileUtils.resolveExisting(file), algorithm);
    } catch (Exception e) {
      throw new AssertionError("The key file could not be loaded: " + e.getMessage());
    }

    String keyBase64 = Base64.getEncoder().encodeToString(keyFile.getEncoded());
    sendKeyToTigerProxy(tigerProxy, keyName, keyBase64, keyFile.getAlgorithm());
  }

  /**
   * Sends a key from a file with the specified algorithm to the given TigerProxy instance.
   *
   * @param tigerProxy the identifier or name of the TigerProxy to send the key to
   * @param keyName the name to assign to the key being sent
   * @param keyBase64 base64 content of the keyfile
   * @param algorithm the algorithm used for the key
   */
  @Dann("Sende TigerProxy {tigerResolvedString} den Key {string} mit dem Inhalt {tigerResolvedString} und dem Algorithmus {string}")
  @Then("Send the TigerProxy {tigerResolvedString} the key {string} with content {tigerResolvedString} and algorithm {string}")
  public void sendKeyToTigerProxy(String tigerProxy, String keyName, String keyBase64, String algorithm) {
    String url = getUrl(tigerProxy, "${paths.tigerProxy.keyPath}/" + keyName);

    try {
      RestTemplate restTemplate = new RestTemplate();
      KeyRequest keyRequest = new KeyRequest(
          algorithm,
          keyBase64);

      // Set JSON headers
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<KeyRequest> request = new HttpEntity<>(keyRequest, headers);
      ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    } catch (Exception e) {
      throw new AssertionError("The key couldn't be added to TigerProxy.");
    }
  }

  /**
   * Configures a JWT manipulation on the TigerProxy.
   *
   * @param tigerProxy URL of the TigerProxy
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param privateKeyPem private key used to re-sign the token
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für JWT in {string} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString}")
  @Then("Set in TigerProxy {tigerResolvedString} for JWT in {string} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString}")
  public void setTigerProxyJwtManipulationWithKey(String tigerProxy, String jwtLocation, String jwtField, String value,
      String privateKeyPem) {
    sendJwtManipulation(tigerProxy, Map.of(
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value,
        "privateKeyPem", privateKeyPem));
  }

  /**
   * Configures a JWT manipulation on the TigerProxy with condition and execution limit, without re-signing.
   *
   * @param tigerProxy URL of the TigerProxy
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param condition regex pattern to match request paths
   * @param executions number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für JWT in {string} das Feld {string} auf Wert {tigerResolvedString} "
      + "für Pfad {tigerResolvedString} und {int} Ausführungen")
  @Then("Set in TigerProxy {tigerResolvedString} for JWT in {string} the field {string} to value {tigerResolvedString} "
      + "for path {tigerResolvedString} with {int} executions")
  public void setTigerProxyJwtManipulationWithConditionNoResign(String tigerProxy, String jwtLocation, String jwtField,
      String value, String condition, Integer executions) {
    sendJwtManipulation(tigerProxy, Map.of(
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value,
        "condition", condition,
        "deleteAfterNExecutions", executions));
  }

  /**
   * Configures a JWT manipulation on the TigerProxy with condition and execution limit.
   *
   * @param tigerProxy URL of the TigerProxy
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param privateKeyPem private key used to re-sign the token
   * @param condition regex pattern to match request paths
   * @param executions number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für JWT in {string} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} und {int} Ausführungen")
  @Then("Set in TigerProxy {tigerResolvedString} for JWT in {string} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString} for path {tigerResolvedString} with {int} executions")
  public void setTigerProxyJwtManipulationWithCondition(String tigerProxy, String jwtLocation, String jwtField,
      String value, String privateKeyPem, String condition, Integer executions) {
    sendJwtManipulation(tigerProxy, Map.ofEntries(
        Map.entry("jwtLocation", jwtLocation),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value),
        Map.entry("privateKeyPem", privateKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions)));
  }

  /**
   * Configures a JWT manipulation on the TigerProxy with condition, execution limit, and JWK replacement.
   * The JWK in the JWT header will be replaced with the public key derived from the provided private key.
   *
   * @param tigerProxy URL of the TigerProxy
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param privateKeyPem private key used to re-sign the token and derive public key for JWK
   * @param condition regex pattern to match request paths
   * @param executions number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für JWT in {string} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} und {int} Ausführungen und ersetze JWK")
  @Then("Set in TigerProxy {tigerResolvedString} for JWT in {string} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString} for path {tigerResolvedString} with {int} executions and replace JWK")
  public void setTigerProxyJwtManipulationWithConditionAndReplaceJwk(String tigerProxy, String jwtLocation, String jwtField,
      String value, String privateKeyPem, String condition, Integer executions) {
    sendJwtManipulation(tigerProxy, Map.ofEntries(
        Map.entry("jwtLocation", jwtLocation),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value),
        Map.entry("privateKeyPem", privateKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions),
        Map.entry("replaceJwk", true)));
  }

  /**
   * Configures a single-execution JWT manipulation on the TigerProxy (executes once then auto-clears).
   *
   * @param tigerProxy URL of the TigerProxy
   * @param jwtLocation where the JWT is located
   * @param jwtField what to change in the JWT
   * @param value new value for the field
   * @param privateKeyPem private key used to re-sign the token
   */
  @Dann("Setze im TigerProxy {tigerResolvedString} für JWT in {string} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString} einmalig")
  @Then("Set in TigerProxy {tigerResolvedString} for JWT in {string} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString} once")
  public void setTigerProxyJwtManipulationOnce(String tigerProxy, String jwtLocation, String jwtField,
      String value, String privateKeyPem) {
    sendJwtManipulation(tigerProxy, Map.of(
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value,
        "privateKeyPem", privateKeyPem,
        "deleteAfterNExecutions", 1));
  }

  /**
   * Sends a JWT manipulation request to TigerProxy.
   * Central method handling all HTTP communication for JWT manipulations.
   *
   * @param tigerProxy URL of the TigerProxy
   * @param body Request body containing manipulation parameters
   */
  private void sendJwtManipulation(String tigerProxy, Map<String, Object> body) {
    String url = getUrl(tigerProxy, "${paths.tigerProxy.modifyJwtPath}");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    try {
      ResponseEntity<String> response = restTemplate.postForEntity(
          url, new HttpEntity<>(body, headers), String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    } catch (RestClientException e) {
      throw new AssertionError("JWT manipulation failed: " + e.getMessage());
    }
  }

  /**
   * Sends an RBel manipulation request to TigerProxy.
   * Central method handling all HTTP communication for RBel path manipulations.
   *
   * @param tigerProxy URL of the TigerProxy
   * @param body Request body containing manipulation parameters
   */
  private void sendRbelManipulation(String tigerProxy, Map<String, Object> body) {
    String url = getUrl(tigerProxy, "${paths.tigerProxy.modificationPath}");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    try {
      restTemplate.put(url, new HttpEntity<>(body, headers));
      ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    } catch (RestClientException e) {
      throw new AssertionError("RBel manipulation failed: " + e.getMessage());
    }
  }

  /**
   * Builds the request URL from the given tigerProxy and uri part.
   *
   * @param tigerProxy TigerProxy URL
   * @param uri        Specific endpoint that is called in the TigerProxy
   * @return The complete URL to access the TigerProxy API
   */
  private String getUrl(String tigerProxy, String uri) {
    String resolvedUri = TigerGlobalConfiguration.resolvePlaceholders(uri);
    return (tigerProxy.endsWith("/") ? tigerProxy.substring(0, tigerProxy.length() - 1) : tigerProxy) + resolvedUri;
  }


  /**
   * Loads EC private keys from multiple formats: PKCS#8 PEM, EC PEM, raw DER binary, and Base64-encoded DER.
   *
   * <p>Supported formats:</p>
   * <ul>
   *   <li>PKCS#8 PEM: {@code -----BEGIN PRIVATE KEY----- ... -----END PRIVATE KEY-----}</li>
   *   <li>EC PEM: {@code -----BEGIN EC PRIVATE KEY----- ... -----END EC PRIVATE KEY-----}</li>
   *   <li>Raw DER binary: Pure binary PKCS#8 DER bytes</li>
   *   <li>Base64 DER: Raw Base64 string without PEM headers</li>
   * </ul>
   *
   * @param path Path to the key file
   * @param algorithm Key algorithm (use "EC" for ECDSA P-256/secp256r1 keys) [web:21]
   * @return Loaded PrivateKey instance
   * @throws Exception if key loading fails (IO, parsing, or crypto errors)
   */
  private static PrivateKey loadPrivateKey(Path path, String algorithm) throws Exception {
    byte[] fileBytes = Files.readAllBytes(path);
    String content = new String(fileBytes, StandardCharsets.UTF_8).trim();

    // Try PEM format first - extract Base64 content from headers
    String base64Key = extractBase64FromPem(content);
    if (base64Key != null) {
      byte[] keyBytes = Base64.getDecoder().decode(base64Key);
      return createPrivateKey(keyBytes, algorithm);
    }

    // Fallback to raw DER binary (no PEM headers)
    return createPrivateKey(fileBytes, algorithm);
  }

  /**
   * Extracts Base64-encoded key content from PEM format.
   * Removes PEM headers/footers and normalizes whitespace.
   *
   * @param content File content as string
   * @return Base64 key content or null if not PEM format
   */
  private static String extractBase64FromPem(String content) {
    // Matches BEGIN/END KEY headers (case-insensitive, supports EC/PRIVATE variants)
    Pattern pemPattern = Pattern.compile(
        "(?s).*?-----BEGIN.*KEY-----\\s*(.+?)\\s*-----END.*KEY-----.*",
        Pattern.CASE_INSENSITIVE
    );
    var matcher = pemPattern.matcher(content);

    if (matcher.matches()) {
      // Remove all whitespace from Base64 content
      return matcher.group(1).replaceAll("[\\r\\n\\s]+", "");
    }

    return null;
  }

  /**
   * Creates PrivateKey from PKCS#8 DER-encoded bytes.
   * Uses "EC" algorithm for elliptic curve keys (curve info is in key data).
   *
   * @param keyBytes DER-encoded PKCS#8 key bytes
   * @param algorithm Expected: "EC" for P-256/secp256r1 keys [web:24]
   * @return PrivateKey instance
   * @throws Exception on parsing or algorithm errors
   */
  private static PrivateKey createPrivateKey(byte[] keyBytes, String algorithm) throws Exception {
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
    KeyFactory keyFactory = KeyFactory.getInstance(algorithm);

    return keyFactory.generatePrivate(keySpec);
  }

  /**
   * JSON Request DTO with algorithm and base64 key.
   */
  private record KeyRequest(String algorithm, String keyBase64) {}
}
