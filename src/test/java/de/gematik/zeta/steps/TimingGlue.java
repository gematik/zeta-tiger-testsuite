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
   * Logs a summary of all RBEL messages captured in the current test run. Uses
   * {@link RbelMessageRetriever} to obtain all recorded messages and delegates to
   * {@link #logAllMessages(Collection)} for structured logging.
   */
  @Dann("logge alle Nachrichten")
  @Then("log all messages")
  public void logAllMessages() {
    var messages = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
    logAllMessages(messages);
  }

  /**
   * Logs details for each RBEL message pair including request/response UUIDs and latency. If a
   * message does not contain a {@link TracingMessagePairFacet}, a note is logged.
   *
   * @param messages the collection of RBEL messages to log; may be {@code null}
   */
  private void logAllMessages(Collection<RbelElement> messages) {
    if (messages == null) {
      log.info("RBEL messages recorded: 0");
      return;
    }
    log.info("RBEL messages recorded: {}", messages.size());
    var index = 0;
    for (var m : messages) {
      var tracingMessagePairFacet = m.getFacet(TracingMessagePairFacet.class).orElse(null);
      if (tracingMessagePairFacet == null) {
        log.info("[{}] Message UUID {} has no TracingMessagePairFacet - skipping",
            String.format("%03d", index), m.getUuid());
        index++;
        continue;
      }
      log.info("[{}] Pair UUID {}", String.format("%03d", index), m.getUuid());
      this.getDurationBetweenRequestAndResponse(tracingMessagePairFacet.getRequest(),
          tracingMessagePairFacet.getResponse());
      index++;
    }
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
    var messages = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
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
   * Verifies that the currently selected request/response pair took at least the given number of
   * seconds.
   *
   * @param minimumSeconds minimum acceptable response duration in seconds
   */
  @Dann("prüfe, dass die Antwortzeit vom aktuellen Nachrichtenpaar mindestens {int} Sekunden beträgt")
  @Then("verify the current message pair response time is at least {int} seconds")
  public void verifyCurrentMessagePairResponseTimeAtLeastSeconds(int minimumSeconds) {
    var messages = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
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
    var minimumDuration = Duration.ofSeconds(Math.abs(minimumSeconds));
    if (duration.compareTo(minimumDuration) < 0) {
      throw new AssertionError("Current message pair response time was " + duration.toMillis()
          + " ms, expected at least " + minimumDuration.toMillis() + " ms.");
    }
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
    var messages = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
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
   */
  @Dann("prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad"
      + " {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString}"
      + " übereinstimmt erfolgt")
  @Then("verify a request to path {tigerResolvedString} with {tigerResolvedString} matching"
      + " {tigerResolvedString} occurs between the current request and response")
  public void verifyMatchingRequestOccursBetweenCurrentRequestAndResponse(
      String pathPattern, String rbelPath, String expectedValueRegex) {
    var messages = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
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
    var matchingRequest = findFirstRequestMatchingPathAndNodeBetween(
        messages, pathPattern, rbelPath, expectedValueRegex, requestTime, responseTime);
    if (matchingRequest == null) {
      throw new AssertionError("No request found for path '" + pathPattern + "' with node '" + rbelPath
          + "' matching '" + expectedValueRegex + "' between current request at " + requestTime
          + " and current response at " + responseTime + ".");
    }

    var matchingTime = transmissionTimeOf(matchingRequest, "matching request " + matchingRequest.getUuid());
    SerenityReportUtils.addCustomData("Intermediate request timing",
        "Matching request " + matchingRequest.getUuid() + " occurred at " + matchingTime
            + " between current request " + currentRequest.getUuid() + " at " + requestTime
            + " and response " + currentResponse.getUuid() + " at " + responseTime + ".");
  }

  /**
   * Verifies that the currently selected request with the given path was sent before the previous
   * request with the same path reached the configured cache lifetime.
   *
   * @param pathPattern path value or regex to identify both requests
   * @param maxAgeSeconds cache lifetime in seconds that must not be reached
   */
  @Dann("prüfe, dass die aktuelle Anfrage mit Pfad {tigerResolvedString} vor Ablauf von {int} Sekunden seit der vorherigen Anfrage mit diesem Pfad gesendet wurde")
  @Then("verify current request with path {tigerResolvedString} was sent before {int} seconds elapsed since the previous request with this path")
  public void verifyCurrentRequestWasSentBeforePreviousMatchingRequestExpired(String pathPattern, int maxAgeSeconds) {
    var messages = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
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

    var elapsed = getDurationBetweenRequests(previousRequest, currentRequest);
    var maxAge = Duration.ofSeconds(Math.abs(maxAgeSeconds));
    if (elapsed.isNegative() || elapsed.compareTo(maxAge) >= 0) {
      throw new AssertionError("Current request to path '" + pathPattern + "' was sent "
          + elapsed.toMillis() + " ms after the previous request, expected less than "
          + maxAge.toMillis() + " ms.");
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

  /**
   * Calculates the elapsed time between two request messages.
   *
   * @param previousRequest the earlier request message
   * @param currentRequest the later request message
   * @return duration between the two request transmission timestamps
   * @throws AssertionError if either request lacks the required timing facet
   */
  private Duration getDurationBetweenRequests(@NonNull RbelElement previousRequest,
      @NonNull RbelElement currentRequest) {
    var previousTiming = requireTimingFacet(previousRequest, "previous request " + previousRequest.getUuid());
    var currentTiming = requireTimingFacet(currentRequest, "current request " + currentRequest.getUuid());

    var duration = Duration.between(previousTiming.getTransmissionTime(),
        currentTiming.getTransmissionTime());

    log.info("Duration between previous request with UUID: {} and current request with UUID: {} "
            + "taken from Tiger Message logs is {} ms", previousRequest.getUuid(), currentRequest.getUuid(),
        duration.toMillis());

    return duration;
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
