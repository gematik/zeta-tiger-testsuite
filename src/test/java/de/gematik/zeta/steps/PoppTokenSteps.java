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
import de.gematik.test.tiger.lib.TigerHttpClient;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import io.restassured.http.ContentType;
import io.restassured.http.Method;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cucumber step definitions for PoPP token generation.
 */
@Slf4j
public class PoppTokenSteps {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TOKEN_GENERATOR_URL_CONFIG_KEY = "paths.popp.tokenGenerator";
  private static final String POPP_PROOF_METHOD_CONFIG_KEY = "testdata.popp.proofMethod";
  private static final String POPP_PATIENT_ID_CONFIG_KEY = "testdata.popp.patientId";
  private static final String POPP_INSURER_ID_CONFIG_KEY = "testdata.popp.insurerId";
  private static final String POPP_ACTOR_PROFESSION_OID_CONFIG_KEY = "testdata.popp.actorProfessionOid";

  /**
   * Generates a fresh PoPP token for the given actor data and stores it in a Tiger variable.
   *
   * @param actorId Telematik-ID from the SMC-B certificate
   * @param professionId profession OID from the SMC-B certificate
   * @param varName Tiger variable receiving the generated token
   */
  @Und("hole frisches PoPP Token aus dem Generator für Akteur {tigerResolvedString} und Profession {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @And("fetch fresh PoPP token from generator for actor {tigerResolvedString} and profession {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void fetchFreshPoppToken(String actorId, String professionId, String varName) {
    var tokenGeneratorUrl = readRequiredConfig(TOKEN_GENERATOR_URL_CONFIG_KEY);
    var token = generatePoppToken(tokenGeneratorUrl, actorId, professionId);

    TigerGlobalConfiguration.putValue(varName, token, ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Stored generated PoPP token for actor '{}' in variable '{}'", actorId, varName);
  }

  /**
   * Generates a PoPP token using configured static test values and certificate-derived actor data.
   *
   * @param tokenGeneratorUrl token generator endpoint
   * @param actorId Telematik-ID from the SMC-B certificate
   * @param professionId profession OID from the SMC-B certificate, or {@code null}
   * @return generated compact PoPP JWT
   */
  private String generatePoppToken(String tokenGeneratorUrl, String actorId, String professionId) {
    var tokenParams = new LinkedHashMap<String, String>();
    tokenParams.put("proofMethod", readRequiredConfig(POPP_PROOF_METHOD_CONFIG_KEY));
    tokenParams.put("patientId", readRequiredConfig(POPP_PATIENT_ID_CONFIG_KEY));
    tokenParams.put("insurerId", readRequiredConfig(POPP_INSURER_ID_CONFIG_KEY));
    tokenParams.put("actorId", requireNonBlank(actorId, "SMC-B Telematik-ID"));
    tokenParams.put("actorProfessionOid", professionId == null
        ? readRequiredConfig(POPP_ACTOR_PROFESSION_OID_CONFIG_KEY)
        : requireNonBlank(professionId, "SMC-B profession OID"));

    var requestBody = writeJson(Map.of("tokenParamsList", List.of(tokenParams)));
    var response = TigerHttpClient.givenDefaultSpec()
        .accept("*/*")
        .contentType(ContentType.JSON)
        .body(requestBody)
        .request(Method.POST, uri(tokenGeneratorUrl));

    assertSuccessful(response, "PoPP token generator");
    var root = readJson(response, "PoPP token generator");
    var token = root.path("tokenResults").path(0).asText(null);
    return requireNonBlank(token, "PoPP token generator response tokenResults[0]");
  }

  /**
   * Parses a RestAssured response body as JSON.
   *
   * @param response HTTP response
   * @param label label for assertion messages
   * @return parsed response JSON
   */
  private JsonNode readJson(Response response, String label) {
    try {
      return JSON.readTree(new String(response.body().asByteArray(), StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      throw new AssertionError(label + " did not return valid JSON: " + e.getMessage(), e);
    }
  }

  /**
   * Asserts that a response status code is in the 2xx range.
   *
   * @param response HTTP response
   * @param label label for assertion messages
   */
  private void assertSuccessful(Response response, String label) {
    assertThat(response.statusCode())
        .as(label + " HTTP status")
        .isBetween(200, 299);
  }

  /**
   * Reads and resolves one required Tiger configuration value.
   *
   * @param key configuration key
   * @return resolved configuration value
   */
  private String readRequiredConfig(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .map(this::requireNonBlankConfig)
        .orElseThrow(() -> new AssertionError("Missing Tiger configuration: " + key));
  }

  /**
   * Validates a resolved configuration value.
   *
   * @param value resolved configuration value
   * @return trimmed value
   */
  private String requireNonBlankConfig(String value) {
    return requireNonBlank(value, "Tiger configuration value");
  }

  /**
   * Parses one URI from text.
   *
   * @param value URI text
   * @return parsed URI
   */
  private URI uri(String value) {
    try {
      return new URI(value);
    } catch (URISyntaxException e) {
      throw new AssertionError("Invalid URI: " + value, e);
    }
  }

  /**
   * Serializes a Java object to JSON.
   *
   * @param value object to serialize
   * @return JSON text
   */
  private String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new AssertionError("Failed to serialize PoPP token request.", e);
    }
  }

  /**
   * Converts a blank string to {@code null}.
   *
   * @param value string value
   * @return trimmed string or {@code null}
   */
  private String trimToNull(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  /**
   * Validates and trims a required string.
   *
   * @param value string value
   * @param label label for assertion messages
   * @return trimmed string
   */
  private String requireNonBlank(String value, String label) {
    var trimmed = trimToNull(value);
    if (trimmed == null) {
      throw new AssertionError(label + " must not be blank.");
    }
    return trimmed;
  }
}
