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

package de.gematik.zeta.services;

import de.gematik.zeta.services.model.TlsCaCertificateUploadPayload;
import de.gematik.zeta.services.model.TlsCertificateUploadPayload;
import de.gematik.zeta.services.model.TlsToolConfigResponse;
import de.gematik.zeta.services.model.TlsToolStateResponse;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client for the zeta-tls-test-tool-service admin API.
 */
public class TlsTestToolService {

  private static final String CONFIG_MULTIPART_FILENAME = "config";
  private static final Duration PROCESS_COMPLETION_POLL_INTERVAL = Duration.ofMillis(100);
  private static final Duration PROCESS_COMPLETION_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

  private final String baseUrl;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Creates a client with default {@link RestTemplate} and {@link ObjectMapper} instances.
   *
   * @param baseUrl admin API base URL
   */
  public TlsTestToolService(String baseUrl) {
    this(baseUrl, createRestTemplate(), JsonMapper.builder().findAndAddModules().build());
  }

  /**
   * Creates a client with explicit collaborators.
   *
   * @param baseUrl admin API base URL
   * @param restTemplate HTTP client
   * @param objectMapper JSON mapper
   */
  public TlsTestToolService(String baseUrl, RestTemplate restTemplate, ObjectMapper objectMapper) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /**
   * Reads the currently stored TLS test tool configuration.
   *
   * @return stored config payload
   */
  public TlsToolConfigResponse getConfig() {
    return exchangeForJson("/config", HttpMethod.GET, null, TlsToolConfigResponse.class);
  }

  /**
   * Uploads TLS test tool configuration using the service's multipart endpoint.
   *
   * <p>The original local file name is not preserved. A stable multipart filename is sent because
   * some server stacks only expose the part as {@code MultipartFile} when a filename is present.
   *
   * @param configPath configuration file path
   */
  public void updateConfig(Path configPath) {
    try {
      Objects.requireNonNull(configPath, "configPath must not be null");
      if (!Files.isRegularFile(configPath)) {
        throw new IllegalArgumentException("configPath must point to an existing file: " + configPath);
      }
      if (!Files.isReadable(configPath)) {
        throw new IllegalArgumentException("configPath is not readable: " + configPath);
      }
      var configContent = Files.readString(configPath, StandardCharsets.UTF_8);
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.MULTIPART_FORM_DATA);

      var body = new LinkedMultiValueMap<String, Object>();
      body.add("file",
          new ByteArrayResource(configContent.getBytes(StandardCharsets.UTF_8)) {

            @Override
            public String getFilename() {
              return CONFIG_MULTIPART_FILENAME;
            }
          });

      exchange("/config", HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
    } catch (IllegalArgumentException e) {
      throw new AssertionError("Invalid TLS test tool configuration file.", e);
    } catch (IOException e) {
      throw new AssertionError("Failed to read TLS test tool configuration file: " + configPath, e);
    }
  }

  /**
   * Uploads PEM encoded certificate material.
   *
   * @param certificatePath certificate file path
   * @param privateKeyPath private key file path
   */
  public void updateCertificate(Path certificatePath, Path privateKeyPath) {
    try {
      Objects.requireNonNull(certificatePath, "certificatePath must not be null");
      if (!Files.isRegularFile(certificatePath)) {
        throw new IllegalArgumentException("certificatePath must point to an existing file: " + certificatePath);
      }
      if (!Files.isReadable(certificatePath)) {
        throw new IllegalArgumentException("certificatePath is not readable: " + certificatePath);
      }
      Objects.requireNonNull(privateKeyPath, "privateKeyPath must not be null");
      if (!Files.isRegularFile(privateKeyPath)) {
        throw new IllegalArgumentException("privateKeyPath must point to an existing file: " + privateKeyPath);
      }
      if (!Files.isReadable(privateKeyPath)) {
        throw new IllegalArgumentException("privateKeyPath is not readable: " + privateKeyPath);
      }

      var request = new TlsCertificateUploadPayload(
          Files.readString(certificatePath, StandardCharsets.UTF_8),
          normalizePrivateKeyPem(Files.readString(privateKeyPath, StandardCharsets.UTF_8)));
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      exchange("/certificate", HttpMethod.PUT, new HttpEntity<>(objectMapper.writeValueAsString(request), headers), Void.class);
    } catch (IllegalArgumentException e) {
      throw new AssertionError("Invalid TLS test tool certificate input file.", e);
    } catch (JacksonException e) {
      throw new AssertionError("Failed to serialize TLS test tool service request body.", e);
    } catch (IOException e) {
      throw new AssertionError("Failed to read TLS test tool certificate input file.", e);
    }
  }

