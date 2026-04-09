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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.gematik.zeta.services.TlsTestToolService;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TlsTestToolService}.
 */
class TlsTestToolServiceTest {

  private HttpServer server;
  private Path configFile;
  private Path certificateFile;
  private Path privateKeyFile;

  /**
   * Stops the embedded server and removes temporary files created during the test.
   */
  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    deleteIfExists(configFile);
    deleteIfExists(certificateFile);
    deleteIfExists(privateKeyFile);
  }

  @Test
  void callsJsonAndTextEndpointsUsingNormalizedBaseUrl() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/config", exchange -> writeJson(exchange, 200, "{\"config\":\"server.port=8443\"}"));
    server.createContext("/state", exchange -> writeJson(exchange, 200,
        "{\"running\":true,\"lastExitCode\":0,\"startedAt\":\"2026-03-13T10:15:30Z\",\"stoppedAt\":null}"));
    server.createContext("/start", exchange -> writeJson(exchange, 200,
        "{\"running\":true,\"lastExitCode\":null,\"startedAt\":\"2026-03-13T10:16:00Z\",\"stoppedAt\":null}"));
    server.createContext("/stop", exchange -> writeJson(exchange, 200,
        "{\"running\":false,\"lastExitCode\":0,\"startedAt\":\"2026-03-13T10:16:00Z\",\"stoppedAt\":\"2026-03-13T10:16:08Z\"}"));
    server.createContext("/logs", exchange -> writeText(exchange, 200, "merged logs"));
    server.start();

    var service = new TlsTestToolService(baseUrl() + "/");
    var config = service.getConfig();
    assertEquals("server.port=8443", config.config());
    var state = service.getState();
    assertTrue(state.running());
    assertEquals(Integer.valueOf(0), state.lastExitCode());
    assertEquals(Instant.parse("2026-03-13T10:15:30Z"), state.startedAt());
    assertNull(state.stoppedAt());
    var started = service.start();
    assertTrue(started.running());
    var stopped = service.stop();
    assertFalse(stopped.running());
    assertEquals(Instant.parse("2026-03-13T10:16:08Z"), stopped.stoppedAt());
    var logs = service.getLogs();
    assertEquals("merged logs", logs);
  }

  @Test
  void sendsMultipartConfigAndJsonCertificatePayloads() throws Exception {
    var requestMethod = new AtomicReference<String>();
    var configContentType = new AtomicReference<String>();
    var configBody = new AtomicReference<String>();
    var certificateContentType = new AtomicReference<String>();
    var certificateBody = new AtomicReference<String>();

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/config", exchange -> {
      requestMethod.set(exchange.getRequestMethod());
      configContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      configBody.set(readBody(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.createContext("/certificate", exchange -> {
      certificateContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      certificateBody.set(readBody(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();

    configFile = Files.createTempFile("tls-tool-config", ".conf");
    certificateFile = Files.createTempFile("tls-tool-certificate", ".pem");
    privateKeyFile = Files.createTempFile("tls-tool-key", ".pem");
    Files.writeString(configFile, "listenPort=8443\n", StandardCharsets.UTF_8);
    Files.writeString(certificateFile, "CERT_PEM", StandardCharsets.UTF_8);
    Files.writeString(privateKeyFile, "-----BEGIN PRIVATE KEY-----\nKEY_PEM\n-----END PRIVATE KEY-----\n", StandardCharsets.UTF_8);

    var service = new TlsTestToolService(baseUrl());
    service.updateConfig(configFile);
    service.updateCertificate(certificateFile, privateKeyFile);

    assertEquals("PUT", requestMethod.get());
    assertTrue(configContentType.get().startsWith("multipart/form-data"));
    assertTrue(configBody.get().contains("name=\"file\""));
    assertTrue(configBody.get().contains("filename=\"config\""));
    assertTrue(configBody.get().contains("listenPort=8443"));
    assertEquals("application/json", certificateContentType.get());
    assertTrue(certificateBody.get().contains("\"certificatePem\":\"CERT_PEM\""));
    assertTrue(certificateBody.get().contains("\"privateKeyPem\":\"-----BEGIN PRIVATE KEY-----\\nKEY_PEM\\n-----END PRIVATE KEY-----\\n\""));
  }

  /**
   * Verifies that legacy SEC1 EC private keys are normalized to PKCS#8 before upload.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void normalizesEcPrivateKeysToPkcs8BeforeUpload() throws Exception {
    var certificateBody = new AtomicReference<String>();

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/certificate", exchange -> {
      certificateBody.set(readBody(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();

    certificateFile = Files.createTempFile("tls-tool-certificate", ".pem");
    privateKeyFile = Files.createTempFile("tls-tool-key", ".pem");
    Files.writeString(certificateFile, "CERT_PEM", StandardCharsets.UTF_8);
    Files.writeString(privateKeyFile, """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEIHywVdxGg8e7PsZHQPbVR+C5iJK7szhN9EdeuZOHxfQHoAoGCCqGSM49
        AwEHoUQDQgAEjsBWlax+IxgFT1FKyaWOVY7AKW2jA1nJuQOgDUWSEXgpx3kXUOOu
        WSXFaEh6Z3eU68F3ScWDlhpYEwVCM9sb0w==
        -----END EC PRIVATE KEY-----
        """, StandardCharsets.UTF_8);

    var service = new TlsTestToolService(baseUrl());
    service.updateCertificate(certificateFile, privateKeyFile);

    assertTrue(certificateBody.get().contains("\"certificatePem\":\"CERT_PEM\""));
    assertTrue(certificateBody.get().contains("BEGIN PRIVATE KEY"));
    assertFalse(certificateBody.get().contains("BEGIN EC PRIVATE KEY"));
  }

  @Test
  void rejectsMissingConfigFile() {
    var service = new TlsTestToolService("http://localhost:8080");
    var missingConfigFile = Path.of("does-not-exist.conf");

    var exception = assertThrows(AssertionError.class, () -> service.updateConfig(missingConfigFile));

    assertTrue(exception.getMessage().contains("Invalid TLS test tool configuration file."));
  }

  @Test
  void rejectsMissingCertificateFile() throws Exception {
    var service = new TlsTestToolService("http://localhost:8080");
    privateKeyFile = Files.createTempFile("tls-tool-key", ".pem");
    Files.writeString(privateKeyFile, "KEY_PEM", StandardCharsets.UTF_8);
    var missingCertificateFile = Path.of("does-not-exist.pem");

    var exception = assertThrows(AssertionError.class,
        () -> service.updateCertificate(missingCertificateFile, privateKeyFile));

    assertTrue(exception.getMessage().contains("Invalid TLS test tool certificate input file."));
  }

  @Test
  void wrapsHttpErrorResponsesAsAssertionErrors() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/start", exchange -> writeText(exchange, 409, "already running"));
    server.start();

    var service = new TlsTestToolService(baseUrl());

    var exception = assertThrows(AssertionError.class, service::start);

    assertTrue(exception.getMessage().contains("POST"));
    assertTrue(exception.getMessage().contains("/start"));
    assertTrue(exception.getMessage().contains("409"));
  }

  @Test
  void clearsRetainedLogsViaDedicatedEndpoint() throws Exception {
    var requestMethod = new AtomicReference<String>();

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/logs/clear", exchange -> {
      requestMethod.set(exchange.getRequestMethod());
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();

    var service = new TlsTestToolService(baseUrl());

    service.clearLogs();

    assertEquals("POST", requestMethod.get());
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
   * Reads the full request body from the embedded HTTP server exchange.
   *
   * @param inputStream request body stream
   * @return decoded UTF-8 body content
   * @throws IOException if reading the stream fails
   */
  private static String readBody(InputStream inputStream) throws IOException {
    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
  }

  /**
   * Deletes a temporary file when it exists.
   *
   * @param path file path to delete
   */
  private static void deleteIfExists(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup for temporary test files.
    }
  }

  /**
   * Writes a JSON response to the embedded HTTP exchange.
   *
   * @param exchange target exchange
   * @param status HTTP status code
   * @param body response body
   * @throws IOException if writing the response fails
   */
  private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    writeText(exchange, status, body);
  }

  /**
   * Writes a plain UTF-8 response to the embedded HTTP exchange.
   *
   * @param exchange target exchange
   * @param status HTTP status code
   * @param body response body
   * @throws IOException if writing the response fails
   */
  private static void writeText(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var outputStream = exchange.getResponseBody()) {
      outputStream.write(bytes);
    }
  }
}
