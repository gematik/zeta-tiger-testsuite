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

package de.gematik.zeta.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.model.ReceivedStompMessage;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

/**
 * Manages STOMP session lifecycle, subscriptions, and message handling.
 */
@Slf4j
public class StompSessionManager {

  @Setter
  private static int connectionTimeout = 5;

  @Setter
  private static int messageTimeout = 5;
  private final WebSocketClientFactory clientFactory;

  /**
   * ObjectMapper for JSON deserialization.
   */
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Queue for storing received STOMP messages.
   */
  private final BlockingQueue<ReceivedStompMessage> messageQueue = new LinkedBlockingQueue<>();

  /**
   * Map of active subscription IDs to their destinations.
   */
  private final Map<String, String> activeSubscriptions = new ConcurrentHashMap<>();

  /**
   * The current STOMP session.
   */
  @Getter
  private StompSession session;

  /**
   * The last received STOMP message.
   */
  @Getter
  private ReceivedStompMessage lastReceivedMessage;

  /**
   * Constructs a StompSessionManager with the given client factory.
   *
   * @param clientFactory The factory for creating WebSocket clients
   */
  public StompSessionManager(WebSocketClientFactory clientFactory) {
    this.clientFactory = clientFactory;
  }

  /**
   * Connects to a WebSocket endpoint.
   *
   * @param url The WebSocket URL
   */
  public void connect(String url) {
    var sanitizedUrl = url.trim();
    validateTargetUrl(sanitizedUrl);

    log.info("Connecting to: {}", sanitizedUrl);

    // Configure SSL for trust store tweaks once per manager instance.
    SslConfigurationService.configureForTesting();

    // Create WebSocket client
    var stompClient = clientFactory.create();

    // Keep handshake state so we can block until the asynchronous connect attempt finishes.
    var connectLatch = new CountDownLatch(1);
    var connectionError = new AtomicReference<Throwable>();

    var sessionHandler = new StompSessionHandlerAdapter() {
      @Override
      public void afterConnected(StompSession session, @NotNull StompHeaders connectedHeaders) {
        log.info("STOMP CONNECTED - Session: {}", session.getSessionId());
        StompSessionManager.this.session = session;
        connectLatch.countDown();
      }

      @Override
      public void handleTransportError(@NotNull StompSession session,
          @NotNull Throwable exception) {
        log.error("Transport error during WebSocket handshake", exception);
        connectionError.compareAndSet(null, exception);
        connectLatch.countDown();
      }

      @Override
      public void handleException(@NotNull StompSession session, StompCommand command,
          @NotNull StompHeaders headers,
          byte @NotNull [] payload, @NotNull Throwable exception) {
        log.error("STOMP exception during WebSocket handshake (command={}, headers={})",
            command, headers, exception);
        connectionError.compareAndSet(null, exception);
        connectLatch.countDown();
      }
    };

    // Start the asynchronous WebSocket handshake.
    log.info("Starting WebSocket connection...");
    stompClient.connectAsync(sanitizedUrl, sessionHandler);

    // Wait for the handshake to complete or time out.
    boolean connected;
    try {
      connected = connectLatch.await(connectionTimeout, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new AssertionError("WebSocket connection failed", connectionError.get());
    }

    var error = connectionError.get();
    if (error != null) {
      throw new AssertionError(
          buildConnectionFailureMessage(sanitizedUrl, error), error);
    }

    assertThat(connected)
        .withFailMessage(
            () -> buildConnectionFailureMessage(sanitizedUrl, connectionError.get()))
        .isTrue();

    assertThat(session)
        .as("STOMP Session created")
        .isNotNull();

    assertThat(session.isConnected())
        .as("STOMP Session connected")
        .isTrue();

    log.info("WebSocket connection established");
  }

  /**
   * Validates connectivity prerequisites before attempting the WebSocket handshake.
   *
   * @param url the WebSocket endpoint URL
   */
  private void validateTargetUrl(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException ex) {
      throw new AssertionError("Invalid WebSocket URL '" + url + "': " + ex.getMessage(), ex);
    }

    var host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new AssertionError("WebSocket URL '" + url + "' does not contain a valid host");
    }

