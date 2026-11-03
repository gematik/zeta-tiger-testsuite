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
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.http.RbelHttpRequestFacet;
import de.gematik.rbellogger.facets.http.RbelHttpResponseFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.RawOtlpTraceAssertions.Operation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts individually correlated, completed PEP HTTP attempts from recorded messages. */
public final class RawOtlpAttempts {
  private RawOtlpAttempts() {
  }

  /**
   * Returns one operation per distinct PEP request with the supplied trace ID.
   *
   * @param messages complete recorded request and response messages
   * @param traceId independently assigned nonzero trace ID
   * @param method expected HTTP method
   * @param target expected PEP request path
   * @param status expected terminal HTTP response status
   * @return all matching attempts, each with its own observed parent ID
   * @throws AssertionError if an attempt is absent, ambiguous, incomplete or inconsistent
   */
  public static List<Operation> fromMessages(Collection<RbelElement> messages, String traceId,
      String method, String target, int status) {
    require(messages != null && traceId != null && traceId.matches("[0-9a-f]{32}")
        && !traceId.equals("0".repeat(32)), "Missing or invalid independent trace ID");
    require(method != null && List.of("GET", "POST").contains(method) && target != null
        && target.startsWith("/pep/") && status >= 200 && status <= 599,
        "Invalid independent HTTP operation metadata");
    var recorded = new LinkedHashMap<String, RbelElement>();
    for (var message : messages) {
      require(message != null && message.getUuid() != null, "Missing recorded message identity");
      recorded.putIfAbsent(message.getUuid(), message);
    }
    var parents = new HashSet<String>();
    var operations = new ArrayList<Operation>();
    for (var request : recorded.values()) {
      if (!isPepRequest(request)) {
        continue;
      }
      var contexts = request.findRbelPathMembers(node("headers.traceparent.root"));
      if (contexts.stream().noneMatch(context -> identifiesTrace(context.getRawStringContent(), traceId))) {
        continue;
      }
      require(contexts.size() == 1, "PEP request has ambiguous trace context");
      var response = terminalResponse(request, recorded);
      var actualStatus = responseStatus(response);
      var operation = Operation.fromTraceparent(contexts.getFirst().getRawStringContent(),
          value(request, node("body.method")), value(request, node("body.path")), actualStatus);
      require(method.equals(operation.method()) && target.equals(operation.target())
          && status == actualStatus, "Observed attempt differs from the independent HTTP operation");
      require(status != 401 || request.findRbelPathMembers(node("headers.authorization.root")).isEmpty(),
          "Rejected attempt still carries an Authorization header");
      require(parents.add(operation.parentId()), "Distinct PEP requests share a parent ID");
      operations.add(operation);
    }
    require(!operations.isEmpty(), "No completed PEP attempt has the independent trace ID");
    return List.copyOf(operations);
  }

  private static boolean isPepRequest(RbelElement message) {
    var paths = message.findRbelPathMembers(node("body.path"));
    return message.hasFacet(RbelHttpRequestFacet.class)
        && message.findRbelPathMembers(node("body.responseCode")).isEmpty() && paths.size() == 1
        && paths.getFirst().getRawStringContent().startsWith("/pep/");
  }

  private static boolean identifiesTrace(String context, String traceId) {
    if (context == null) {
      return false;
    }
    var fields = context.split("-", -1);
    return fields.length >= 2 && traceId.equals(fields[1]);
  }

  private static RbelElement terminalResponse(RbelElement request,
      Map<String, RbelElement> recorded) {
    var pair = request.getFacet(TracingMessagePairFacet.class)
        .orElseThrow(() -> new AssertionError("PEP attempt has no correlated response"));
    require(pair.getRequest() != null && request.getUuid().equals(pair.getRequest().getUuid())
        && pair.isResponseComplete(), "PEP response correlation is incomplete or inconsistent");
    RbelElement terminal = null;
    for (var response : pair.getResponses()) {
      require(response != null && recorded.containsKey(response.getUuid()),
          "Correlated PEP response is absent from the recorded messages");
      require(response.hasFacet(RbelHttpResponseFacet.class),
          "Correlated message is not an HTTP response");
      var responsePair = response.getFacet(TracingMessagePairFacet.class)
          .orElseThrow(() -> new AssertionError("PEP response has no request correlation"));
      require(responsePair.getRequest() != null
          && request.getUuid().equals(responsePair.getRequest().getUuid()),
          "PEP response belongs to another request");
      if (responseStatus(response) >= 200) {
        require(terminal == null, "PEP attempt has multiple terminal HTTP responses");
        terminal = response;
      }
    }
    require(terminal != null, "PEP attempt has no terminal HTTP response");
    return terminal;
  }

  private static int responseStatus(RbelElement response) {
    var status = value(response, node("body.responseCode"));
    require(status != null && status.matches("[1-5][0-9]{2}"),
        "PEP response has an invalid HTTP status");
    return Integer.parseInt(status);
  }

  private static String value(RbelElement message, String path) {
    var nodes = message.findRbelPathMembers(path);
    require(nodes.size() == 1, "Observed HTTP metadata is missing or ambiguous: " + path);
    return nodes.getFirst().getRawStringContent();
  }

  private static String node(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .filter(value -> !value.isBlank())
        .orElseThrow(() -> new AssertionError("Missing RBEL node configuration: " + key));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
