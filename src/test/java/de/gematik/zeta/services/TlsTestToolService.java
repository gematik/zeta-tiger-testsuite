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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for the zeta-tls-test-tool-service admin API.
 */
public class TlsTestToolService {

  private static final String CONFIG_MULTIPART_FILENAME = "config";

  private final String baseUrl;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Creates a client with default {@link RestTemplate} and {@link ObjectMapper} instances.
   *
   * @param baseUrl admin API base URL
   */
  public TlsTestToolService(String baseUrl) {
    this(baseUrl, new RestTemplate(), new ObjectMapper().findAndRegisterModules());
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
    } catch (JsonProcessingException e) {
      throw new AssertionError("Failed to serialize TLS test tool service request body.", e);
    } catch (IOException e) {
      throw new AssertionError("Failed to read TLS test tool certificate input file.", e);
    }
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
   * Starts the TLS test tool process.
   *
   * @return resulting process state
   */
  public TlsToolStateResponse start() {
    return exchangeForJson("/start", HttpMethod.POST, HttpEntity.EMPTY, TlsToolStateResponse.class);
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
   * Reads the merged logs. The service stops the process first when required.
   *
   * @return final plain text logs
   */
  public String getLogs() {
    var response = exchange("/logs", HttpMethod.GET, null, String.class);
    return response.getBody() == null ? "" : response.getBody();
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
    var response = exchange(path, method, requestEntity, String.class);
    if (response.getBody() == null || response.getBody().isBlank()) {
      throw new AssertionError("TLS test tool service returned an empty response for " + method + " " + path + ".");
    }
    try {
      return objectMapper.readValue(response.getBody(), responseType);
    } catch (JsonProcessingException e) {
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
    var uri = URI.create(baseUrl + path);
    try {
      return restTemplate.exchange(uri, method, requestEntity, responseType);
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

}
