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

package de.gematik.zeta.services.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.gematik.zeta.services.TestDriverConfigurationService;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestDriverConfigurationService}.
 */
class TestDriverConfigurationServiceTest {

  private HttpServer server;

  /**
   * Stops the embedded HTTP server after each test.
   */
  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * Verifies that reset and configure are sent to the expected endpoints.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void resetAndConfigureCallExpectedEndpoints() throws Exception {
    var resetMethod = new AtomicReference<String>();
    var configureMethod = new AtomicReference<String>();
    var configureContentType = new AtomicReference<String>();
    var configureBody = new AtomicReference<String>();

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/testdriver-api/reset", exchange -> {
      resetMethod.set(exchange.getRequestMethod());
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.createContext("/testdriver-api/configure", exchange -> {
      configureMethod.set(exchange.getRequestMethod());
      configureContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      configureBody.set(readBody(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();

    var service = new TestDriverConfigurationService(
        baseUrl() + "/testdriver-api/reset",
        baseUrl() + "/testdriver-api/configure");

    service.reset();
    service.configure("https://tls-test-tool.example.local:8443", "-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----");

    assertEquals("GET", resetMethod.get());
    assertEquals("POST", configureMethod.get());
    assertEquals("application/json", configureContentType.get());
    assertTrue(configureBody.get().contains("\"resource\":\"https://tls-test-tool.example.local:8443\""));
    assertTrue(configureBody.get().contains("\"caCertificatePem\":\"-----BEGIN CERTIFICATE-----\\nMIID...\\n-----END CERTIFICATE-----\""));
  }

  /**
   * Verifies that HTTP errors are surfaced as assertion failures.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void wrapsHttpErrorsAsAssertionErrors() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/testdriver-api/configure", exchange -> {
      var body = "bad request";
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(400, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();

    var service = new TestDriverConfigurationService(
        baseUrl() + "/testdriver-api/reset",
        baseUrl() + "/testdriver-api/configure");

    var exception = assertThrows(AssertionError.class,
        () -> service.configure("https://tls-test-tool.example.local:8443", "CERT"));

    assertTrue(exception.getMessage().contains("POST"));
    assertTrue(exception.getMessage().contains("/testdriver-api/configure"));
    assertTrue(exception.getMessage().contains("400"));
  }

  /**
   * Returns the base URL of the embedded HTTP server.
   *
   * @return embedded service base URL
   */
  private String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  /**
   * Reads the full request body.
   *
   * @param inputStream request body stream
   * @return decoded UTF-8 request body
   * @throws IOException if reading the stream fails
   */
  private static String readBody(InputStream inputStream) throws IOException {
    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
  }
}
