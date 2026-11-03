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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.rbel.RequestParameter;
import de.gematik.zeta.services.RecordedMessages;
import io.restassured.http.Method;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertionsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetryQueryStepsTest {
  private static final URI ENDPOINT = URI.create("http://localhost:9200/logs/_search");
  private static final String EMPTY = """
      {"timed_out":false,"_shards":{"failed":0},"hits":{"total":{"value":0,"relation":"eq"},"hits":[]}}
      """;

  @ParameterizedTest
  @ValueSource(ints = {200, 503})
  void pollingKeepsQueryAndFinalDuplicateOrErrorResponseVisible(int finalStatus) throws Exception {
    var opa = response(200, "{\"decision_id\":\"own\"}");
    opa.addOrReplaceFacet(new RbelMessageTimingFacet(Instant.now().atZone(ZoneOffset.UTC)));
    var query = "/logs/_search?q=decision_id%3A%22own%22&size=2&track_total_hits=true";
    var selectedRequest = new AtomicReference<>(request("GET", query, "localhost:9200"));
    var selectedResponse = new AtomicReference<>(opa);
    var finalResponse = response(finalStatus, EMPTY.replace("\"value\":0", "\"value\":2"));
    var reads = new AtomicInteger();
    var retriever = mock(RbelMessageRetriever.class);
    var spec = mock(RequestSpecification.class);
    when(spec.urlEncodingEnabled(false)).thenReturn(spec);
    when(retriever.getCurrentRequest()).thenAnswer(call -> selectedRequest.get());
    when(retriever.getCurrentResponse()).thenAnswer(call -> selectedResponse.get());
    when(retriever.doesPathOfMessageMatch(any(), anyString())).thenCallRealMethod();
    doAnswer(call -> {
      var next = request("GET", query, "localhost:9200");
      RequestParameter parameter = call.getArgument(0);
      assertTrue(retriever.doesPathOfMessageMatch(next, parameter.getPath()),
          "Tiger must actually match the repeated request's path");
      selectedRequest.set(next);
      selectedResponse.set(reads.incrementAndGet() == 1 ? response(200, EMPTY) : finalResponse);
      return null;
    }).when(retriever).filterRequestsAndStoreInContext(any());
    try (var recorded = mockStatic(RecordedMessages.class);
        var singleton = mockStatic(RbelMessageRetriever.class);
        var config = mockStatic(TigerGlobalConfiguration.class);
        var client = mockStatic(TigerHttpClient.class)) {
      singleton.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      singleton.when(() -> RbelMessageRetriever.getValueOrContentString(any())).thenCallRealMethod();
      recorded.when(RecordedMessages::snapshot).thenReturn(List.of(opa));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString())).thenAnswer(call -> {
        String text = call.getArgument(0);
        return text.contains("${paths.") ? ENDPOINT.toString() : text;
      });
      client.when(TigerHttpClient::givenDefaultSpec).thenReturn(spec);
      client.when(() -> TigerHttpClient.executeCommandWithContingentWait(any())).thenAnswer(call -> {
        ((SoftAssertionsProvider.ThrowingRunnable) call.getArgument(0)).run();
        return null;
      });
      var glue = new TelemetryQuerySteps();
      glue.rememberDeadline("own", "30");
      assertThrows(AssertionError.class, () -> glue.rememberDeadline("own", "30"));
      selectedResponse.set(response(200, EMPTY));
      glue.pollSelectedQuery("own", 1);
      assertSame(finalResponse, selectedResponse.get());
      verify(spec, times(2)).request(Method.GET, URI.create("http://localhost:9200" + query));
    }
  }

  @Test
  void repeatsOnlyHealthyEmptySearches() {
    assertTrue(TelemetryQuerySteps.retryEmptyResponse(response(200, EMPTY)));
    assertFalse(TelemetryQuerySteps.retryEmptyResponse(response(503, "unavailable")));
    for (var body : new String[] {
        EMPTY.replace("false", "true"), EMPTY.replace("\"failed\":0", "\"failed\":1"),
        EMPTY.replace("\"eq\"", "\"gte\""), EMPTY.replace("\"value\":0", "\"value\":1"),
        EMPTY.replace("\"value\":0", "\"value\":2"), EMPTY.replace("\"hits\":[]", "\"hits\":[{}]")}) {
      assertFalse(TelemetryQuerySteps.retryEmptyResponse(response(200, body)), body);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"hits\":{}}"})
  void malformedResponsesAreNotRetried(String body) {
    assertThrows(AssertionError.class, () -> TelemetryQuerySteps.retryEmptyResponse(response(200, body)));
  }

  @Test
  void invalidJsonIsNotRetried() {
    assertThrows(RuntimeException.class, () -> TelemetryQuerySteps.retryEmptyResponse(response(200, "not-json")));
  }

  @Test
  void preservesEncodedQueryAndEndpoint() {
    var query = "/logs/_search?q=traceId%3A%22abc%22+AND+decision_id%3A%22def%22&size=2&track_total_hits=true";
    assertEquals(URI.create("http://localhost:9200" + query),
        TelemetryQuerySteps.queryUri(request("GET", query, "localhost:9200"), ENDPOINT));
    assertEquals(URI.create("http://localhost:9200" + query),
        TelemetryQuerySteps.queryUri(request("GET", "http://localhost:9200" + query, "localhost:9200"), ENDPOINT));
  }

  @Test
  void refusesAnotherMethodHostPathOrMissingQuery() {
    for (var request : new RbelElement[] {
        request("POST", "/logs/_search?q=own", "localhost:9200"),
        request("GET", "/logs/_search?q=own", "other:9200"),
        request("GET", "/other/_search?q=own", "localhost:9200"),
        request("GET", "/logs/_search", "localhost:9200")}) {
      assertThrows(AssertionError.class, () -> TelemetryQuerySteps.queryUri(request, ENDPOINT));
    }
  }

  @Test
  void elapsedSetupAndEarlierChecksDoNotRestartDeadline() {
    var received = Instant.ofEpochSecond(1000);
    var origin = Duration.ofSeconds(50).toNanos();
    var first = TelemetryQuerySteps.deadline(received, 660, received.plusSeconds(100), origin);
    var later = TelemetryQuerySteps.deadline(received, 660, received.plusSeconds(500),
        origin + Duration.ofSeconds(400).toNanos());
    assertEquals(first, later);
    assertEquals(origin + Duration.ofSeconds(560).toNanos(), first);
    assertTrue(TelemetryQuerySteps.deadline(received, 660, received.plusSeconds(661), origin) < origin);
    assertThrows(AssertionError.class, () -> TelemetryQuerySteps.deadline(received, 0, received, origin));
    assertThrows(AssertionError.class, () -> TelemetryQuerySteps.deadline(received, 660, received.minusSeconds(1), origin));
  }

  private static RbelElement request(String method, String target, String host) {
    return message(method + " " + target + " HTTP/1.1\r\nHost: " + host + "\r\nContent-Length: 0\r\n\r\n");
  }

  private static RbelElement response(int status, String body) {
    return message("HTTP/1.1 " + status + " Response\r\nContent-Type: application/json\r\nContent-Length: "
        + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body);
  }

  private static RbelElement message(String content) {
    return RbelLogger.build().getRbelConverter().parseMessage(
        content.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }
}