    try {
      // Fail fast when DNS cannot resolve the provided hostname.
      var ignored = InetAddress.getByName(host);
    } catch (UnknownHostException ex) {
      throw new AssertionError(
          String.format("WebSocket host '%s' cannot be resolved (URL: %s)", host, url), ex);
    }

    var port = uri.getPort();
    if (port < 0) {
      // Map WebSocket schemes to their implicit default ports.
      port = switch (uri.getScheme()) {
        case "wss", "https" -> 443;
        case "ws", "http" -> 80;
        default -> -1;
      };
    }

    if (port > 0) {
      var timeoutMillis = (int) Math.min(Duration.ofSeconds(connectionTimeout).toMillis(), 2000);
      try (var socket = new Socket()) {
        // Perform a lightweight TCP reachability probe before establishing the STOMP session.
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      } catch (UnknownHostException ex) {
        throw new AssertionError(
            String.format("WebSocket host '%s' cannot be resolved (URL: %s)", host, url), ex);
      } catch (Exception ex) {
        throw new AssertionError(
            String.format("Cannot reach WebSocket endpoint %s:%d (%s)", host, port, url), ex);
      }
    }
  }

  /**
   * Creates a descriptive assertion message for failed connection attempts.
   *
   * @param url   target URL
   * @param error optional cause gathered during the await
   * @return formatted assertion message
   */
  private String buildConnectionFailureMessage(String url, Throwable error) {
    return error == null
        ? String.format("WebSocket connection to '%s' not established within %d seconds", url,
        connectionTimeout)
        : String.format("WebSocket connection to '%s' failed within %d seconds: %s",
            url,
            connectionTimeout,
            error.getMessage());
  }

  /**
   * Subscribes to a STOMP destination.
   */
  public void subscribe(String destination, String subscriptionId) {
    log.info("Subscribing to: {} (ID: {})", destination, subscriptionId);

    assertThat(session)
        .as("STOMP Session must be connected")
        .isNotNull();

    assertThat(session.isConnected())
        .as("STOMP Session must be active")
        .isTrue();

    var headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setId(subscriptionId);

    session.subscribe(headers, new StompFrameHandler() {
      @Override
      public @NotNull Type getPayloadType(@NotNull StompHeaders headers) {
        return byte[].class;
      }

      @Override
      public void handleFrame(@NotNull StompHeaders headers, Object payload) {
        var dest = headers.getDestination();
        var deserializedPayload = deserializePayload(payload);
        log.info("RECEIVED on {}: {}", dest, deserializedPayload);

        var message = new ReceivedStompMessage(dest, deserializedPayload);
        var ignored = messageQueue.offer(message);
      }
    });

    activeSubscriptions.put(subscriptionId, destination);
    log.info("Subscription active - ID: {}", subscriptionId);
  }

  /**
   * Sends a message to a STOMP destination (DataTable format).
   */
  public void send(String destination, Map<String, String> data) {
    log.info("SEND to {}: {}", destination, data);

    assertThat(session)
        .as("STOMP Session must be connected before sending")
        .isNotNull();

    // Resolve Tiger placeholders in all values
    Map<String, String> resolvedData = new HashMap<>();
    data.forEach((key, value) -> {
      var resolvedValue = TigerGlobalConfiguration.resolvePlaceholders(value);
      resolvedData.put(key, resolvedValue);
    });

    var headers = new StompHeaders();
    headers.setDestination(destination);

    session.send(headers, resolvedData.isEmpty() ? Collections.emptyMap() : resolvedData);

    log.info("Message sent successfully");
  }

  /**
   * Sends a message with JSON payload to a STOMP destination.
   */
  public void sendJson(String destination, Map<String, Object> data) {
    log.info("SEND JSON to {}: {}", destination, data);

    assertThat(session)
        .as("STOMP Session must be connected before sending JSON")
        .isNotNull();

    var headers = new StompHeaders();
    headers.setDestination(destination);

    session.send(headers, data.isEmpty() ? Collections.emptyMap() : data);

    log.info("JSON Message sent successfully");
  }

  /**
   * Waits for a message on the specified destination.
   */
  public void awaitMessage(String expectedDestination) {
    log.info("Waiting {} seconds for message on '{}'...", messageTimeout, expectedDestination);
    log.info("Queue size: {}, Active subscriptions: {}",
        messageQueue.size(), activeSubscriptions.size());

    ReceivedStompMessage message;
    try {
      message = messageQueue.poll(messageTimeout, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for message", e);
    }

    if (message == null) {
      log.error("Timeout! No message received.");
      log.error("Expected: Destination with '{}'", expectedDestination);
      log.error("Session connected: {}", session != null && session.isConnected());
      log.error("Subscriptions: {}", activeSubscriptions);
    }

    assertThat(message)
        .as("Message received within %d seconds", messageTimeout)
        .isNotNull();

    assertThat(message.destination())
        .as("Message on expected destination")
        .contains(expectedDestination);

    lastReceivedMessage = message;

    log.info("Message received from {}: {}", message.destination(), message.payload());
  }

  /**
   * Closes the WebSocket connection.
   */
  public void close() {
    if (session != null && session.isConnected()) {
      log.info("Closing WebSocket...");

      try {
        activeSubscriptions.clear();
        session.disconnect();
        log.info("WebSocket closed successfully");
      } catch (Exception e) {
        log.warn("Error closing WebSocket: {}", e.getMessage());
      }
    } else {
      log.info("WebSocket was already closed or not connected");
    }

    messageQueue.clear();
  }

  /**
   * Deserializes byte array payload to JSON object.
   *
   * @param payload The raw payload (typically byte[])
   * @return Deserialized object (Map or List), or original payload if not byte[]
   */
  private Object deserializePayload(Object payload) {
    if (!(payload instanceof byte[])) {
      return payload;
    }

    try {
      return objectMapper.readValue((byte[]) payload, Object.class);
    } catch (Exception e) {
      log.warn("Failed to deserialize payload: {}", e.getMessage());
      return new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  /**
   * Extracts a Map from the payload for field-based validation.
   *
   * <p>Handles two JSON response formats:
   * <ul>
   *   <li>Single object → returns the Map directly</li>
   *   <li>Array of objects → returns the first Map</li>
   * </ul>
   *
   * @param payload The JSON payload (Map or List)
   * @return Map for field access
   * @throws AssertionError if payload is null, not a Map/List, List is empty, or List elements are
   *                        not Maps
   */
  public Map<String, Object> extractFirstObjectFromPayload(Object payload) {
    switch (payload) {
      case null -> throw new AssertionError("Payload is null");
      case Map<?, ?> ignored -> {
        return castToMap(payload);
      }
      case List<?> list -> {
        assertThat(list)
            .as("List is not empty for object extraction")
            .isNotEmpty();

        var firstElement = list.getFirst();
        return castToMap(firstElement);
      }
      default -> {
      }
    }

    throw new AssertionError(
        String.format("Payload must be Map or List, but was: %s",
            payload.getClass().getName()));
  }

  /**
   * Safely casts object to Map with validation.
   *
   * @param obj The object to cast
   * @return The object cast to Map&lt;String, Object&gt;
   * @throws AssertionError if object is null or not a Map
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> castToMap(Object obj) {
    if (obj == null) {
      throw new AssertionError("Cannot cast null to Map");
    }

    if (!(obj instanceof Map)) {
      throw new AssertionError(
          String.format("Expected Map but got: %s", obj.getClass().getName()));
    }

    return (Map<String, Object>) obj;
  }
}
