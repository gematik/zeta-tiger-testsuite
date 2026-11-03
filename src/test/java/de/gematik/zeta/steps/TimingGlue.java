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
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.test.tiger.proxy.handler.SingleConnectionParser.SingleConnectionParserMarkerFacet;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber step glue for logging RBEL messages and reporting timing information between correlated
 * request/response pairs using RBEL tracing and timing facets.
 */
@Slf4j
public class TimingGlue {

  private Instant cacheResponseTime;

  /**
   * Remembers the observed response that established the cache entry used by this scenario.
   */
  @Dann("merke den Empfangszeitpunkt der aktuellen Antwort als Cache-Beginn")
  public void rememberCacheResponseTime() {
    var retriever = RbelMessageRetriever.getInstance();
    var response = findResponseForRequest(
        RecordedMessages.snapshot(), retriever.getCurrentRequest());
    if (response == null) {
      throw new AssertionError("No correlated cache response");
    }
    cacheResponseTime = requireTimingFacet(response, "cache response")
        .getTransmissionTime().toInstant();
  }

  /**
   * Requires the actual resource request to occur within the observed cache lifetime.
   *
   * @param lifetimeSeconds cache lifetime supplied in the observed response
   */
  @Dann("prüfe, dass die aktuelle Anfrage innerhalb der gemerkten Cache-Gültigkeit von {int} Sekunden liegt")
  public void verifyCurrentRequestWithinCacheLifetime(int lifetimeSeconds) {
    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    var requestTime = requireTimingFacet(request, "resource request").getTransmissionTime().toInstant();
    if (!isWithinCacheLifetime(cacheResponseTime, requestTime, lifetimeSeconds)) {
      throw new AssertionError("Resource request is outside the observed cache lifetime");
    }
  }

  static boolean isWithinCacheLifetime(Instant start, Instant request, int seconds) {
    return start != null && seconds > 0 && !request.isBefore(start)
        && request.isBefore(start.plusSeconds(seconds));
  }

  /**
   * Helper to extract the required timing facet from an {@link RbelElement} or throw a concise
   * assertion error.
   *
   * @param element the RBEL element providing the timing facet
   * @param context human-readable context
   * @return the timing facet instance
   * @throws AssertionError if the timing facet is not present on the element
   */
  private static RbelMessageTimingFacet requireTimingFacet(@NonNull RbelElement element,
      @NonNull String context) {
    return element.getFacet(RbelMessageTimingFacet.class).orElseThrow(
        () -> new AssertionError("Missing RbelMessageTimingFacet for " + context));
  }

  /**
   * Computes and reports the response time for the currently active request/response pair. Adds a
   * custom entry to the Serenity report and logs the measured latency.
   *
   * @throws AssertionError if no messages are available, no current request exists, or the required
   *                        timing facets are missing.
   */
  @Dann("gebe die Antwortzeit vom aktuellen Nachrichtenpaar aus")
  @Then("show response time of current message pair")
  public void showResponseTimeOfCurrentMessagePair() {
    var messages = RecordedMessages.snapshot();
    if (messages == null || messages.isEmpty()) {
      throw new AssertionError("No RBEL messages recorded – cannot determine response time.");
    }

    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    if (request == null) {
      throw new AssertionError("No current request available – cannot determine response time.");
    }

    var response = this.findResponseForRequest(messages, request);
    if (response == null) {
      throw new AssertionError("No matching response found for request " + request.getUuid());
    }

    var duration = this.getDurationBetweenRequestAndResponse(request, response);
    SerenityReportUtils.addCustomData("Current response time",
        "The current response took " + duration.toMillis() + " ms.");
  }

