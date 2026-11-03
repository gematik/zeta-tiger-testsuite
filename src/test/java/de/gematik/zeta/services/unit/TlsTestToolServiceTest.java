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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.gematik.zeta.services.TlsTestToolService;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TlsTestToolService}.
 */
class TlsTestToolServiceTest {

  private HttpServer server;
  private ExecutorService serverExecutor;
  private Path configFile;
  private Path caCertificateFile;
  private Path certificateFile;
  private Path privateKeyFile;

  @Test
  void resetRequiresCleanIdleStateAndPropagatesUnsupportedEndpoint() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    var response = new AtomicReference<>("{\"running\":false,\"lastExitCode\":null,\"startedAt\":null,\"stoppedAt\":null}");
    var status = new AtomicInteger(200);
    server.createContext("/reset", exchange -> {
      assertEquals("POST", exchange.getRequestMethod());
      writeJson(exchange, status.get(), response.get());
    });
    server.start();
    var service = new TlsTestToolService(baseUrl());
    assertFalse(service.reset().running());
    for (var dirty : java.util.List.of(
        "{}", "{\"running\":false}", "{\"running\":true}", "{\"running\":false,\"lastExitCode\":0}",
        "{\"running\":false,\"startedAt\":\"2026-03-13T10:15:30Z\"}")) {
      response.set(dirty);
      assertThrows(AssertionError.class, service::reset);
    }
    status.set(404);
    assertThrows(AssertionError.class, service::reset);
  }

  @Test
  void explicitlySelectsCertificateUseForClientRuns() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    var queries = new java.util.ArrayList<String>();
    server.createContext(
        "/startAsTlsClient",
        exchange -> {
          queries.add(exchange.getRequestURI().getRawQuery());
          writeJson(exchange, 200, "{\"running\":true}");
        });
    server.start();
    var service = new TlsTestToolService(baseUrl());
    assertTrue(service.startAsTlsClient(true).running());
    assertEquals(java.util.List.of("useClientCertificate=true"), queries);
  }


  /**
   * Stops the embedded server and removes temporary files created during the test.
   */
  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (serverExecutor != null) {
      serverExecutor.shutdownNow();
    }
    deleteIfExists(configFile);
    deleteIfExists(caCertificateFile);
    deleteIfExists(certificateFile);
    deleteIfExists(privateKeyFile);
  }

  @Test
  void callsJsonAndTextEndpointsUsingNormalizedBaseUrl() throws Exception {
    var stateRequestCount = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/config", exchange -> writeJson(exchange, 200, "{\"config\":\"server.port=8443\"}"));
    server.createContext("/state", exchange -> {
      var running = stateRequestCount.getAndIncrement() == 0;
      writeJson(exchange, 200, running
          ? "{\"running\":true,\"lastExitCode\":0,\"startedAt\":\"2026-03-13T10:15:30Z\",\"stoppedAt\":null}"
          : "{\"running\":false,\"lastExitCode\":0,\"startedAt\":\"2026-03-13T10:15:30Z\","
              + "\"stoppedAt\":\"2026-03-13T10:16:08Z\"}");
    });
    server.createContext("/startAsTlsServer", exchange -> writeJson(exchange, 200,
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
    var started = service.startAsTlsServer();
    assertTrue(started.running());
    var stopped = service.stop();
    assertFalse(stopped.running());
    assertEquals(Instant.parse("2026-03-13T10:16:08Z"), stopped.stoppedAt());
    var logs = service.getLogs();
    assertEquals("merged logs", logs);
  }

  /**
   * Verifies that log retrieval waits for process completion so the logs endpoint cannot stop an
   * in-flight TLS handshake.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void waitsForProcessCompletionBeforeReadingLogs() throws Exception {
    final var stateRequestCount = new AtomicInteger();
    final var stateRequestsObservedByLogsEndpoint = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/state", exchange -> {
      var requestNumber = stateRequestCount.incrementAndGet();
      var running = requestNumber < 3;
      writeJson(exchange, 200, running
          ? "{\"running\":true,\"lastExitCode\":null,\"startedAt\":\"2026-03-13T10:16:00Z\",\"stoppedAt\":null}"
          : "{\"running\":false,\"lastExitCode\":0,\"startedAt\":\"2026-03-13T10:16:00Z\","
              + "\"stoppedAt\":\"2026-03-13T10:16:08Z\"}");
    });
    server.createContext("/logs", exchange -> {
      stateRequestsObservedByLogsEndpoint.set(stateRequestCount.get());
      writeText(exchange, 200, "complete logs");
    });
    server.start();

    var logs = new TlsTestToolService(baseUrl()).getLogs();

    assertEquals("complete logs", logs);
    assertEquals(3, stateRequestsObservedByLogsEndpoint.get());
  }

  /**
   * Verifies that a tool completing after its receive and close waits can still provide final logs.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void waitsBeyondTenSecondsForFinalLogs() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    var completedAt = System.nanoTime() + Duration.ofSeconds(11).toNanos();
    server.createContext("/state", exchange -> writeJson(exchange, 200,
        "{\"running\":" + (System.nanoTime() < completedAt) + "}"));
    server.createContext("/logs", exchange -> {
      assertTrue(System.nanoTime() >= completedAt);
      writeText(exchange, 200, "complete logs");
    });
    server.start();

    var logs = assertTimeout(Duration.ofSeconds(20), () -> new TlsTestToolService(baseUrl()).getLogs());

    assertEquals("complete logs", logs);
  }

  /**
   * Verifies that log retrieval fails after the completion timeout and does not call the logs
   * endpoint while the TLS test tool process is still running.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void rejectsLogRetrievalWhenProcessDoesNotCompleteBeforeTimeout() throws Exception {
    final var logsRequestCount = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext("/state", exchange -> {
      pause(Duration.ofSeconds(3));
      writeJson(exchange, 200,
          "{\"running\":true,\"lastExitCode\":null,\"startedAt\":\"2026-03-13T10:16:00Z\",\"stoppedAt\":null}");
    });
    server.createContext("/logs", exchange -> {
      logsRequestCount.incrementAndGet();
      writeText(exchange, 200, "incomplete logs");
    });
    server.start();

    var service = new TlsTestToolService(baseUrl()) {
      /**
       * Uses a short timeout to exercise the timeout branch deterministically.
       *
       * @return short process completion timeout
       */
      @Override
      protected Duration getProcessCompletionTimeout() {
        return Duration.ofMillis(500);
      }
    };

    var exception = assertTimeout(Duration.ofSeconds(3),
        () -> assertThrows(AssertionError.class, service::getLogs));

    assertTrue(exception.getMessage().contains("did not complete within 500 milliseconds"));
    assertEquals(0, logsRequestCount.get());
  }

  /**
   * Verifies that a transient state read timeout is retried while process completion time remains.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void retriesTransientStateFailureBeforeCompletionTimeout() throws Exception {
    final var stateRequestCount = new AtomicInteger();
    final var stateRequestsObservedByLogsEndpoint = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext("/state", exchange -> {
      if (stateRequestCount.incrementAndGet() == 1) {
        pause(Duration.ofSeconds(4));
      }
      writeJson(exchange, 200,
          "{\"running\":false,\"lastExitCode\":0,\"startedAt\":\"2026-03-13T10:16:00Z\","
              + "\"stoppedAt\":\"2026-03-13T10:16:08Z\"}");
    });
    server.createContext("/logs", exchange -> {
      stateRequestsObservedByLogsEndpoint.set(stateRequestCount.get());
      writeText(exchange, 200, "complete logs");
    });
    server.start();

    var service = new TlsTestToolService(baseUrl()) {
      /**
       * Keeps the test short while leaving enough time for a retry after the first read timeout.
       * The first request times out after approximately 2.5 seconds, well before the simulated
       * 4-second response delay. The remaining budget covers the inter-poll pause and second round
       * trip with sufficient CI scheduling tolerance.
       *
       * @return process completion timeout for the retry test
       */
      @Override
      protected Duration getProcessCompletionTimeout() {
        return Duration.ofSeconds(5);
      }
    };

    var logs = assertTimeout(Duration.ofSeconds(10), service::getLogs);

    assertEquals("complete logs", logs);
    assertTrue(stateRequestsObservedByLogsEndpoint.get() >= 2);
  }

  /**
   * Verifies that a temporary server error from the state endpoint is retried before reading logs.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void retriesTransientStateServerErrorBeforeReadingLogs() throws Exception {
    final var stateRequestCount = new AtomicInteger();
    final var stateRequestsObservedByLogsEndpoint = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/state", exchange -> {
      if (stateRequestCount.incrementAndGet() == 1) {
        writeJson(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
        return;
      }
      writeJson(exchange, 200,
          "{\"running\":false,\"lastExitCode\":0,\"startedAt\":\"2026-03-13T10:16:00Z\","
              + "\"stoppedAt\":\"2026-03-13T10:16:08Z\"}");
    });
    server.createContext("/logs", exchange -> {
      stateRequestsObservedByLogsEndpoint.set(stateRequestCount.get());
      writeText(exchange, 200, "complete logs");
    });
    server.start();

    var logs = new TlsTestToolService(baseUrl()).getLogs();

    assertEquals("complete logs", logs);
    assertEquals(2, stateRequestsObservedByLogsEndpoint.get());
  }

  /**
   * Verifies that a duplicated start conflict is treated as success when the service reports a
   * running process.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void acceptsDuplicatedStartWhenProcessIsRunning() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/startAsTlsServer", exchange -> writeJson(exchange, 409, "{}"));
    server.createContext("/state", exchange -> writeJson(exchange, 200,
        "{\"running\":true,\"lastExitCode\":null,\"startedAt\":\"2026-03-13T10:16:00Z\",\"stoppedAt\":null}"));
    server.start();

    var state = new TlsTestToolService(baseUrl()).startAsTlsServer();

    assertTrue(state.running());
    assertEquals(Instant.parse("2026-03-13T10:16:00Z"), state.startedAt());
  }

  /**
   * Verifies that a start conflict remains a failure when no process is running.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void rejectsStartConflictWhenProcessIsNotRunning() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/startAsTlsClient", exchange -> writeJson(exchange, 409, "{}"));
    server.createContext("/state", exchange -> writeJson(exchange, 200,
        "{\"running\":false,\"lastExitCode\":0,\"startedAt\":null,\"stoppedAt\":null}"));
    server.start();

    var service = new TlsTestToolService(baseUrl());

    assertThrows(AssertionError.class, service::startAsTlsClient);
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

    var caCertificateContentType = new AtomicReference<String>();
    var caCertificateBody = new AtomicReference<String>();
    server.createContext("/caCertificate", exchange -> {
      caCertificateContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      caCertificateBody.set(readBody(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    var ocspContentType = new AtomicReference<String>();
    var ocspBody = new AtomicReference<byte[]>();
    server.createContext("/ocspResponse", exchange -> {
      ocspContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      ocspBody.set(exchange.getRequestBody().readAllBytes());
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();

    configFile = Files.createTempFile("tls-tool-config", ".conf");
    caCertificateFile = Files.createTempFile("tls-tool-ca-certificate", ".pem");
    certificateFile = Files.createTempFile("tls-tool-certificate", ".pem");
    privateKeyFile = Files.createTempFile("tls-tool-key", ".pem");
    Files.writeString(configFile, "listenPort=8443\n", StandardCharsets.UTF_8);
    Files.writeString(caCertificateFile, "CA_CERT_PEM", StandardCharsets.UTF_8);
    Files.writeString(certificateFile, "CERT_PEM", StandardCharsets.UTF_8);
    Files.writeString(privateKeyFile, "-----BEGIN PRIVATE KEY-----\nKEY_PEM\n-----END PRIVATE KEY-----\n", StandardCharsets.UTF_8);

    var service = new TlsTestToolService(baseUrl());
    service.updateConfig(configFile);
    service.updateCertificate(certificateFile, privateKeyFile);
    service.updateCaCertificate(caCertificateFile);
    service.updateOcspResponse(new byte[] {0x30, 0x01, 0x02});

    assertEquals("PUT", requestMethod.get());
    assertTrue(configContentType.get().startsWith("multipart/form-data"));
    assertTrue(configBody.get().contains("name=\"file\""));
    assertTrue(configBody.get().contains("filename=\"config\""));
    assertTrue(configBody.get().contains("listenPort=8443"));
    assertEquals("application/json", certificateContentType.get());
    assertTrue(certificateBody.get().contains("\"certificatePem\":\"CERT_PEM\""));
    assertTrue(certificateBody.get().contains("\"privateKeyPem\":\"-----BEGIN PRIVATE KEY-----\\nKEY_PEM\\n-----END PRIVATE KEY-----\\n\""));
    assertEquals("application/json", caCertificateContentType.get());
    assertTrue(caCertificateBody.get().contains("\"caCertificatePem\":\"CA_CERT_PEM\""));
    assertEquals("application/ocsp-response", ocspContentType.get());
    assertArrayEquals(new byte[] {0x30, 0x01, 0x02}, ocspBody.get());
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
    server.createContext("/startAsTlsServer", exchange -> writeText(exchange, 409, "already running"));
    server.start();

    var service = new TlsTestToolService(baseUrl());

    var exception = assertThrows(AssertionError.class, service::startAsTlsServer);

    assertTrue(exception.getMessage().contains("POST"));
    assertTrue(exception.getMessage().contains("/startAsTlsServer"));
    assertTrue(exception.getMessage().contains("409"));
  }

  @Test
  void startsTlsToolInClientModeViaDedicatedEndpoint() throws Exception {
    var requestMethod = new AtomicReference<String>();

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/startAsTlsClient", exchange -> {
      requestMethod.set(exchange.getRequestMethod());
      writeJson(exchange, 200,
          "{\"running\":true,\"lastExitCode\":null,\"startedAt\":\"2026-03-13T10:16:00Z\",\"stoppedAt\":null}");
    });
    server.start();

    var service = new TlsTestToolService(baseUrl());

    var started = service.startAsTlsClient();

    assertEquals("POST", requestMethod.get());
    assertTrue(started.running());
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
   * Pauses an embedded HTTP handler to simulate a slow service response.
   *
   * @param duration pause duration
   */
  private static void pause(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
