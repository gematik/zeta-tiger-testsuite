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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.StompSessionManager;
import de.gematik.zeta.services.WebSocketClientFactory;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Collections;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber Steps for WebSocket/STOMP tests. Delegates to services for WebSocket management.
 */
@Slf4j
public class WebSocketStompSteps {

  /**
   * Shared ObjectMapper instance for JSON operations.
   */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  /**
   * Manages STOMP session lifecycle and message handling.
   */
  private final StompSessionManager sessionManager;

  /**
   * Constructs WebSocketStompSteps with default client factory.
   */
  public WebSocketStompSteps() {
    var clientFactory = new WebSocketClientFactory();
    this.sessionManager = new StompSessionManager(clientFactory);
  }

  /**
   * Sets the connection timeout for websocket connections.
   *
   * @param seconds the new timeout in seconds
   */
  @Gegebensei("setze Anfrage Timeout für WebSocket Verbindungen auf {int} Sekunden")
  @Given("set connection timeout for websocket connections to {int} seconds")
  public void setConnectionTimeout(int seconds) {
    StompSessionManager.setConnectionTimeout(seconds);
    log.info("Connection timeout for websocket connections set to {} seconds.", seconds);
  }

  /**
   * Sets the message timeout for websocket messages.
   *
   * @param seconds the new timeout in seconds
   */
  @Gegebensei("setze Timeout für WebSocket Nachrichten auf {int} Sekunden")
  @Given("set message timeout for websocket messages to {int} seconds")
  public void setMessageTimeout(int seconds) {
    StompSessionManager.setMessageTimeout(seconds);
    log.info("Message timeout for websocket connections set to {} seconds.", seconds);
  }

  /**
   * Opens a WebSocket connection.
   *
   * @param url The WebSocket URL to connect to
   */
  @Wenn("eine WebSocket Verbindung zu {tigerResolvedString} geöffnet wird")
  @When("a WebSocket to {tigerResolvedString} is opened")
  public void openWebSocket(String url) {
    // Resolve Tiger placeholders (e.g. environment endpoints) before connecting.
    sessionManager.connect(url);
    log.info("Websocket connection to {} established.", url);
  }

  /**
   * Subscribes to a STOMP channel.
   *
   * @param channel        The STOMP channel to subscribe to
   * @param subscriptionId The subscription ID
   */
  @Wenn("der Kanal {tigerResolvedString} mit ID {tigerResolvedString} abonniert wird")
  @When("the channel {tigerResolvedString} with ID {tigerResolvedString} is subscribed")
  public void subscribe(String channel, String subscriptionId) {
    sessionManager.subscribe(channel, subscriptionId);
    log.info("Subscribed to channel {} with subscriptionID {}", channel, subscriptionId);
  }

  /**
   * Sends an empty request to a STOMP channel.
   *
   * @param channel The STOMP channel to send to
   */
  @Wenn("eine leere Anfrage an Kanal {tigerResolvedString} gesendet wird")
  @When("an empty request is sent to channel {tigerResolvedString}")
  public void sendRequest(String channel) {
    sendRequestWithData(channel, Collections.emptyMap());
    log.info("Sent empty request to channel {}", channel);
  }

  /**
   * Sends a request with data to a STOMP channel (using DataTable).
   *
   * @param channel     The STOMP channel to send to
   * @param requestData The request data from DataTable
   */
  @Wenn("eine Anfrage mit folgenden mehrzeiligen Daten an Kanal {tigerResolvedString} gesendet wird:")
  @When("a request with following multi-line data is sent to channel {tigerResolvedString}:")
  public void sendRequestWithData(String channel, Map<String, String> requestData) {
    var resolvedChannel = TigerGlobalConfiguration.resolvePlaceholders(channel);
    // Allow tables to carry unresolved placeholders; the manager resolves each entry on send.
    sessionManager.send(resolvedChannel, requestData);
    log.info("Sent request with data {} to channel {}", requestData, channel);
  }

  /**
   * Sends a request with JSON payload to a STOMP channel.
   *
   * @param channel     The STOMP channel to send to
   * @param jsonPayload The JSON payload as string
   */
  @Wenn("Anfrage an Kanal {tigerResolvedString} mit folgenden JSON Daten gesendet wird:")
  @When("request is sent to channel {tigerResolvedString} with following JSON data:")
  public void sendRequestWithJson(String channel, String jsonPayload) {
    var resolvedChannel = TigerGlobalConfiguration.resolvePlaceholders(channel);
    var resolvedJson = TigerGlobalConfiguration.resolvePlaceholders(jsonPayload);

    try {
      Map<String, Object> payloadMap = OBJECT_MAPPER.readValue(resolvedJson,
          new TypeReference<>() {
          });
      sessionManager.sendJson(resolvedChannel, payloadMap);
    } catch (JsonProcessingException e) {
      // For invalid JSON (negative testing), send raw string
      log.info("JSON parsing failed (expected for negative tests), sending raw string");
      sessionManager.sendJson(resolvedChannel, Map.of("raw", resolvedJson));
    } catch (Exception e) {
      throw new AssertionError(
          "Failed to send JSON to channel '" + resolvedChannel + "': " + e.getMessage(), e);
    }
    log.info("Sent JSON request to {}: {}", resolvedChannel, resolvedJson);
  }