  /**
   * Verifies that the first recorded request matching the given path and node matcher took at
   * least the given amount of time until its correlated response arrived.
   *
   * @param pathPattern path value or regex to identify the request
   * @param rbelPath RBEL path whose value must match {@code expectedValueRegex}
   * @param expectedValueRegex value or regex to match at {@code rbelPath}
   * @param minimumSeconds minimum acceptable response duration in seconds
   */
  @Dann("prüfe, dass die erste Anfrage mit Pfad {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString} übereinstimmt, eine Antwortzeit von mindestens {int} Sekunden hat")
  @Then("verify first request to path {tigerResolvedString} with {tigerResolvedString} matching {tigerResolvedString} has a response time of at least {int} seconds")
  public void verifyFirstMatchingRequestResponseTimeAtLeastSeconds(String pathPattern, String rbelPath,
      String expectedValueRegex, int minimumSeconds) {
    var messages = RecordedMessages.snapshot();
    if (messages == null || messages.isEmpty()) {
      throw new AssertionError("No RBEL messages recorded – cannot determine response time.");
    }

    var request = findFirstRequestMatchingPathAndNode(messages, pathPattern, rbelPath, expectedValueRegex);
    if (request == null) {
      throw new AssertionError("No request found for path '" + pathPattern + "' with node '" + rbelPath
          + "' matching '" + expectedValueRegex + "'.");
    }

    var response = this.findResponseForRequest(messages, request);
    if (response == null) {
      throw new AssertionError("No matching response found for request " + request.getUuid());
    }

    var duration = this.getDurationBetweenRequestAndResponse(request, response);
    var minimumDuration = Duration.ofSeconds(Math.abs(minimumSeconds));
    if (duration.compareTo(minimumDuration) < 0) {
      throw new AssertionError("Response time for first request to path '" + pathPattern + "' with node '" + rbelPath
          + "' matching '" + expectedValueRegex + "' was " + duration.toMillis()
          + " ms, expected at least " + minimumDuration.toMillis() + " ms.");
    }
  }

  /**
   * Verifies that a request matching the given path and node matcher was sent after the currently
   * selected request and before that selected request's correlated response arrived.
   *
   * @param pathPattern path value or regex to identify the request that must occur in between
   * @param rbelPath RBEL path whose value must match {@code expectedValueRegex}
   * @param expectedValueRegex value or regex to match at {@code rbelPath}
   * @throws InterruptedException if the bounded parsing observation is interrupted
   */
  @Dann("prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad"
      + " {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString}"
      + " übereinstimmt erfolgt")
  @Then("verify a request to path {tigerResolvedString} with {tigerResolvedString} matching"
      + " {tigerResolvedString} occurs between the current request and response")
  public void verifyMatchingRequestOccursBetweenCurrentRequestAndResponse(
      String pathPattern, String rbelPath, String expectedValueRegex) throws InterruptedException {
    var messages = RecordedMessages.snapshot();
    if (messages == null || messages.isEmpty()) {
      throw new AssertionError("No RBEL messages recorded - cannot determine request timing.");
    }

    var currentRequest = RbelMessageRetriever.getInstance().getCurrentRequest();
    if (currentRequest == null) {
      throw new AssertionError("No current request available - cannot determine request timing.");
    }

    var currentResponse = this.findResponseForRequest(messages, currentRequest);
    if (currentResponse == null) {
      throw new AssertionError("No matching response found for current request "
          + currentRequest.getUuid() + ".");
    }

    var requestTime = transmissionTimeOf(currentRequest, "current request " + currentRequest.getUuid());
    var responseTime = transmissionTimeOf(currentResponse, "current response " + currentResponse.getUuid());
    var matchingRequest = BoundedObservation.await(
        () -> findFirstRequestMatchingPathAndNodeBetween(RecordedMessages.snapshot(),
            pathPattern, rbelPath, expectedValueRegex, requestTime, responseTime),
        Objects::nonNull, Duration.ofSeconds(20), Duration.ofMillis(100),
        "No request found for path '" + pathPattern + "' with node '" + rbelPath
            + "' matching '" + expectedValueRegex + "' between current request at " + requestTime
            + " and current response at " + responseTime + ".");

    var matchingTime = transmissionTimeOf(matchingRequest, "matching request " + matchingRequest.getUuid());
    SerenityReportUtils.addCustomData("Intermediate request timing",
        "Matching request " + matchingRequest.getUuid() + " occurred at " + matchingTime
            + " between current request " + currentRequest.getUuid() + " at " + requestTime
            + " and response " + currentResponse.getUuid() + " at " + responseTime + ".");
  }

