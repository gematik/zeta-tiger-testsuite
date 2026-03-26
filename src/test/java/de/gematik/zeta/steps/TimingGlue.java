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

  private RbelElement findFirstRequestMatchingPathAndNode(Collection<RbelElement> messages, String pathPattern,
      String rbelPath, String expectedValueRegex) {
    return messages.stream()
        .filter(Objects::nonNull)
        .filter(message -> matchesPath(message, pathPattern))
        .filter(message -> matchesNodeValue(message, rbelPath, expectedValueRegex))
        .findFirst()
        .orElse(null);
  }

  private boolean matchesPath(RbelElement message, String pathPattern) {
    return message.findRbelPathMembers("$.path").stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .anyMatch(actualPath -> actualPath.equals(pathPattern) || Pattern.compile(pathPattern).matcher(actualPath).find());
  }

  private boolean matchesNodeValue(RbelElement message, String rbelPath, String expectedValueRegex) {
    Pattern pattern = Pattern.compile(expectedValueRegex);
    return message.findRbelPathMembers(rbelPath).stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .anyMatch(actualValue -> pattern.matcher(actualValue).find());
  }

}