  /**
   * Waits for a message on the specified channel.
   *
   * @param expectedChannel The expected channel
   */
  @Dann("wird eine Nachricht auf dem Kanal {tigerResolvedString} empfangen")
  @Then("a message on channel {tigerResolvedString} is received")
  public void awaitMessage(String expectedChannel) {
    // Delegate blocking wait to the session manager which handles queueing and assertions.
    sessionManager.awaitMessage(expectedChannel);
  }

  /**
   * Saves the received ID to a Tiger variable (legacy step - kept for backwards compatibility).
   *
   * @param variableName The Tiger variable name to store the ID
   */
  @Dann("wird die empfangene ID in der Variable {string} gespeichert")
  @Then("received ID is stored in variable {string}")
  @SuppressWarnings("Unused")
  public void saveReceivedId(String variableName) {
    saveReceivedValue("$.id", variableName);
  }

  /**
   * Saves a value from the received message using JSON path (like TGR step).
   *
   * @param jsonPath     The JSON path (e.g., "$.id")
   * @param variableName The Tiger variable name to store the value
   */
  @Dann("wird der Wert des Knotens {string} der empfangenen Nachricht in der Variable {string} gespeichert")
  @Then("value of node {string} from received message is stored in variable {string}")
  public void saveReceivedValue(String jsonPath, String variableName) {
    var lastMessage = sessionManager.getLastReceivedMessage();

    assertThat(lastMessage)
        .as("Last message available for value extraction")
        .isNotNull();

    var objectMap = sessionManager.extractFirstObjectFromPayload(
        lastMessage.payload());

    // Simple JSON path implementation - supports $.fieldName syntax
    var path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;

    var value = objectMap.get(path);
    assertThat(value)
        .as("Field '%s' present in payload", path)
        .isNotNull();

    var valueAsString = String.valueOf(value);
    TigerGlobalConfiguration.putValue(variableName, valueAsString);

    log.info("Value saved from '{}': {} = {}", jsonPath, variableName, valueAsString);
  }

  /**
   * Verifies a field in the last received message.
   *
   * @param fieldName     The field name to check
   * @param expectedValue The expected value (supports Tiger placeholders)
   */
  @Dann("hat die empfangene Nachricht im Feld {string} den Wert {tigerResolvedString}")
  @Then("the received message has field {string} with value {tigerResolvedString}")
  public void verifyMessageField(String fieldName, String expectedValue) {
    var lastMessage = sessionManager.getLastReceivedMessage();

    assertThat(lastMessage)
        .as("Last message available for field validation")
        .isNotNull();

    var objectMap = sessionManager.extractFirstObjectFromPayload(
        lastMessage.payload());

    var fieldValue = objectMap.get(fieldName);
    var actualValue = fieldValue != null ? String.valueOf(fieldValue) : null;
    var resolvedExpectedValue = TigerGlobalConfiguration.resolvePlaceholders(expectedValue);

    assertThat(actualValue)
        .as("Field '%s' has expected value", fieldName)
        .isEqualTo(resolvedExpectedValue);

    log.info("Validation successful: {} = {}", fieldName, actualValue);
  }

  /**
   * Verifies the last received WebSocket message matches expected JSON (similar to TGR step). Only
   * checks fields that are present in expected JSON (partial match).
   *
   * @param expectedJson The expected JSON string (supports Tiger placeholders)
   */
  @Dann("stimmt empfangene Nachricht als JSON überein mit:")
  @Then("received message matches following JSON:")
  public void verifyMessageMatchesJson(String expectedJson) {
    var lastMessage = sessionManager.getLastReceivedMessage();

    assertThat(lastMessage)
        .as("Last message available for JSON validation")
        .isNotNull();

    var actualMap = sessionManager.extractFirstObjectFromPayload(
        lastMessage.payload());

    // Resolve Tiger placeholders in expected JSON
    var resolvedExpectedJson = TigerGlobalConfiguration.resolvePlaceholders(expectedJson);

    // Parse expected JSON to Map
    Map<String, Object> expectedMap;
    try {
      expectedMap = OBJECT_MAPPER.readValue(resolvedExpectedJson,
          new TypeReference<>() {
          });
    } catch (Exception e) {
      throw new AssertionError("Failed to parse expected JSON: " + e.getMessage(), e);
    }

    // Compare each field from expected JSON (partial match like TGR)
    // Only assert on keys provided by the feature to keep steps flexible.
    for (var entry : expectedMap.entrySet()) {
      var key = entry.getKey();
      var expectedValue = entry.getValue();
      var actualValue = actualMap.get(key);

      assertThat(actualValue)
          .as("Field '%s' has expected value", key)
          .isEqualTo(expectedValue);
    }

    log.info("JSON validation successful - all {} expected fields match", expectedMap.size());
  }

  /**
   * Closes the WebSocket connection.
   */
  @Dann("wird die WebSocket Verbindung geschlossen")
  @Then("WebSocket connection is closed")
  public void closeWebSocket() {
    sessionManager.close();
  }

}
