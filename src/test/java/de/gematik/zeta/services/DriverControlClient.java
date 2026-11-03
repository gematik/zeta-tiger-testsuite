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

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.zeta.steps.TimingGlue;
import io.restassured.builder.ResponseBuilder;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Calls only the configured driver's explicit SDK control API. */
public final class DriverControlClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final String prefix;

  private DriverControlClient(String prefix) {
    this.prefix = prefix;
  }

  /** Returns controls for the currently configured primary SDK context. */
  public static DriverControlClient primary() {
    return new DriverControlClient(base() + "/testdriver-api/control");
  }

  /** Invokes one explicit SDK operation and validates the driver's result envelope. */
  public JsonNode call(String operation, Object body) {
    require(operation.matches("[a-z]+(?:/[a-z]+)?"), "Invalid SDK operation");
    return decode(
        TigerHttpClient.givenDefaultSpec()
            .contentType("application/json")
            .body(JSON.writeValueAsString(body))
            .post(prefix + "/" + operation));
  }

  /** Invokes the SDK, then observes its unique new backend exchange through Tiger. */
  public Response response(String operation, Object body, String method, String path)
      throws Exception {
    var before = RecordedMessages.snapshot().stream()
        .map(RbelElement::getUuid).collect(Collectors.toSet());
    call(operation, body);
    var observed = BoundedObservation.await(
        () -> selectResponse(RecordedMessages.snapshot(), before, method, path),
        reply -> reply != null,
        Duration.ofSeconds(5), Duration.ofMillis(100),
        "No correlated SDK backend response within the evidence deadline");
    return downstream(observed);
  }

  static RbelElement selectResponse(
      List<RbelElement> messages, Set<String> before, String method, String path) {
    var requests = messages.stream()
        .filter(m -> !before.contains(m.getUuid()))
        .filter(m -> method.equals(value(m, "$.method")) && path.equals(value(m, "$.path")))
        .toList();
    require(requests.size() <= 1, "Multiple SDK backend requests; recovery or ambiguous evidence");
    if (requests.isEmpty()) {
      return null;
    }
    var reply = new TimingGlue().findResponseForRequest(messages, requests.getFirst());
    require(reply == null || !before.contains(reply.getUuid()), "Stale SDK backend response");
    return reply;
  }

  /** Reconstructs assertions' response exclusively from the correlated Tiger observation. */
  static Response downstream(RbelElement observed) {
    var status = value(observed, "$.responseCode");
    require(status != null && status.matches("[1-5][0-9]{2}"), "Missing backend HTTP status");
    var headers = new ArrayList<Header>();
    var lines = observed.getRawStringContent().split("\\r?\\n\\r?\\n", 2)[0]
        .split("\\r?\\n");
    require(lines[0].startsWith("HTTP/"), "Missing actual HTTP response headers");
    for (int index = 1; index < lines.length; index++) {
      var header = lines[index].split(":", 2);
      require(header.length == 2, "Malformed observed HTTP header");
      headers.add(new Header(header[0], header[1].strip()));
    }
    var body = value(observed, "$.body");
    require(body != null || observed.getRawStringContent().split("\\r?\\n\\r?\\n", 2)
        .length == 1 || observed.getRawStringContent().endsWith("\r\n\r\n"),
        "Backend body is present but has not been parsed");
    return new ResponseBuilder()
        .setStatusCode(Integer.parseInt(status))
        .setBody(body == null ? "" : body)
        .setHeaders(new Headers(headers))
        .build();
  }

  private static String value(RbelElement element, String path) {
    var values = element.findRbelPathMembers(path);
    require(values.size() <= 1, "Ambiguous backend response evidence: " + path);
    return values.isEmpty() ? null : values.getFirst().getRawStringContent();
  }

  static JsonNode decode(Response reply) {
    require(reply.statusCode() == 200, "SDK control API failed with HTTP " + reply.statusCode());
    var result = JSON.readTree(reply.asString());
    require(
        result != null && result.isObject() && result.path("completed").isBoolean()
            && (result.path("completed").asBoolean()
                || result.path("sdkRejected").asBoolean())
            && !result.has("adapterError"),
        "SDK control API returned no operation result");
    return result;
  }

  private static String base() {
    return TigerGlobalConfiguration.resolvePlaceholders("${paths.client.baseUrl}");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
