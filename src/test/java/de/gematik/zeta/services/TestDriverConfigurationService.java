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

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the testdriver configuration and identity endpoints used by scenarios.
 */
public class TestDriverConfigurationService {

  private final String resetUrl;
  private final String configureUrl;
  private final String kvnrEmailUrl;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Creates a client with default {@link RestTemplate} and {@link ObjectMapper} instances.
   *
   * @param resetUrl absolute reset endpoint URL
   * @param configureUrl absolute configure endpoint URL
   */
  public TestDriverConfigurationService(String resetUrl, String configureUrl) {
    this(resetUrl, configureUrl, null, new RestTemplate(), JsonMapper.builder().findAndAddModules().build());
  }

  /**
   * Creates a client with default HTTP and JSON collaborators.
   *
   * @param resetUrl absolute reset endpoint URL
   * @param configureUrl absolute configure endpoint URL
   * @param kvnrEmailUrl absolute OIDC identity endpoint URL
   */
  public TestDriverConfigurationService(String resetUrl, String configureUrl, String kvnrEmailUrl) {
    this(resetUrl, configureUrl, kvnrEmailUrl, new RestTemplate(), JsonMapper.builder().findAndAddModules().build());
  }

  /**
   * Creates a client with explicit collaborators.
   *
   * @param resetUrl absolute reset endpoint URL
   * @param configureUrl absolute configure endpoint URL
   * @param kvnrEmailUrl absolute OIDC identity endpoint URL, or {@code null} when unsupported
   * @param restTemplate HTTP client
   * @param objectMapper JSON mapper
   */
  public TestDriverConfigurationService(String resetUrl, String configureUrl, String kvnrEmailUrl,
      RestTemplate restTemplate, ObjectMapper objectMapper) {
    this.resetUrl = normalizeUrl(resetUrl, "resetUrl");
    this.configureUrl = normalizeUrl(configureUrl, "configureUrl");
    this.kvnrEmailUrl = normalizeOptionalUrl(kvnrEmailUrl, "kvnrEmailUrl");
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /**
   * Creates a client with explicit HTTP and JSON collaborators.
   *
   * @param resetUrl absolute reset endpoint URL
   * @param configureUrl absolute configure endpoint URL
   * @param restTemplate HTTP client
   * @param objectMapper JSON mapper
   */
  public TestDriverConfigurationService(String resetUrl, String configureUrl,
      RestTemplate restTemplate, ObjectMapper objectMapper) {
    this(resetUrl, configureUrl, null, restTemplate, objectMapper);
  }

  /**
   * Resets the testdriver to its default configuration.
   */
  public void reset() {
    exchange(resetUrl, HttpMethod.GET, HttpEntity.EMPTY);
  }

  /**
   * Sets the KVNR and binding email used by the testdriver for its next authenticated request.
   *
   * <p>The values are intentionally not locally validated so scenarios can also exercise the
   * testdriver's rejection of invalid input; {@link #setRandomKvnrEmail(String)} always produces
   * a valid KVNR.</p>
   *
   * @param kvnr KVNR to configure
   * @param email binding email to configure
   */
  public void setKvnrEmail(String kvnr, String email) {
    var configuredKvnr = Objects.requireNonNull(kvnr, "kvnr must not be null");
    var configuredEmail = Objects.requireNonNull(email, "email must not be null");
    if (configuredKvnr.isBlank()) {
      throw new IllegalArgumentException("kvnr must not be blank");
    }
    if (configuredEmail.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }

    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    exchange(requireKvnrEmailUrl(), HttpMethod.POST,
        jsonEntity(Map.of("kvnr", configuredKvnr, "email", configuredEmail), headers));
  }

  /**
   * Generates a valid random KVNR and configures it with a correlated binding email.
   *
   * @param emailDomain domain used for the binding email
   * @return the generated KVNR
   */
  public String setRandomKvnrEmail(String emailDomain) {
    var configuredEmailDomain = Objects.requireNonNull(emailDomain, "emailDomain must not be null").trim();
    if (configuredEmailDomain.isBlank()) {
      throw new IllegalArgumentException("emailDomain must not be blank");
    }
    var kvnr = KvnrGenerator.generateRandomValidKvnr();
    setKvnrEmail(kvnr, kvnr + "@" + configuredEmailDomain);
    return kvnr;
  }

  /**
   * Configures the testdriver resource target and trusted CA PEM.
   *
   * @param resource                     protected resource base URL the client should call
   * @param caCertificatePem             PEM-encoded CA certificate to trust
   * @param clientDisableTlsVerification whether the client should disable TLS verification
   */
  public void configure(String resource, String caCertificatePem,
      boolean clientDisableTlsVerification) {
    Objects.requireNonNull(resource, "resource must not be null");
    Objects.requireNonNull(caCertificatePem, "caCertificatePem must not be null");

    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var body = Map.of(
        "resource", resource,
        "caCertificatePem", caCertificatePem,
        "disableTlsVerification", clientDisableTlsVerification);
    exchange(configureUrl, HttpMethod.POST, jsonEntity(body, headers));
  }

  /**
   * Configures the testdriver once its application endpoint is ready after a deployment restart.
   *
   * <p>Only transient gateway and connection failures are retried. Client errors and other
   * configuration failures are reported immediately.</p>
   *
   * @param resource protected resource base URL the client should call
   * @param caCertificatePem PEM-encoded CA certificate to trust
   * @param clientDisableTlsVerification whether the client should disable TLS verification
   * @param timeout maximum time to wait for the configuration endpoint
   * @param retryInterval delay between attempts
   */
  public void configureWhenReady(
      String resource,
      String caCertificatePem,
      boolean clientDisableTlsVerification,
      Duration timeout,
      Duration retryInterval) {
    validatePositiveDuration(timeout, "timeout");
    validatePositiveDuration(retryInterval, "retryInterval");

    var deadline = System.nanoTime() + timeout.toNanos();
    AssertionError lastFailure;
    do {
      try {
        configure(resource, caCertificatePem, clientDisableTlsVerification);
        return;
      } catch (AssertionError e) {
        if (!isTransientReadinessFailure(e)) {
          throw e;
        }
        lastFailure = e;
      }

      var remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        break;
      }
      sleepBeforeRetry(Math.min(retryInterval.toNanos(), remainingNanos));
    } while (System.nanoTime() < deadline);

    throw new AssertionError(
        "Testdriver configuration endpoint did not become ready within " + timeout + ".",
        lastFailure);
  }

