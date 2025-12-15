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

import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.client.SslEngineConfigurator;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Factory for creating configured WebSocket STOMP clients. Uses native WebSocket connections with
 * Tyrus (JSR 356 reference implementation).
 */
@Slf4j
public class WebSocketClientFactory {

  // Configuration constants
  private static final int TASK_SCHEDULER_POOL_SIZE = 10;
  private static final long[] HEARTBEAT_MS = {30000, 30000};

  /**
   * Creates a configured WebSocketStompClient with native WebSocket support.
   *
   * @return Configured WebSocketStompClient
   * @throws IllegalStateException if SSL context creation fails
   */
  public WebSocketStompClient create() {
    try {
      SSLContext sslContext = SslConfigurationService.getTrustAllSslContext();

      log.info("Creating native WebSocket client with SSL");

      ClientManager clientManager = ClientManager.createClient();

      SslEngineConfigurator sslEngineConfigurator = new SslEngineConfigurator(sslContext);
      sslEngineConfigurator.setHostnameVerifier((hostname, session) -> true);

      clientManager.getProperties().put(
          ClientProperties.SSL_ENGINE_CONFIGURATOR,
          sslEngineConfigurator);

      StandardWebSocketClient webSocketClient = new StandardWebSocketClient(clientManager);

      return configureStompClient(webSocketClient);
    } catch (Exception e) {
      log.error("Failed to create WebSocket client", e);
      throw new AssertionError("Failed to create WebSocket client: " + e.getMessage(), e);
    }
  }

  /**
   * Configures a WebSocketStompClient with common settings.
   */
  private WebSocketStompClient configureStompClient(StandardWebSocketClient wsClient) {

    WebSocketStompClient stompClient = new WebSocketStompClient(wsClient);
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());

    // Task Scheduler for Heartbeats
    ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    taskScheduler.setPoolSize(TASK_SCHEDULER_POOL_SIZE);
    taskScheduler.setThreadNamePrefix("stomp-");
    taskScheduler.afterPropertiesSet();
    stompClient.setTaskScheduler(taskScheduler);

    // Heartbeat settings
    stompClient.setDefaultHeartbeat(HEARTBEAT_MS);

    log.info("WebSocket STOMP client configured");
    return stompClient;
  }
}