  /**
   * Uploads PEM encoded CA certificate material for the next TLS test tool run.
   *
   * @param caCertificatePath CA certificate file path
   */
  public void updateCaCertificate(Path caCertificatePath) {
    try {
      Objects.requireNonNull(caCertificatePath, "caCertificatePath must not be null");
      if (!Files.isRegularFile(caCertificatePath)) {
        throw new IllegalArgumentException("caCertificatePath must point to an existing file: " + caCertificatePath);
      }
      if (!Files.isReadable(caCertificatePath)) {
        throw new IllegalArgumentException("caCertificatePath is not readable: " + caCertificatePath);
      }

      var request = new TlsCaCertificateUploadPayload(Files.readString(caCertificatePath, StandardCharsets.UTF_8));
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      exchange("/caCertificate", HttpMethod.PUT, new HttpEntity<>(objectMapper.writeValueAsString(request), headers), Void.class);
    } catch (IllegalArgumentException e) {
      throw new AssertionError("Invalid TLS test tool CA certificate input file.", e);
    } catch (JacksonException e) {
      throw new AssertionError("Failed to serialize TLS test tool service request body.", e);
    } catch (IOException e) {
      throw new AssertionError("Failed to read TLS test tool CA certificate input file.", e);
    }
  }

  /**
   * Uploads a DER-encoded OCSP response for the next TLS server run.
   *
   * @param response DER-encoded OCSP response
   */
  public void updateOcspResponse(byte[] response) {
    if (response == null || response.length == 0) {
      throw new AssertionError("TLS test tool OCSP response must not be empty.");
    }
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("application/ocsp-response"));
    exchange(
        "/ocspResponse",
        HttpMethod.PUT,
        new HttpEntity<>(response, headers),
        Void.class);
  }

  /**
   * Reads the current process state.
   *
   * @return state payload
   */
  public TlsToolStateResponse getState() {
    return exchangeForJson("/state", HttpMethod.GET, null, TlsToolStateResponse.class);
  }

  /**
   * Starts the TLS test tool process as TLS server.
   *
   * @return resulting process state
   */
  public TlsToolStateResponse startAsTlsServer() {
    return startProcess("/startAsTlsServer");
  }

  /**
   * Starts the TLS test tool process as TLS client.
   *
   * @return resulting process state
   */
  public TlsToolStateResponse startAsTlsClient() {
    return startProcess("/startAsTlsClient");
  }

  /** Starts an authenticated client using the certificate uploaded via {@code /certificate}. */
  public TlsToolStateResponse startAsTlsClient(boolean useClientCertificate) {
    return startProcess("/startAsTlsClient?useClientCertificate=" + useClientCertificate);
  }


  /**
   * Starts a process and tolerates a duplicated start request only when the service confirms that
   * the process is already running.
   *
   * <p>The standalone Tiger proxy can retry a request after the first request has already reached
   * the service. In that case the service returns {@code 409 Conflict} for the duplicate although
   * the requested process was started successfully.</p>
   *
   * @param path start endpoint
   * @return resulting process state
   */
  private TlsToolStateResponse startProcess(String path) {
    try {
      return exchangeForJson(path, HttpMethod.POST, HttpEntity.EMPTY, TlsToolStateResponse.class);
    } catch (AssertionError error) {
      if (error.getCause() instanceof HttpStatusCodeException statusException
          && statusException.getStatusCode().value() == 409) {
        try {
          var state = getState();
          if (state.running()) {
            return state;
          }
        } catch (AssertionError stateError) {
          error.addSuppressed(stateError);
        }
      }
      throw error;
    }
  }

  /** Completely resets configuration, credentials, logs and process state for exclusive reuse. */
  public TlsToolStateResponse reset() {
    var response = exchangeForJson("/reset", HttpMethod.POST, HttpEntity.EMPTY,
        tools.jackson.databind.JsonNode.class);
    if (!response.path("running").isBoolean() || response.path("running").asBoolean()
        || !response.path("lastExitCode").isNull() || !response.path("startedAt").isNull()
        || !response.path("stoppedAt").isNull()) {
      throw new AssertionError("TLS tool reset did not return a complete clean idle state");
    }
    var state = objectMapper.treeToValue(response, TlsToolStateResponse.class);
    if (state == null || state.running() || state.lastExitCode() != null
        || state.startedAt() != null || state.stoppedAt() != null) {
      throw new AssertionError("TLS tool reset did not return a clean idle state");
    }
    return state;
  }

  /**
   * Stops the TLS test tool process.
   *
   * @return resulting process state
   */
  public TlsToolStateResponse stop() {
    return exchangeForJson("/stop", HttpMethod.POST, HttpEntity.EMPTY, TlsToolStateResponse.class);
  }

  /**
   * Reads the merged logs after the TLS test tool process has completed.
   *
   * @return final plain text logs
   */
  public String getLogs() {
    awaitProcessCompletion();
    var response = exchange("/logs", HttpMethod.GET, null, String.class);
    return response.getBody() == null ? "" : response.getBody();
  }

  /**
   * Waits until the service reports that the TLS test tool process has completed.
   *
   * <p>Waits for final protocol evidence before retrieving logs. OpenSSL receive waits and the
   * tool's configured wait-before-close can together consume ten seconds after the handshake;
   * the completion budget also allows for the handshake and process cleanup. State polling keeps
   * that wait bounded even when the service's logs endpoint waits for process exit.</p>
   */
  private void awaitProcessCompletion() {
    var completionTimeout = getProcessCompletionTimeout();
    var deadline = System.nanoTime() + completionTimeout.toNanos();
    var pollingRequestFactory = DirectHttpClients.requestFactory(READ_TIMEOUT);
    var pollingRestTemplate = new RestTemplate(pollingRequestFactory);
    while (true) {
      var remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        throw processCompletionTimeout(completionTimeout, null);
      }
      try {
        configureCompletionPollTimeouts(pollingRequestFactory, Duration.ofNanos(remainingNanos));
        if (!getStateForCompletionPoll(pollingRestTemplate).running()) {
          return;
        }
      } catch (AssertionError e) {
        var cause = e.getCause();
        if (!isTransientCompletionPollFailure(cause)) {
          throw e;
        }
        if (System.nanoTime() >= deadline) {
          throw processCompletionTimeout(completionTimeout, cause);
        }
      }
      var remainingBeforePause = deadline - System.nanoTime();
      if (remainingBeforePause <= 0) {
        throw processCompletionTimeout(completionTimeout, null);
      }
      pauseBeforeNextCompletionPoll(Duration.ofNanos(remainingBeforePause));
    }
  }

  /**
   * Reads process state with the completion polling HTTP client.
   *
   * @param pollingRestTemplate completion polling HTTP client
   * @return state payload
   */
  private TlsToolStateResponse getStateForCompletionPoll(RestTemplate pollingRestTemplate) {
    return exchangeForJson(pollingRestTemplate, "/state", HttpMethod.GET, null, TlsToolStateResponse.class);
  }

  /**
   * Determines whether a failed process-state request can be retried safely within the budget.
   *
   * @param cause service request failure
   * @return {@code true} for connection failures and temporary server errors
   */
  private static boolean isTransientCompletionPollFailure(Throwable cause) {
    return cause instanceof ResourceAccessException
        || (cause instanceof HttpStatusCodeException statusException
            && statusException.getStatusCode().is5xxServerError());
  }

  /**
   * Splits the remaining completion budget between the sequential connect and read phases.
   *
   * @param requestFactory polling request factory to configure
   * @param remainingTimeout remaining process completion budget
   */
  private static void configureCompletionPollTimeouts(
      SimpleClientHttpRequestFactory requestFactory,
      Duration remainingTimeout) {
    var requestBudget = roundUpToMilliseconds(remainingTimeout);
    var halfBudget = requestBudget.dividedBy(2);
    var connectTimeout = atLeastOneMillisecond(
        halfBudget.compareTo(DirectHttpClients.CONNECT_TIMEOUT) < 0 ? halfBudget : DirectHttpClients.CONNECT_TIMEOUT);
    var readTimeout = atLeastOneMillisecond(requestBudget.minus(connectTimeout));
    requestFactory.setConnectTimeout(connectTimeout);
    requestFactory.setReadTimeout(readTimeout);
  }

  /**
   * Creates the assertion raised when the process completion budget is exhausted.
   *
   * @param completionTimeout configured completion timeout
   * @param cause optional timeout cause
   * @return process completion assertion
   */
  private static AssertionError processCompletionTimeout(Duration completionTimeout, Throwable cause) {
    return new AssertionError(
        "TLS test tool process did not complete within " + formatTimeout(completionTimeout) + ".", cause);
  }

  /**
   * Formats a process completion timeout for an actionable error message.
   *
   * @param timeout timeout to format
   * @return timeout expressed in whole seconds or milliseconds
   */
  private static String formatTimeout(Duration timeout) {
    if (timeout.toMillis() % 1000 == 0) {
      return timeout.toSeconds() + " seconds";
    }
    return timeout.toMillis() + " milliseconds";
  }

  /**
   * Rounds a positive HTTP timeout up to the HTTP client's supported millisecond precision.
   *
   * @param timeout timeout to normalize
   * @return timeout rounded up to at least one millisecond
   */
  private static Duration roundUpToMilliseconds(Duration timeout) {
    var milliseconds = timeout.toMillis();
    if (!timeout.minusMillis(milliseconds).isZero()) {
      milliseconds++;
    }
    return Duration.ofMillis(Math.max(1, milliseconds));
  }

  /**
   * Normalizes an HTTP timeout to at least one millisecond.
   *
   * @param timeout timeout to normalize
   * @return timeout of at least one millisecond
   */
  private static Duration atLeastOneMillisecond(Duration timeout) {
    return timeout.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : timeout;
  }

  /**
   * Returns the maximum time to wait for TLS test tool process completion.
   *
   * <p>This method can be overridden by tests to exercise timeout handling without waiting for the
   * production timeout.</p>
   *
   * @return process completion timeout
   */
  protected Duration getProcessCompletionTimeout() {
    return PROCESS_COMPLETION_TIMEOUT;
  }

  /**
   * Pauses between process-state completion polls without exceeding the remaining timeout budget.
   *
   * @param remainingTimeout remaining process completion budget
   */
  private static void pauseBeforeNextCompletionPoll(Duration remainingTimeout) {
    var pauseDuration = remainingTimeout.compareTo(PROCESS_COMPLETION_POLL_INTERVAL) < 0
        ? remainingTimeout
        : PROCESS_COMPLETION_POLL_INTERVAL;
    try {
      Thread.sleep(pauseDuration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for the TLS test tool process to complete.", e);
    }
  }

  /**
   * Clears the retained in-memory logs of the TLS test tool service.
   */
  public void clearLogs() {
    exchange("/logs/clear", HttpMethod.POST, HttpEntity.EMPTY, Void.class);
  }

  /**
   * Executes a request that returns JSON and deserializes it into the expected response type.
   *
   * @param path endpoint path relative to the service base URL
   * @param method HTTP method
   * @param requestEntity optional request entity
   * @param responseType target response class
   * @param <T> response payload type
   * @return deserialized response payload
   */
  private <T> T exchangeForJson(String path, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
    return exchangeForJson(restTemplate, path, method, requestEntity, responseType);
  }

  /**
   * Executes a request with a specific HTTP client and deserializes its JSON response.
   *
   * @param client HTTP client
   * @param path endpoint path relative to the service base URL
   * @param method HTTP method
   * @param requestEntity optional request entity
   * @param responseType target response class
   * @param <T> response payload type
   * @return deserialized response payload
   */
  private <T> T exchangeForJson(
      RestTemplate client,
      String path,
      HttpMethod method,
      HttpEntity<?> requestEntity,
      Class<T> responseType) {
    var response = exchange(client, path, method, requestEntity, String.class);
    if (response.getBody() == null || response.getBody().isBlank()) {
      throw new AssertionError("TLS test tool service returned an empty response for " + method + " " + path + ".");
    }
    try {
      return objectMapper.readValue(response.getBody(), responseType);
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse TLS test tool service response for " + method + " " + path + ".", e);
    }
  }

  /**
   * Normalizes uploaded private keys to PKCS#8 PEM so the TLS test tool service can load both
   * legacy SEC1 EC keys and already normalized PKCS#8 keys.
   *
   * @param privateKeyPem private key PEM content
   * @return PKCS#8 encoded private key PEM content
   */
  private String normalizePrivateKeyPem(String privateKeyPem) {
    Objects.requireNonNull(privateKeyPem, "privateKeyPem must not be null");
    if (privateKeyPem.isBlank()) {
      throw new AssertionError("The TLS test tool private key is empty.");
    }
    if (privateKeyPem.contains("-----BEGIN PRIVATE KEY-----")) {
      return privateKeyPem;
    }

    try (var pemParser = new PEMParser(new StringReader(privateKeyPem))) {
      var parsedObject = pemParser.readObject();
      var keyConverter = new JcaPEMKeyConverter();
      if (parsedObject instanceof PEMKeyPair pemKeyPair) {
        return encodePkcs8Pem(keyConverter.getPrivateKey(pemKeyPair.getPrivateKeyInfo()));
      } else if (parsedObject instanceof PrivateKeyInfo privateKeyInfo) {
        return encodePkcs8Pem(keyConverter.getPrivateKey(privateKeyInfo));
      } else {
        throw new AssertionError("Unsupported TLS test tool private key format.");
      }
    } catch (IOException e) {
      throw new AssertionError("Failed to normalize the TLS test tool private key to PKCS#8.", e);
    }
  }

  /**
   * Serializes a JCA private key as PKCS#8 PEM.
   *
   * @param privateKey private key to encode
   * @return PKCS#8 PEM content
   */
  private String encodePkcs8Pem(PrivateKey privateKey) {
    Objects.requireNonNull(privateKey, "privateKey must not be null");
    var encodedKey = privateKey.getEncoded();
    if (encodedKey == null || encodedKey.length == 0) {
      throw new AssertionError("Failed to encode the TLS test tool private key as PKCS#8.");
    }
    var base64Body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(encodedKey);
    return "-----BEGIN PRIVATE KEY-----\n"
        + base64Body
        + "\n-----END PRIVATE KEY-----\n";
  }

  /**
   * Executes a request against the TLS test tool service.
   *
   * @param path endpoint path relative to the service base URL
   * @param method HTTP method
   * @param requestEntity optional request entity
   * @param responseType expected response class
   * @param <T> response payload type
   * @return raw HTTP response entity
   */
  private <T> ResponseEntity<T> exchange(String path, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
    return exchange(restTemplate, path, method, requestEntity, responseType);
  }

  /**
   * Executes a request with a specific HTTP client against the TLS test tool service.
   *
   * @param client HTTP client
   * @param path endpoint path relative to the service base URL
   * @param method HTTP method
   * @param requestEntity optional request entity
   * @param responseType expected response class
   * @param <T> response payload type
   * @return raw HTTP response entity
   */
  private <T> ResponseEntity<T> exchange(
      RestTemplate client,
      String path,
      HttpMethod method,
      HttpEntity<?> requestEntity,
      Class<T> responseType) {
    var uri = URI.create(baseUrl + path);
    try {
      return client.exchange(uri, method, requestEntity, responseType);
    } catch (HttpStatusCodeException e) {
      var body = e.getResponseBodyAsString();
      var detail = body.isBlank() ? "<empty body>" : body;
      throw new AssertionError(
          "TLS test tool service call failed: " + method + " " + uri + " -> " + e.getStatusCode() + " " + detail,
          e);
    } catch (ResourceAccessException e) {
      throw new AssertionError("TLS test tool service is not reachable at '" + baseUrl + "'.", e);
    } catch (RestClientException e) {
      throw new AssertionError("TLS test tool service call failed: " + method + " " + uri + ".", e);
    }
  }

  /**
   * Removes a trailing slash from the configured base URL.
   *
   * @param baseUrl raw configured base URL
   * @return normalized base URL without trailing slash
   */
  private static String normalizeBaseUrl(String baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    var normalized = baseUrl.trim();
    if (normalized.isEmpty()) {
      throw new AssertionError("The baseUrl must not be blank");
    }
    return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  /**
   * Creates the default HTTP client with bounded connection and response waits.
   *
   * @return configured REST client
   */
  private static RestTemplate createRestTemplate() {
    var requestFactory = DirectHttpClients.requestFactory(READ_TIMEOUT);
    return new RestTemplate(requestFactory);
  }

}