  /**
   * Serializes the given request body as JSON.
   *
   * @param body request payload
   * @param headers request headers
   * @return JSON request entity
   */
  private HttpEntity<String> jsonEntity(Object body, HttpHeaders headers) {
    try {
      return new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
    } catch (JacksonException e) {
      throw new AssertionError("Failed to serialize testdriver configuration request body.", e);
    }
  }

  /**
   * Executes a request against a testdriver endpoint and wraps client errors as assertion failures.
   *
   * @param url absolute endpoint URL
   * @param method HTTP method
   * @param requestEntity request payload
   */
  private void exchange(String url, HttpMethod method, HttpEntity<?> requestEntity) {
    var uri = URI.create(url);
    try {
      restTemplate.exchange(uri, method, requestEntity, String.class);
    } catch (HttpStatusCodeException e) {
      var body = e.getResponseBodyAsString();
      var detail = body.isBlank() ? "<empty body>" : body;
      throw new AssertionError(
          "Testdriver call failed: " + method + " " + uri + " -> " + e.getStatusCode() + " " + detail,
          e);
    } catch (ResourceAccessException e) {
      throw new AssertionError("Testdriver endpoint is not reachable: " + method + " " + uri + ".", e);
    } catch (RestClientException e) {
      throw new AssertionError("Testdriver call failed: " + method + " " + uri + ".", e);
    }
  }

  /**
   * Checks whether a failed configuration attempt indicates an endpoint that is still starting.
   *
   * @param error wrapped request failure
   * @return {@code true} if retrying can establish readiness
   */
  private static boolean isTransientReadinessFailure(AssertionError error) {
    if (error.getCause() instanceof ResourceAccessException) {
      return true;
    }
    if (error.getCause() instanceof HttpStatusCodeException statusException) {
      return switch (statusException.getStatusCode().value()) {
        case 502, 503, 504 -> true;
        default -> false;
      };
    }
    return false;
  }

  /**
   * Waits before the next endpoint readiness attempt while preserving interruption state.
   *
   * @param delayNanos delay in nanoseconds
   */
  private static void sleepBeforeRetry(long delayNanos) {
    try {
      Thread.sleep(Duration.ofNanos(delayNanos));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for the testdriver configuration endpoint.", e);
    }
  }

  /**
   * Validates a strictly positive retry duration.
   *
   * @param duration duration to validate
   * @param name parameter name
   */
  private static void validatePositiveDuration(Duration duration, String name) {
    Objects.requireNonNull(duration, name + " must not be null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * Normalizes and validates an absolute endpoint URL.
   *
   * @param url raw URL value
   * @param name parameter name for error reporting
   * @return trimmed URL
   */
  private static String normalizeUrl(String url, String name) {
    var normalized = Objects.requireNonNull(url, name + " must not be null").trim();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  /**
   * Normalizes an optional endpoint URL.
   *
   * @param url raw optional URL value
   * @param name parameter name for error reporting
   * @return trimmed URL or {@code null}
   */
  private static String normalizeOptionalUrl(String url, String name) {
    return url == null ? null : normalizeUrl(url, name);
  }

  /**
   * Returns the configured OIDC identity endpoint or reports an incomplete testdriver configuration.
   *
   * @return configured OIDC identity endpoint URL
   */
  private String requireKvnrEmailUrl() {
    if (kvnrEmailUrl == null) {
      throw new AssertionError("The testdriver KVNR/email URL is not configured.");
    }
    return kvnrEmailUrl;
  }
}
