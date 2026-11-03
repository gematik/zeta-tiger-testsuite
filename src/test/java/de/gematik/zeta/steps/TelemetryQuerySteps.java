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

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.RBelValidatorGlue;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import io.restassured.http.Method;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** Repeats an unchanged OpenSearch GET within a deadline tied to a recorded response. */
public class TelemetryQuerySteps {
  private final Map<String, Long> deadlines = new HashMap<>();

  /**
   * Stores a monotonic deadline derived from the complete selected response's receipt time.
   *
   * @param name unique scenario-local deadline name
   * @param seconds maximum elapsed seconds from response receipt
   */
  @Dann("merke ab der aktuellen Antwort die Telemetriefrist {string} von {tigerResolvedString} Sekunden")
  public void rememberDeadline(String name, String seconds) {
    var response = RbelMessageRetriever.getInstance().getCurrentResponse();
    require(response != null, "Missing response for telemetry deadline");
    require(!deadlines.containsKey(name), "Telemetry deadline already exists: " + name);
    var received = TimingGlue.responseReceivedAt(RecordedMessages.snapshot(), response);
    deadlines.put(name, deadline(received, Long.parseLong(seconds), Instant.now(), System.nanoTime()));
  }

  /**
   * Repeats only healthy empty responses; leaves the final response selected for explicit assertions.
   *
   * @param name previously stored deadline name
   * @param seconds interval between queries
   * @throws InterruptedException if polling is interrupted
   */
  @Dann("wiederhole die aktuelle OpenSearch-GET-Abfrage innerhalb der Telemetriefrist {string} alle {int} Sekunden solange das Ergebnis leer ist")
  public void pollSelectedQuery(String name, int seconds) throws InterruptedException {
    var deadline = deadlines.get(name);
    require(deadline != null, "Missing telemetry deadline: " + name);
    var retriever = RbelMessageRetriever.getInstance();
    var endpoint = URI.create(TigerGlobalConfiguration.resolvePlaceholders(
        "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}"));
    var target = queryUri(retriever.getCurrentRequest(), endpoint);
    var path = target.getPath();
    var first = new boolean[] {true};
    BoundedObservation.awaitUntil(() -> {
      if (!first[0]) {
        var previous = retriever.getCurrentRequest().getUuid();
        TigerHttpClient.executeCommandWithContingentWait(() -> TigerHttpClient.givenDefaultSpec()
            .urlEncodingEnabled(false).request(Method.GET, target));
        new RBelValidatorGlue().findLastRequestToPath(Pattern.quote(path));
        require(!previous.equals(retriever.getCurrentRequest().getUuid()), "Missing fresh OpenSearch query");
        require(target.equals(queryUri(retriever.getCurrentRequest(), endpoint)), "OpenSearch query changed during polling");
      }
      first[0] = false;
      return retriever.getCurrentResponse();
    }, response -> !retryEmptyResponse(response), deadline, Duration.ofSeconds(seconds),
        "OpenSearch decision log remained absent within telemetry deadline '" + name + "'");
  }

  static long deadline(Instant received, long seconds, Instant now, long nanoTime) {
    require(seconds > 0 && seconds <= 3600, "Telemetry deadline must be between 1 and 3600 seconds");
    require(!received.isAfter(now), "Response receipt time is in the future");
    return nanoTime + Duration.between(now, received.plusSeconds(seconds)).toNanos();
  }

  static URI queryUri(RbelElement request, URI endpoint) {
    require(request != null && "GET".equals(value(request, "$.method")), "Select an OpenSearch GET first");
    var line = request.getRawStringContent().split("\\r?\\n", 2)[0].split(" ", -1);
    require(line.length == 3, "Invalid HTTP request target");
    var target = URI.create(line[1]);
    if (!target.isAbsolute()) {
      require(line[1].startsWith("/") && !line[1].startsWith("//"), "Invalid relative query target");
      target = URI.create(endpoint.getScheme() + "://" + value(request, "$.header.[~'Host']") + line[1]);
    }
    require(endpoint.getScheme().equals(target.getScheme())
        && endpoint.getRawAuthority().equals(target.getRawAuthority())
        && endpoint.getRawPath().equals(target.getRawPath())
        && target.getRawQuery() != null && target.getRawUserInfo() == null && target.getRawFragment() == null,
        "Selected request is not the configured OpenSearch search endpoint");
    require(value(request, "$.body").isEmpty(), "OpenSearch GET must have no body");
    return target;
  }

  static boolean retryEmptyResponse(RbelElement response) {
    require(response != null, "Missing OpenSearch response");
    if (!"200".equals(value(response, "$.responseCode"))) {
      return false;
    }
    var body = new ObjectMapper().readTree(value(response, "$.body"));
    var total = body.at("/hits/total/value");
    require(body.path("timed_out").isBoolean() && body.at("/_shards/failed").isIntegralNumber()
        && total.isIntegralNumber() && total.asLong() >= 0
        && body.at("/hits/total/relation").isString() && body.at("/hits/hits").isArray(),
        "Malformed OpenSearch search response");
    return !body.path("timed_out").asBoolean() && body.at("/_shards/failed").asLong() == 0
        && "eq".equals(body.at("/hits/total/relation").asString())
        && total.asLong() == 0 && body.at("/hits/hits").isEmpty();
  }

  private static String value(RbelElement message, String path) {
    var nodes = message.findRbelPathMembers(path);
    require(nodes.size() == 1, "Missing or ambiguous node: " + path);
    return nodes.getFirst().getRawStringContent();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
