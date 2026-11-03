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

import java.net.IDN;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Assertions on original OTLP JSON, selecting the operation independently of service.name. */
public final class RawOtlpTraceAssertions {
  private static final String EXPECTED_SERVICE = "ZETA Guard PEP HTTP proxy";
  private static final int MAX_FAILURE_DETAILS = 16;

  private RawOtlpTraceAssertions() {
  }

  /** One independently observed HTTP operation and its injected W3C parent context. */
  public record Operation(String traceId, String parentId, String method, String target, int status) {
    /**
     * Creates an operation from a version-00 W3C context observed on its PEP request.
     *
     * @param traceparent observed request header
     * @param method observed HTTP method
     * @param target observed PEP path
     * @param status observed response status
     * @return the correlated operation
     */
    public static Operation fromTraceparent(String traceparent, String method, String target, int status) {
      require(traceparent != null && traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"),
          "Invalid observed W3C trace context");
      var fields = traceparent.split("-");
      return new Operation(fields[1], fields[2], method, target, status);
    }

    /** Rejects unresolved/invalid correlation fixtures. */
    public Operation {
      require(traceId.matches("[0-9a-f]{32}") && !traceId.equals("0".repeat(32)), "Invalid trace identifier");
      require(parentId.matches("[0-9a-f]{16}") && !parentId.equals("0".repeat(16)), "Invalid parent identifier");
      require(List.of("GET", "POST").contains(method) && target.startsWith("/pep/") && status >= 200 && status <= 599,
          "Invalid HTTP operation fixture");
    }
  }

  /**
   * An exact profession attribute expectation, including required absence.
   *
   * @param expectedOid certificate OID to require, or null to require attribute absence
   */
  public record ProfessionExpectation(String expectedOid) {
  }

  /**
   * Independently established metadata and inclusive epoch-nanosecond bounds for one operation.
   *
   * @param route expected instrumented route, or null to omit this check
   * @param serverAddress independently observed destination FQDN, or null to omit this check
   * @param productId expected client product identifier, or null to omit this check
   * @param productVersion expected client product version, or null to omit this check
   * @param profession profession presence or absence expectation, or null to omit this check
   * @param earliestUnixNano earliest permissible span timestamp, or null with latestUnixNano
   * @param latestUnixNano latest permissible span timestamp, or null with earliestUnixNano
   */
  public record ExpectedAttributes(String route, String serverAddress, String productId, String productVersion,
      ProfessionExpectation profession, Long earliestUnixNano, Long latestUnixNano) {
    /**
     * Rejects invalid destination names and incomplete or inverted timestamp bounds.
     *
     * @throws AssertionError if the destination or timestamp bounds are invalid
     */
    public ExpectedAttributes {
      require(serverAddress == null || isServerFqdn(serverAddress),
          "Expected destination must be a valid FQDN");
      require((earliestUnixNano == null) == (latestUnixNano == null), "Both independent timestamp bounds are required");
      if (earliestUnixNano != null) {
        require(earliestUnixNano >= 0 && latestUnixNano >= earliestUnixNano, "Invalid independent timestamp bounds");
      }
    }
  }

  /**
   * Correlates an observed operation with its independently established parameter expectations.
   *
   * @param operation observed HTTP operation and parent context
   * @param expected requested metadata checks and timestamp bounds
   */
  public record Observation(Operation operation, ExpectedAttributes expected) {
    /**
     * Rejects missing operation or parameter expectations.
     *
     * @throws AssertionError if either fixture component is null
     */
    public Observation {
      require(operation != null && expected != null, "Operation and parameter expectations are required");
    }
  }

  /**
   * Checks every matching export; exact repeated deliveries are deduplicated by span identity.
   *
   * @param batches raw OTLP export objects from this capture
   * @param operations independently observed operations
   * @return false while at least one operation still has no exported PEP server span
   */
  public static boolean verifyServiceNames(List<JsonNode> batches, List<Operation> operations) {
    var unchecked = new ExpectedAttributes(null, null, null, null, null, null, null);
    return verifyParameters(batches, operations.stream().map(operation -> new Observation(operation, unchecked)).toList());
  }

  /**
   * Checks correlated exports against the requested metadata and original span timestamp bounds.
   *
   * @param batches raw OTLP export objects from this capture
   * @param observations independently observed operations and requested parameter checks
   * @return false while at least one observed operation has no exported PEP server span
   * @throws AssertionError if an exported span violates a requested check or fixtures are invalid
   */
  public static boolean verifyParameters(List<JsonNode> batches, List<Observation> observations) {
    require(!observations.isEmpty(), "No independent telemetry operations were recorded");
    boolean complete = true;
    var failures = new LinkedHashSet<String>();
    int failingExports = 0;
    for (var observation : observations) {
      var operation = observation.operation();
      var spans = new HashMap<String, JsonNode>();
      for (var batch : batches) {
        require(batch.path("resourceSpans").isArray(), "Raw OTLP resourceSpans array is missing");
        for (var resource : batch.path("resourceSpans")) {
          for (var scope : resource.path("scopeSpans")) {
            for (var span : scope.path("spans")) {
              if (!operation.traceId().equals(span.path("traceId").asString())
                  || !operation.parentId().equals(span.path("parentSpanId").asString()) || span.path("kind").asInt() != 2) {
                continue;
              }
              require("nginx".equals(scope.path("scope").path("name").asString()), "Unexpected producer for the PEP parent context");
              if (span.path("attributes").valueStream()
                  .anyMatch(value -> "http.target".equals(value.path("key").asString()))) {
                require(operation.target().equals(attribute(span, "http.target", "stringValue")),
                    "Exported span belongs to another request target");
              }
              require(operation.method().equals(attribute(span, "http.method", "stringValue")), "Exported span belongs to another HTTP method");
              require(Integer.toString(operation.status()).equals(attribute(span, "http.response.status_code", "intValue")),
                  "Exported status does not match the independently observed PEP operation");
              require(EXPECTED_SERVICE.equals(attribute(resource.path("resource"), "service.name", "stringValue")),
                  "Raw PEP resource.service.name must equal the normative value: " + EXPECTED_SERVICE);
              var spanId = span.path("spanId").asString();
              require(spanId.matches("[0-9a-f]{16}") && !spanId.equals("0".repeat(16)), "Exported span identifier is invalid");
              try {
                verifyAttributes(span, observation.expected());
              } catch (AssertionError error) {
                failingExports++;
                if (failures.size() < MAX_FAILURE_DETAILS) {
                  failures.add(operation.method() + " status " + operation.status() + " (trace "
                      + operation.traceId() + ", parent " + operation.parentId() + ", span " + spanId + "): "
                      + error.getMessage());
                }
              }
              var previous = spans.putIfAbsent(spanId, span);
              require(previous == null || previous.equals(span), "Conflicting repeated export of the same span");
            }
          }
        }
      }
      complete &= !spans.isEmpty();
    }
    var failureSummary = String.join("\n", failures);
    if (failingExports > failures.size()) {
      failureSummary += "\nAdditional failing exports omitted=" + (failingExports - failures.size());
    }
    require(failures.isEmpty(), "Raw OTLP parameter mismatches:\n" + failureSummary);
    return complete;
  }

