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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.DirectHttpClients;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Reads a freshly issued Access Token from observed Guard requests.
 */
public class TigerProxyAccessTokenExtractor {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile(
      "(?i)(?:Bearer|dpop)\\s+(eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
  private static final HttpClient TIGER_PROXY_HTTP_CLIENT = DirectHttpClients.httpClientBuilder()
      .build();
  private static final Duration TIGER_PROXY_HTTP_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration ACCESS_TOKEN_LOOKUP_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration ACCESS_TOKEN_LOOKUP_RETRY_INTERVAL = Duration.ofMillis(200);
  private final Supplier<Map<String, String>> localMessages;
  private final HttpClient proxyHttpClient;
  private final LongSupplier clock;

  /** Uses the scenario's downloaded traffic in addition to the remote proxy API. */
  public TigerProxyAccessTokenExtractor() {
    this(() -> {
      var messages = new LinkedHashMap<String, String>();
      RbelMessageRetriever.getInstance().getMessageHistory().getMessagesByOrder()
          .forEach(message -> messages.put(message.getUuid(), message.getRawStringContent()));
      return messages;
    });
  }

  TigerProxyAccessTokenExtractor(Supplier<Map<String, String>> localMessages) {
    this(localMessages, TIGER_PROXY_HTTP_CLIENT, System::nanoTime);
  }

  TigerProxyAccessTokenExtractor(Supplier<Map<String, String>> localMessages,
      HttpClient proxyHttpClient, LongSupplier clock) {
    this.localMessages = localMessages;
    this.proxyHttpClient = proxyHttpClient;
    this.clock = clock;
  }

  /**
   * Captures both histories before issuing the request whose token is needed.
   *
   * @return immutable IDs of messages that cannot prove a fresh token
   */
  public Set<String> captureMessageIds() {
    try {
      var ids = new HashSet<String>();
      for (var message : readMessages(resolveTigerProxyBaseUrl(),
          clock.getAsLong() + TIGER_PROXY_HTTP_TIMEOUT.toNanos())) {
        ids.add(message.path("uuid").asText(""));
      }
      ids.addAll(localMessages.get().keySet());
      return Set.copyOf(ids);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while capturing TigerProxy message history.", e);
    } catch (IOException e) {
      throw new AssertionError("Could not capture TigerProxy message history.", e);
    }
  }

  /**
   * Extracts the latest compact Access Token recorded by TigerProxy.
   *
   * <p>Inspect the scenario's downloaded traffic independently of the RBEL current-request
   * selection. The remote API remains a fallback; its parsed-history prefix may be held back by
   * an unfinished old message even while fresh traffic is delivered to the scenario.</p>
   *
   * @param previousMessageIds history captured before issuing the fresh request
   * @return latest compact Access Token found in a new Guard request
   * @throws AssertionError if the proxy is unavailable or no Access Token is recorded in time
   */
  public String extractLatestAccessToken(Set<String> previousMessageIds) {
    return extractLatestAccessToken(previousMessageIds,
        TigerGlobalConfiguration.resolvePlaceholders("${paths.guard.helloZetaPath}"));
  }

  /**
   * Extracts a fresh token from a GET to the specified Guard path, excluding earlier traffic.
   *
   * @param previousMessageIds messages captured before the operation
   * @param guardPath exact protected request path
   * @return compact token from the matching request
   */
  public String extractLatestAccessToken(Set<String> previousMessageIds, String guardPath) {
    var baseUrl = resolveTigerProxyBaseUrl();
    var inspectedMessageIds = new HashSet<>(previousMessageIds);
    var deadline = clock.getAsLong() + ACCESS_TOKEN_LOOKUP_TIMEOUT.toNanos();
    while (clock.getAsLong() < deadline) {
      try {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("Access Token lookup interrupted");
        }
        var token = findLatestLocalAccessToken(previousMessageIds, guardPath);
        if (token == null) {
          token = findLatestAccessTokenInMessages(baseUrl, guardPath, inspectedMessageIds, deadline);
        }
        if (token != null) {
          return token;
        }
        var remaining = deadline - clock.getAsLong();
        if (remaining > 0) {
          Thread.sleep(Duration.ofNanos(Math.min(remaining, ACCESS_TOKEN_LOOKUP_RETRY_INTERVAL.toNanos())));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for the fresh Access Token in TigerProxy.", e);
      } catch (JsonProcessingException e) {
        throw new AssertionError("TigerProxy returned invalid message metadata.", e);
      } catch (IOException | RuntimeException e) {
        // The last request is bounded by the remaining lookup time, so its timeout can
        // signal normal deadline exhaustion. Earlier transport failures remain distinct.
        if (e instanceof HttpTimeoutException && clock.getAsLong() >= deadline) {
          throw noFreshAccessToken(e);
        }
        throw new AssertionError("Could not read the fresh Access Token from TigerProxy at '" + baseUrl + "'.", e);
      }
    }
    throw noFreshAccessToken(null);
  }

  private AssertionError noFreshAccessToken(Throwable cause) {
    return new AssertionError("No fresh Access Token appeared in TigerProxy within "
        + ACCESS_TOKEN_LOOKUP_TIMEOUT.toSeconds() + " seconds.", cause);
  }

  private String findLatestLocalAccessToken(Set<String> previousMessageIds, String guardPath) {
    String token = null;
    for (var message : localMessages.get().entrySet()) {
      if (!previousMessageIds.contains(message.getKey())) {
        var candidate = accessTokenFromGuardRequest(message.getValue(), guardPath);
        if (candidate != null) {
          token = candidate;
        }
      }
    }
    return token;
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
   * @param guardPath exact protected request path
   * @param inspectedMessageIds message IDs already checked during this lookup
   * @param deadline absolute lookup deadline in nanoseconds
   * @return latest token or {@code null} while no token is available yet
   * @throws IOException if the API request fails or message metadata is invalid
   * @throws InterruptedException if the API request is interrupted
   */
  private String findLatestAccessTokenInMessages(final String baseUrl, final String guardPath,
      final Set<String> inspectedMessageIds, final long deadline) throws IOException, InterruptedException {
    var messages = readMessages(baseUrl, deadline);
    for (var index = messages.size() - 1; index >= 0; index--) {
      var messageId = messages.get(index).path("uuid").asText("");
      if (messageId.isBlank() || !inspectedMessageIds.add(messageId)) {
        continue;
      }
      var content = getTigerProxyApiResponse(
          baseUrl + TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.messageContentPath}")
              + "/" + messageId, deadline);
      var token = accessTokenFromGuardRequest(content, guardPath);
      if (token != null) {
        return token;
      }
    }
    return null;
  }

  private String accessTokenFromGuardRequest(String content, String guardPath) {
    var guardHost = TigerGlobalConfiguration.resolvePlaceholders("${zeta_base_url}");
    var headers = content.split("\\r?\\n\\r?\\n", 2)[0];
    if (!headers.startsWith("GET " + guardPath + " HTTP/")
        || !Pattern.compile("(?im)^Host: *" + Pattern.quote(guardHost) + "(?::[0-9]+)? *\\r?$")
            .matcher(headers).find()) {
      return null;
    }
    var authorization = Pattern.compile("(?im)^Authorization: *([^\\r\\n]+)").matcher(headers);
    if (!authorization.find()) {
      return null;
    }
    var matcher = ACCESS_TOKEN_PATTERN.matcher(authorization.group(1));
    return matcher.matches() ? matcher.group(1) : null;
  }

  /**
   * Reads and validates the complete remote message index.
   *
   * @param baseUrl remote proxy endpoint
   * @param deadline absolute lookup deadline
   * @return metadata array
   * @throws IOException if the index cannot be read or has an unexpected shape
   * @throws InterruptedException if the request is interrupted
   */
  private JsonNode readMessages(String baseUrl, long deadline) throws IOException, InterruptedException {
    var response = getTigerProxyApiResponse(baseUrl
        + TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.messagesWithMetaPath}"), deadline);
    var messages = JSON.readTree(response).path("messages");
    if (!messages.isArray()) {
      throw new IOException("TigerProxy metadata does not contain a messages array.");
    }
    return messages;
  }

  /**
   * Reads one TigerProxy API response without adding it to RBEL or Serenity evidence.
   *
   * @param url TigerProxy API URL
   * @param deadline absolute lookup deadline in nanoseconds
   * @return response body
   * @throws IOException if the request fails or returns a non-success status
   * @throws InterruptedException if the request is interrupted
   */
  private String getTigerProxyApiResponse(final String url, final long deadline) throws IOException, InterruptedException {
    var remaining = deadline - clock.getAsLong();
    if (remaining <= 0) {
      throw new HttpTimeoutException("TigerProxy Access Token lookup deadline exceeded.");
    }
    var response = proxyHttpClient.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofNanos(Math.min(remaining, TIGER_PROXY_HTTP_TIMEOUT.toNanos())))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("TigerProxy returned HTTP " + response.statusCode() + " for " + url);
    }
    return response.body();
  }
}