  /**
   * Requires the selected request to follow the correlated response of a recorded request.
   *
   * @param requestId UUID of the recorded reference request
   * @throws AssertionError if either message or its timing is missing, or the order is invalid
   */
  @Dann("prüfe, dass die aktuelle Anfrage nach der Antwort auf Anfrage {tigerResolvedString} gesendet wurde")
  public void verifyCurrentRequestSentAfterResponse(String requestId) {
    var messages = RecordedMessages.snapshot();
    var reference = messages.stream()
        .filter(message -> requestId.equals(message.getUuid()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing reference request " + requestId));
    var response = findResponseForRequest(messages, reference);
    if (response == null) {
      throw new AssertionError("Missing correlated response for request " + requestId);
    }
    var current = RbelMessageRetriever.getInstance().getCurrentRequest();
    if (current == null) {
      throw new AssertionError("No current request selected");
    }
    if (!transmissionTimeOf(current, "current request")
        .isAfter(responseReceivedAt(messages, response))) {
      throw new AssertionError("Current request was not sent after the reference response");
    }
  }

  /**
   * Verifies that the currently selected download with the given path completed before the previous
   * response with the same path reached the configured cache lifetime. Overlapping downloads do
   * not establish that an already received cache entry was updated.
   *
   * @param pathPattern path value or regex to identify both requests
   * @param maxAgeSeconds cache lifetime in seconds that must not be reached
   */
  @Dann("prüfe, dass der aktuelle Download mit Pfad {tigerResolvedString} vor Ablauf von {int} Sekunden seit Empfang der vorherigen Antwort mit diesem Pfad vollständig abgeschlossen wurde")
  @Then("verify current download with path {tigerResolvedString} completed before {int} seconds elapsed since receiving the previous response with this path")
  public void verifyCurrentDownloadCompletedBeforePreviousMatchingResponseExpired(String pathPattern, int maxAgeSeconds) {
    var messages = RecordedMessages.snapshot();
    if (messages == null || messages.isEmpty()) {
      throw new AssertionError("No RBEL messages recorded – cannot determine request timing.");
    }

    var currentRequest = RbelMessageRetriever.getInstance().getCurrentRequest();
    if (currentRequest == null) {
      throw new AssertionError("No current request available – cannot determine request timing.");
    }
    if (!matchesPath(currentRequest, pathPattern)) {
      throw new AssertionError("Current request " + currentRequest.getUuid()
          + " does not match path pattern '" + pathPattern + "'.");
    }

    var previousRequest = findPreviousRequestMatchingPath(messages, currentRequest, pathPattern);
    if (previousRequest == null) {
      throw new AssertionError("No previous request found for path '" + pathPattern + "'.");
    }

    var previousResponse = findResponseForRequest(messages, previousRequest);
    if (previousResponse == null) {
      throw new AssertionError("No correlated previous cache response for path '" + pathPattern + "'.");
    }
    var receivedPrevious = responseReceivedAt(messages, previousResponse);
    var sentCurrent = requireTimingFacet(currentRequest, "current request").getTransmissionTime().toInstant();
    var elapsed = Duration.between(receivedPrevious, sentCurrent);
    if (maxAgeSeconds <= 0) {
      throw new AssertionError("Positive cache lifetime required");
    }
    var maxAge = Duration.ofSeconds(maxAgeSeconds);
    if (elapsed.isNegative() || elapsed.compareTo(maxAge) >= 0) {
      throw new AssertionError("Current request to path '" + pathPattern + "' was sent "
          + elapsed.toMillis() + " ms after the previous cache response, expected less than "
          + maxAge.toMillis() + " ms.");
    }
    var currentResponse = findResponseForRequest(messages, currentRequest);
    if (currentResponse == null) {
      throw new AssertionError("No correlated current download response for path '" + pathPattern + "'.");
    }
    var receivedCurrent = responseReceivedAt(messages, currentResponse);
    if (receivedCurrent.isBefore(sentCurrent)
        || Duration.between(receivedPrevious, receivedCurrent).compareTo(maxAge) >= 0) {
      throw new AssertionError("Current download to path '" + pathPattern
          + "' did not complete before the previous cache response expired.");
    }
  }

  /**
   * Finds the response message that belongs to a given request by inspecting the
   * {@link TracingMessagePairFacet} of the provided messages.
   *
   * @param messages the collection of RBEL messages to search
   * @param request  the request to find a response for
   * @return the correlated response message, or {@code null} if none is found
   */
  public RbelElement findResponseForRequest(@NonNull Collection<RbelElement> messages,
      @NonNull RbelElement request) {
    return messages.isEmpty() ? null : messages.stream()
        .map(m -> m.getFacet(TracingMessagePairFacet.class).orElse(null))
        .filter(tracingMessagePairFacet -> tracingMessagePairFacet != null
            && tracingMessagePairFacet.getRequest().getUuid().equals(request.getUuid()))
        .findFirst().map(TracingMessagePairFacet::getResponse).orElse(null);
  }

  /**
   * Calculates the elapsed time between a request and its response using
   * {@link RbelMessageTimingFacet} on both elements and logs the result.
   *
   * @param request  the request message; must provide a {@link RbelMessageTimingFacet}
   * @param response the response message; must provide a {@link RbelMessageTimingFacet}
   * @return the computed {@link Duration} between request and response transmission times
   * @throws AssertionError if either element lacks the required timing facet
   */
  private Duration getDurationBetweenRequestAndResponse(@NonNull RbelElement request,
      @NonNull RbelElement response) {
    var requestTiming = requireTimingFacet(request, "request " + request.getUuid());
    var responseTiming = requireTimingFacet(response, "response " + response.getUuid());

    var duration = Duration.between(requestTiming.getTransmissionTime(),
        responseTiming.getTransmissionTime());

    log.info("Duration between request with UUID: {} and response with UUID: {} "
            + "taken from Tiger Message logs is {} ms", request.getUuid(), response.getUuid(),
        duration.toMillis());

    return duration;
  }

  /** Assembled TCP messages inherit the first chunk's timestamp; receipt requires every chunk. */
  static Instant responseReceivedAt(Collection<RbelElement> messages, RbelElement response) {
    var received = requireTimingFacet(response, "response").getTransmissionTime().toInstant();
    var chunks = response.getFacet(SingleConnectionParserMarkerFacet.class);
    if (chunks.isEmpty()) {
      return received;
    }
    if (chunks.get().getSourceUuids().isEmpty()) {
      throw new AssertionError("Missing response chunk identities");
    }
    for (var uuid : chunks.get().getSourceUuids()) {
      var chunk = messages.stream().filter(message -> uuid.equals(message.getUuid())).findFirst()
          .orElseThrow(() -> new AssertionError("Missing response chunk timing evidence: " + uuid));
      var timestamp = requireTimingFacet(chunk, "response chunk").getTransmissionTime().toInstant();
      if (timestamp.isAfter(received)) {
        received = timestamp;
      }
    }
    return received;
  }

  /**
   * Extracts the RBEL transmission time of a message.
   *
   * @param message RBEL message
   * @param context human-readable failure context
   * @return transmission time
   */
  private Instant transmissionTimeOf(RbelElement message, String context) {
    if (message == null) {
      throw new AssertionError("Missing RBEL message for " + context + ".");
    }
    return requireTimingFacet(message, context).getTransmissionTime().toInstant();
  }

  /**
   * Finds the previous request matching a path before the current request in recorded message order.
   *
   * @param messages recorded RBEL messages
   * @param currentRequest currently selected request
   * @param pathPattern path value or regex to identify candidate requests
   * @return last matching request before {@code currentRequest}, or {@code null} if none exists
   */
  private RbelElement findPreviousRequestMatchingPath(Collection<RbelElement> messages, RbelElement currentRequest,
      String pathPattern) {
    RbelElement previousRequest = null;
    for (var message : messages) {
      if (message == null) {
        continue;
      }
      if (message.getUuid().equals(currentRequest.getUuid())) {
        return previousRequest;
      }
      if (isRequestMessage(message) && matchesPath(message, pathPattern)) {
        previousRequest = message;
      }
    }
    return previousRequest;
  }

  /**
   * Finds the first matching request by transmission time within the given exclusive time window.
   *
   * @param messages recorded RBEL messages
   * @param pathPattern path value or regex to identify the request
   * @param rbelPath RBEL path whose value must match {@code expectedValueRegex}
   * @param expectedValueRegex value or regex to match at {@code rbelPath}
   * @param earliestExclusive lower exclusive time boundary
   * @param latestExclusive upper exclusive time boundary
   * @return earliest matching request in the time window, or {@code null} if none exists
   */
  private RbelElement findFirstRequestMatchingPathAndNodeBetween(Collection<RbelElement> messages,
      String pathPattern, String rbelPath, String expectedValueRegex, Instant earliestExclusive,
      Instant latestExclusive) {
    RbelElement earliestMatch = null;
    Instant earliestMatchTime = null;
    for (var message : messages) {
      if (message == null || !isRequestMessage(message) || !matchesPath(message, pathPattern)
          || !matchesNodeValue(message, rbelPath, expectedValueRegex)) {
        continue;
      }

      var messageTime = transmissionTimeOf(message, "candidate request " + message.getUuid());
      if (!isStrictlyBetween(messageTime, earliestExclusive, latestExclusive)) {
        continue;
      }

      if (earliestMatchTime == null || messageTime.isBefore(earliestMatchTime)) {
        earliestMatch = message;
        earliestMatchTime = messageTime;
      }
    }
    return earliestMatch;
  }

  /**
   * Finds the first request matching a path and node value.
   *
   * @param messages recorded RBEL messages
   * @param pathPattern path value or regex to identify the request
   * @param rbelPath RBEL path whose value must match {@code expectedValueRegex}
   * @param expectedValueRegex value or regex to match at {@code rbelPath}
   * @return first matching request, or {@code null} if none exists
   */
  private RbelElement findFirstRequestMatchingPathAndNode(Collection<RbelElement> messages, String pathPattern,
      String rbelPath, String expectedValueRegex) {
    return messages.stream()
        .filter(Objects::nonNull)
        .filter(this::isRequestMessage)
        .filter(message -> matchesPath(message, pathPattern))
        .filter(message -> matchesNodeValue(message, rbelPath, expectedValueRegex))
        .findFirst()
        .orElse(null);
  }

  /**
   * Checks whether an instant is strictly inside an exclusive time range.
   *
   * @param value value to check
   * @param earliestExclusive lower exclusive time boundary
   * @param latestExclusive upper exclusive time boundary
   * @return {@code true} if all values are present and {@code value} is strictly between the
   *         boundaries
   */
  static boolean isStrictlyBetween(Instant value, Instant earliestExclusive, Instant latestExclusive) {
    return value != null && earliestExclusive != null && latestExclusive != null
        && value.isAfter(earliestExclusive) && value.isBefore(latestExclusive);
  }

  /**
   * Checks whether an RBEL message represents a request with a path.
   *
   * @param message the RBEL message to inspect
   * @return {@code true} if the message has a path and no response code
   */
  private boolean isRequestMessage(RbelElement message) {
    return !message.findRbelPathMembers("$.path").isEmpty()
        && message.findRbelPathMembers("$.responseCode").isEmpty();
  }

  /**
   * Checks whether a message has a path equal to or matching the path pattern.
   *
   * @param message RBEL message
   * @param pathPattern path value or regex
   * @return {@code true} if the message path matches
   */
  private boolean matchesPath(RbelElement message, String pathPattern) {
    return message.findRbelPathMembers("$.path").stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .anyMatch(actualPath -> actualPath.equals(pathPattern) || Pattern.compile(pathPattern).matcher(actualPath).find());
  }

  /**
   * Checks whether a message contains a node value matching the expected pattern.
   *
   * @param message RBEL message
   * @param rbelPath RBEL path to inspect
   * @param expectedValueRegex expected value or regex
   * @return {@code true} if at least one node value matches
   */
  private boolean matchesNodeValue(RbelElement message, String rbelPath, String expectedValueRegex) {
    Pattern pattern = Pattern.compile(expectedValueRegex);
    return message.findRbelPathMembers(rbelPath).stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .anyMatch(actualValue -> pattern.matcher(actualValue).find());
  }

}
