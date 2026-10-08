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

package de.gematik.zeta.steps;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a freshly issued Access Token from the TigerProxy message API.
 */
public class TigerProxyAccessTokenExtractor {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile(
      "(?i)(?:Bearer|dpop)\\s+(eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
  private static final HttpClient TIGER_PROXY_HTTP_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .build();
  private static final Duration TIGER_PROXY_HTTP_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration ACCESS_TOKEN_LOOKUP_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration ACCESS_TOKEN_LOOKUP_RETRY_INTERVAL = Duration.ofMillis(200);
  private static final int MAX_RECENT_MESSAGES_TO_INSPECT = 32;

  /**
   * Extracts the latest compact Access Token recorded by TigerProxy.
   *
   * <p>The lookup uses TigerProxy's own message API because requests issued directly by a Java
   * hook are not guaranteed to update the RBEL current-request selection.</p>
   *
   * @return latest compact Access Token found in TigerProxy message content
   * @throws AssertionError if the proxy is unavailable or no Access Token is recorded in time
   */
  public String extractLatestAccessToken() {
    var baseUrl = resolveTigerProxyBaseUrl();
    var inspectedMessageIds = new HashSet<String>();
    var deadline = System.nanoTime() + ACCESS_TOKEN_LOOKUP_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        var token = findLatestAccessTokenInMessages(baseUrl, inspectedMessageIds);
        if (token != null) {
          return token;
        }
        Thread.sleep(ACCESS_TOKEN_LOOKUP_RETRY_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for the fresh Access Token in TigerProxy.", e);
      } catch (JsonProcessingException e) {
        throw new AssertionError("TigerProxy returned invalid message metadata.", e);
      } catch (RuntimeException e) {
        throw new AssertionError("Could not read the fresh Access Token from TigerProxy at '" + baseUrl + "'.", e);
      }
    }
    throw new AssertionError("No fresh Access Token appeared in TigerProxy within "
        + ACCESS_TOKEN_LOOKUP_TIMEOUT.toSeconds() + " seconds.");
  }

  /**
   * Resolves and validates the configured TigerProxy base URL.
   *
   * @return resolved TigerProxy base URL
   * @throws AssertionError if TigerProxy is not configured
   */
  private String resolveTigerProxyBaseUrl() {
    var proxyId = TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId")
        .orElse(null);
    if (proxyId == null || proxyId.isBlank()) {
      throw new AssertionError("TigerProxy is required to extract the fresh Access Token.");
    }

    var baseUrl = TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}");
    if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("${")) {
      throw new AssertionError("TigerProxy base URL is not configured.");
    }
    return baseUrl;
  }

  /**
   * Searches recent TigerProxy messages for the latest compact Access Token.
   *
   * @param baseUrl resolved TigerProxy base URL
   * @param inspectedMessageIds message IDs already checked during this lookup
   * @return latest token or {@code null} while no token is available yet
   * @throws JsonProcessingException if TigerProxy returns invalid message metadata
   */
  private String findLatestAccessTokenInMessages(final String baseUrl,
      final Set<String> inspectedMessageIds) throws JsonProcessingException {
    var metadata = getTigerProxyApiResponse(
        baseUrl + resolvePath("${paths.tigerProxy.messagesWithMetaPath}"));
    var messages = JSON.readTree(metadata).path("messages");
    if (!messages.isArray()) {
      return null;
    }

    var firstMessageIndex = Math.max(0, messages.size() - MAX_RECENT_MESSAGES_TO_INSPECT);
    for (var index = messages.size() - 1; index >= firstMessageIndex; index--) {
      var messageId = messages.get(index).path("uuid").asText("");
      if (messageId.isBlank() || !inspectedMessageIds.add(messageId)) {
        continue;
      }

      var matcher = ACCESS_TOKEN_PATTERN.matcher(getTigerProxyApiResponse(
          baseUrl + resolvePath("${paths.tigerProxy.messageContentPath}") + "/" + messageId));
      var token = (String) null;
      while (matcher.find()) {
        token = matcher.group(1);
      }
      if (token != null) {
        return token;
      }
    }
    return null;
  }

  /**
   * Reads one TigerProxy API response without adding it to RBEL or Serenity evidence.
   *
   * @param url TigerProxy API URL
   * @return response body
   * @throws AssertionError if the request fails or returns a non-success status
   */
  private String getTigerProxyApiResponse(final String url) {
    try {
      var response = TIGER_PROXY_HTTP_CLIENT.send(
          HttpRequest.newBuilder(URI.create(url))
              .timeout(TIGER_PROXY_HTTP_TIMEOUT)
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new AssertionError("TigerProxy returned HTTP " + response.statusCode()
            + " for " + url);
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while reading TigerProxy API " + url, e);
    } catch (IOException e) {
      throw new AssertionError("Could not read TigerProxy API " + url, e);
    }
  }

  /**
   * Resolves one TigerProxy API path from Tiger configuration.
   *
   * @param placeholder Tiger placeholder containing the configured path
   * @return resolved path
   */
  private String resolvePath(final String placeholder) {
    return TigerGlobalConfiguration.resolvePlaceholders(placeholder);
  }
}
