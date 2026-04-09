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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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

/**
 * Client for the testdriver configuration endpoints used by TLS client scenarios.
 */
public class TestDriverConfigurationService {

  private final String resetUrl;
  private final String configureUrl;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Creates a client with default {@link RestTemplate} and {@link ObjectMapper} instances.
   *
   * @param resetUrl absolute reset endpoint URL
   * @param configureUrl absolute configure endpoint URL
   */
  public TestDriverConfigurationService(String resetUrl, String configureUrl) {
    this(resetUrl, configureUrl, new RestTemplate(), new ObjectMapper().findAndRegisterModules());
  }

  /**
   * Creates a client with explicit collaborators.
   *
   * @param resetUrl absolute reset endpoint URL
   * @param configureUrl absolute configure endpoint URL
   * @param restTemplate HTTP client
   * @param objectMapper JSON mapper
   */
  public TestDriverConfigurationService(String resetUrl, String configureUrl, RestTemplate restTemplate, ObjectMapper objectMapper) {
    this.resetUrl = normalizeUrl(resetUrl, "resetUrl");
    this.configureUrl = normalizeUrl(configureUrl, "configureUrl");
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /**
   * Resets the testdriver to its default configuration.
   */
  public void reset() {
    exchange(resetUrl, HttpMethod.GET, HttpEntity.EMPTY);
  }

  /**
   * Configures the testdriver resource target and trusted CA PEM.
   *
   * @param resource protected resource base URL the client should call
   * @param caCertificatePem PEM-encoded CA certificate to trust
   */
  public void configure(String resource, String caCertificatePem) {
    Objects.requireNonNull(resource, "resource must not be null");
    Objects.requireNonNull(caCertificatePem, "caCertificatePem must not be null");

    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var body = Map.of(
        "resource", resource,
        "caCertificatePem", caCertificatePem);
    exchange(configureUrl, HttpMethod.POST, jsonEntity(body, headers));
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
    } catch (JsonProcessingException e) {
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
      var detail = body == null || body.isBlank() ? "<empty body>" : body;
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
}