  private static void verifyAttributes(JsonNode span, ExpectedAttributes expected) {
    var failures = new ArrayList<String>();
    var checks = List.<Runnable>of(
        () -> verifyAttribute(span, "http.route", expected.route()),
        () -> verifyServerAddress(span, expected),
        () -> verifyAttribute(span, "product_id", expected.productId()),
        () -> verifyAttribute(span, "product_version", expected.productVersion()),
        () -> verifyProfession(span, expected),
        () -> verifyTimestamps(span, expected));
    for (var check : checks) {
      try {
        check.run();
      } catch (AssertionError error) {
        failures.add(error.getMessage());
      }
    }
    require(failures.isEmpty(), String.join("; ", failures));
  }

  private static void verifyServerAddress(JsonNode span, ExpectedAttributes expected) {
    if (expected.serverAddress() != null) {
      var actual = attribute(span, "server.address", "stringValue");
      require(isServerFqdn(actual),
          "Raw OTLP server.address must be a valid FQDN");
      require(normalizeHostname(expected.serverAddress()).equals(normalizeHostname(actual)),
          "Raw OTLP server.address does not match the independently observed destination");
    }
  }

  /**
   * Converts a DNS name to lowercase ASCII and removes its optional final root label.
   *
   * @param name valid DNS name
   * @return canonical ASCII host name
   */
  private static String normalizeHostname(String name) {
    var ascii = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    return ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
  }

  /**
   * Validates a multi-label host name after conversion to its ASCII representation.
   *
   * @param name candidate host name
   * @return whether the name has valid labels and lengths and a nonnumeric final label
   */
  private static boolean isServerFqdn(String name) {
    try {
      var ascii = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES);
      var hostname = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
      var labels = hostname.split("\\.", -1);
      if (hostname.length() > 253 || labels.length < 2) {
        return false;
      }
      for (var label : labels) {
        if (label.isEmpty() || label.length() > 63) {
          return false;
        }
      }
      return !labels[labels.length - 1].chars().allMatch(c -> c >= '0' && c <= '9');
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static void verifyProfession(JsonNode span, ExpectedAttributes expected) {
    if (expected.profession() != null) {
      if (expected.profession().expectedOid() == null) {
        require(span.path("attributes").valueStream().noneMatch(a -> "profession_oid".equals(a.path("key").asString())),
            "Raw OTLP profession_oid must be absent for the observed client context");
      } else {
        verifyAttribute(span, "profession_oid", expected.profession().expectedOid());
      }
    }
  }

  private static void verifyTimestamps(JsonNode span, ExpectedAttributes expected) {
    if (expected.earliestUnixNano() != null) {
      var start = unixNano(span, "startTimeUnixNano");
      var end = unixNano(span, "endTimeUnixNano");
      require(start <= end, "Raw OTLP span ends before its start");
      require(start >= expected.earliestUnixNano() && end <= expected.latestUnixNano(),
          "Raw OTLP span timestamps are outside the independently observed operation window");
    }
  }

  private static void verifyAttribute(JsonNode span, String key, String expected) {
    if (expected != null) {
      require(expected.equals(attribute(span, key, "stringValue")), "Raw OTLP attribute does not match the observed operation: " + key);
    }
  }

  private static long unixNano(JsonNode span, String field) {
    var value = span.path(field);
    require(value.isTextual() || value.isIntegralNumber(), "Raw OTLP span timestamp must be an integer: " + field);
    var decimal = value.isTextual() ? value.textValue() : value.toString();
    require(decimal.matches("[0-9]+"), "Raw OTLP span timestamp must be an unsigned decimal integer: " + field);
    try {
      return Long.parseLong(decimal);
    } catch (NumberFormatException e) {
      throw new AssertionError("Raw OTLP span timestamp is outside the supported integer range: " + field);
    }
  }

  private static String attribute(JsonNode owner, String key, String type) {
    var values = owner.path("attributes").valueStream().filter(a -> key.equals(a.path("key").asString())).toList();
    require(values.size() == 1, "Raw OTLP attribute is missing or duplicated: " + key);
    var value = values.getFirst().path("value");
    require(value.size() == 1 && value.has(type) && value.path(type).isTextual(), "Wrong OTLP value type for " + key);
    return value.path(type).textValue();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
