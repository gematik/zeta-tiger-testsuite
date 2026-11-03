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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fixtures for independently correlated OTLP service names, attributes and timestamps. */
class RawOtlpTraceAssertionsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final RawOtlpTraceAssertions.Operation OPERATION = new RawOtlpTraceAssertions.Operation(
      "a".repeat(32), "b".repeat(16), "GET", "/pep/resource", 200);
  private static final long START = 1_800_000_000_000_000_000L;
  private static final long END = START + 100_000_000L;
  private static final String PROFESSION = "1.2.276.0.76.4.30";

  @Test
  void observedTraceparentKeepsTheIndependentOperationMetadata() {
    var operation = RawOtlpTraceAssertions.Operation.fromTraceparent(
        "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01", "POST", "/pep/other", 404);
    assertEquals("a".repeat(32), operation.traceId());
    assertEquals("b".repeat(16), operation.parentId());
    assertEquals("POST", operation.method());
    assertEquals("/pep/other", operation.target());
    assertEquals(404, operation.status());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "${UNRESOLVED_TRACEPARENT}", "00-a-b-01",
      "00-00000000000000000000000000000000-bbbbbbbbbbbbbbbb-01",
      "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-0000000000000000-01",
      "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01-extra"})
  void unusableObservedContextCannotProvideEvidence(String traceparent) {
    assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.Operation.fromTraceparent(traceparent, "GET", "/pep/resource", 200));
  }

  @Test
  void correctResourceNameMatchesTheIndependentOperation() {
    assertTrue(verify(export()));
  }

  @Test
  void everyExpectedOperationMustHaveAnExport() {
    var missing = new RawOtlpTraceAssertions.Operation("d".repeat(32), "e".repeat(16), "POST", "/pep/resource", 200);
    assertFalse(RawOtlpTraceAssertions.verifyServiceNames(List.of(export()), List.of(OPERATION, missing)));
    assertFalse(RawOtlpTraceAssertions.verifyServiceNames(List.of(), List.of(OPERATION)));
    assertThrows(AssertionError.class, () -> RawOtlpTraceAssertions.verifyServiceNames(List.of(export()), List.of()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"nginx", "ZETA Guard PEP HTTP Proxy", "", "ZETA Guard PEP HTTP proxy extra"})
  void wrongServiceNameCannotDisappearFromSelection(String wrong) {
    var batch = export();
    ((ObjectNode) batch.at("/resourceSpans/0/resource/attributes/0/value")).put("stringValue", wrong);
    assertThrows(AssertionError.class, () -> verify(batch));
  }

  @Test
  void missingOrNonstringServiceNameFailsEvenWithMatchingSpanAttribute() {
    var batch = export();
    var value = (ObjectNode) batch.at("/resourceSpans/0/resource/attributes/0/value");
    value.removeAll();
    value.put("intValue", "200");
    var attributes = (tools.jackson.databind.node.ArrayNode) span(batch).path("attributes");
    attributes.addObject().put("key", "service.name").putObject("value").put("stringValue", "ZETA Guard PEP HTTP proxy");
    assertThrows(AssertionError.class, () -> verify(batch));
    value.removeAll();
    assertThrows(AssertionError.class, () -> verify(batch));
  }

  @ParameterizedTest
  @ValueSource(strings = {"traceId", "parentSpanId"})
  void unrelatedContextDoesNotSatisfyTheExpectedOperation(String field) {
    var batch = export();
    span(batch).put(field, "d".repeat(field.equals("traceId") ? 32 : 16));
    assertFalse(verify(batch));
  }

  @Test
  void nonserverSpanCannotSatisfyTheOperation() {
    var batch = export();
    span(batch).put("kind", 3);
    assertFalse(verify(batch));
  }

  @ParameterizedTest
  @ValueSource(strings = {"http.method", "http.target", "http.response.status_code"})
  void wrongHttpOperationCannotProvideNameEvidence(String key) {
    var batch = export();
    for (var attribute : span(batch).path("attributes")) {
      if (key.equals(attribute.path("key").asString())) {
        var value = (ObjectNode) attribute.path("value");
        value.put(key.endsWith("status_code") ? "intValue" : "stringValue", "other");
      }
    }
    assertThrows(AssertionError.class, () -> verify(batch));
  }

  @Test
  void missingTargetAllowsServiceNameAndParameterEvidence() {
    var batch = parameterExport();
    removeAttribute(batch, "http.target");
    assertTrue(verify(batch));
    assertTrue(verifyParameters(batch, expectedParameters()));
  }

  @Test
  void duplicatedTargetCannotProvideEvidence() {
    var batch = parameterExport();
    ((ArrayNode) span(batch).path("attributes")).add(attribute(batch, "http.target").deepCopy());
    assertThrows(AssertionError.class, () -> verify(batch));
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"intValue", "null", "missing"})
  void presentTargetRequiresAStringValue(String type) {
    var batch = parameterExport();
    var value = (ObjectNode) attribute(batch, "http.target").path("value");
    value.removeAll();
    if ("null".equals(type)) {
      value.putNull("stringValue");
    } else if (!"missing".equals(type)) {
      value.put(type, "200");
    }
    assertThrows(AssertionError.class, () -> verify(batch));
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"http.method", "http.response.status_code", "http.route", "server.address",
      "product_id", "product_version", "profession_oid"})
  void missingTargetDoesNotMakeRequiredAttributesOptional(String key) {
    var batch = parameterExport();
    removeAttribute(batch, "http.target");
    removeAttribute(batch, key);
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"traceId", "parentSpanId"})
  void missingTargetDoesNotMatchAnUnrelatedContext(String field) {
    var batch = parameterExport();
    removeAttribute(batch, "http.target");
    span(batch).put(field, "d".repeat(field.equals("traceId") ? 32 : 16));
    assertFalse(verify(batch));
    assertFalse(verifyParameters(batch, expectedParameters()));
  }

  @Test
  void malformedTargetCannotExposeRequestContentsInDiagnostics() {
    var batch = export();
    ((ObjectNode) batch.at("/resourceSpans/0/scopeSpans/0/spans/0/attributes/1/value"))
        .put("stringValue", "http://example.invalid/?token=sensitive sentinel");
    var error = assertThrows(AssertionError.class, () -> verify(batch));
    assertFalse(error.toString().contains("sensitive"));
  }

  @Test
  void independentTargetQueryIsAbsentFromParameterFailureDiagnostics() {
    var target = "/pep/resource?token=sensitive-query-sentinel";
    var operation = new RawOtlpTraceAssertions.Operation(
        OPERATION.traceId(), OPERATION.parentId(), OPERATION.method(), target, OPERATION.status());
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "http.target").path("value")).put("stringValue", target);
    ((ObjectNode) attribute(batch, "product_id").path("value"))
        .put("stringValue", "sensitive-attribute-sentinel");
    var error = assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.verifyParameters(List.of(batch),
            List.of(new RawOtlpTraceAssertions.Observation(operation, expectedParameters()))));
    assertTrue(error.getMessage().contains("product_id"));
    assertTrue(error.getMessage().contains(operation.traceId()));
    assertTrue(error.getMessage().contains(operation.parentId()));
    assertTrue(error.getMessage().contains("GET status 200"));
    assertNoSensitiveDiagnostics(error, target, "sensitive-query-sentinel", "sensitive-attribute-sentinel");
  }

  @Test
  void invalidSpanIdentifierCannotEnterParameterFailureDiagnostics() {
    var batch = parameterExport();
    span(batch).put("spanId", "sensitive-span-sentinel");
    ((ObjectNode) attribute(batch, "product_id").path("value"))
        .put("stringValue", "sensitive-attribute-sentinel");
    var error = assertThrows(AssertionError.class,
        () -> verifyParameters(batch, expectedParameters()));
    assertEquals("Exported span identifier is invalid", error.getMessage());
    assertNoSensitiveDiagnostics(error, "sensitive-span-sentinel", "sensitive-attribute-sentinel");
  }

  @Test
  void identicalRepeatedDeliveriesAreAllowed() {
    var first = export();
    assertTrue(RawOtlpTraceAssertions.verifyServiceNames(List.of(first, first.deepCopy()), List.of(OPERATION)));
  }

  @Test
  void distinctConformingSpansForTheSameOperationContextAreAllowed() {
    var first = export();
    var second = first.deepCopy();
    span(second).put("spanId", "e".repeat(16)).put("endTimeUnixNano", "9999");
    assertTrue(RawOtlpTraceAssertions.verifyServiceNames(List.of(first, second), List.of(OPERATION)));
  }

  @Test
  void conflictingRepeatedDeliveriesOfTheSameSpanFail() {
    var first = export();
    var second = first.deepCopy();
    span(second).put("endTimeUnixNano", "9999");
    assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.verifyServiceNames(List.of(first, second), List.of(OPERATION)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"c", "e"})
  void wrongServiceNameInAnyFurtherExportFails(String spanIdPrefix) {
    var first = export();
    var second = first.deepCopy();
    span(second).put("spanId", spanIdPrefix.repeat(16));
    ((ObjectNode) second.at("/resourceSpans/0/resource/attributes/0/value"))
        .put("stringValue", "other");
    var error = assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.verifyServiceNames(List.of(first, second), List.of(OPERATION)));
    assertTrue(error.getMessage().contains("resource.service.name"));
  }

  @Test
  void independentlyExpectedParametersAndInclusiveTimestampBoundsMatch() {
    assertTrue(verifyParameters(parameterExport(), expectedParameters()));
  }

  @Test
  void unrequestedMetadataAndTimestampsMayBeAbsent() {
    assertTrue(verifyParameters(export(), new RawOtlpTraceAssertions.ExpectedAttributes(null, null, null, null, null, null, null)));
  }

  @Test
  void requestedRouteDoesNotRequireUnavailableClientMetadata() {
    var batch = export();
    ((ArrayNode) span(batch).path("attributes")).addObject().put("key", "http.route")
        .putObject("value").put("stringValue", "/pep/{resource}");
    var expected = new RawOtlpTraceAssertions.ExpectedAttributes("/pep/{resource}", null, null, null, null, null, null);
    assertTrue(verifyParameters(batch, expected));
  }

  @ParameterizedTest
  @ValueSource(strings = {"http.route", "server.address", "product_id", "product_version", "profession_oid"})
  void requestedAttributeCannotBeMissingOrWrong(String key) {
    var missing = parameterExport();
    attribute(missing, key).put("key", "unrelated");
    assertThrows(AssertionError.class, () -> verifyParameters(missing, expectedParameters()));
    var wrong = parameterExport();
    ((ObjectNode) attribute(wrong, key).path("value")).put("stringValue", "other");
    assertThrows(AssertionError.class, () -> verifyParameters(wrong, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"guard.example.org", "zeta-kind.local",
      "pep-proxy-svc.zeta-local.svc.cluster.local", "zeta-kind.local.", "ZETA-KIND.LOCAL",
      "guard.internal", "guard.test", "guard.invalid", "guard.example",
      "bücher.example", "xn--bcher-kva.example", "guard。local", "guard.local．"})
  void validServerFqdnMustEqualTheObservedDestination(String serverAddress) {
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", serverAddress);
    assertTrue(verifyParameters(batch, expectedServer(serverAddress)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"different.example.org", "resource.example.org.attacker.invalid"})
  void unrelatedValidFqdnCannotReplaceTheObservedDestination(String serverAddress) {
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", serverAddress);
    var error = assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
    assertTrue(error.getMessage().contains("server.address does not match"));
    assertNoSensitiveDiagnostics(error, serverAddress);
  }

  @ParameterizedTest
  @ValueSource(strings = {"RESOURCE.EXAMPLE.ORG", "resource.example.org."})
  void destinationComparisonIgnoresDnsCaseAndTheRootDot(String serverAddress) {
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", serverAddress);
    assertTrue(verifyParameters(batch, expectedParameters()));
  }

  @Test
  void destinationComparisonAcceptsEquivalentInternationalizedNames() {
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value"))
        .put("stringValue", "xn--bcher-kva.example");
    assertTrue(verifyParameters(batch, expectedServer("bücher.example")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "localhost", "https://resource.example.org", "resource.example.org:443"})
  void expectedDestinationMustBeAValidFqdn(String serverAddress) {
    assertThrows(AssertionError.class, () -> expectedServer(serverAddress));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "pep-proxy-svc", "localhost", "127.0.0.1", "::1", "[::1]",
      "https://zeta-kind.local/pep/", "zeta-kind.local:443", "bad_name.local", "-guard.local",
      "guard-.local", "a..local", " guard.local", "guard.local/path", "*.local",
      ".", "guard.local..", "127.1", "guard.123", "guard.local ", "guard..", "a。。local"})
  void invalidServerFqdnCannotProvideParameterEvidence(String serverAddress) {
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", serverAddress);
    var error = assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
    assertTrue(error.getMessage().contains("server.address must be a valid FQDN"));
  }

  @Test
  void overlongServerDomainAndLabelCannotProvideParameterEvidence() {
    for (var serverAddress : List.of("a".repeat(64) + ".local", ("a".repeat(63) + ".").repeat(4) + "local",
        ("ü".repeat(57) + ".").repeat(4) + "local")) {
      var batch = parameterExport();
      ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", serverAddress);
      assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
    }
  }

  @Test
  void maximumServerDomainLengthAllowsAnOptionalRootDot() {
    var hostname = ("a".repeat(63) + ".").repeat(3) + "a".repeat(61);
    assertEquals(253, hostname.length());
    for (var serverAddress : List.of(hostname, hostname + ".")) {
      var batch = parameterExport();
      ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", serverAddress);
      assertTrue(verifyParameters(batch, expectedServer(hostname)));
    }
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", hostname + "a");
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @Test
  void idnServerLabelLengthIsCheckedAfterAsciiConversion() {
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value"))
        .put("stringValue", "ü".repeat(57) + ".local");
    assertTrue(verifyParameters(batch, expectedServer("ü".repeat(57) + ".local")));
    ((ObjectNode) attribute(batch, "server.address").path("value"))
        .put("stringValue", "ü".repeat(58) + ".local");
    var error = assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
    assertTrue(error.getMessage().contains("server.address must be a valid FQDN"));
    assertNoSensitiveDiagnostics(error, "ü".repeat(58));
  }

  @Test
  void nullServerAddressCannotProvideParameterEvidence() {
    var batch = parameterExport();
    ((ObjectNode) attribute(batch, "server.address").path("value")).putNull("stringValue");
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @Test
  void invalidServerAddressDoesNotExposeAttributeContentsInDiagnostics() {
    var batch = parameterExport();
    var serverAddress = "https://example.org/?token=sensitive-fqdn-sentinel";
    ((ObjectNode) attribute(batch, "server.address").path("value")).put("stringValue", serverAddress);
    var error = assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
    assertNoSensitiveDiagnostics(error, serverAddress, "sensitive-fqdn-sentinel");
  }

  @ParameterizedTest
  @ValueSource(strings = {"http.route", "server.address", "product_id", "product_version", "profession_oid"})
  void requestedStringAttributeMustHaveOneStringValueAndOneOccurrence(String key) {
    var wrongType = parameterExport();
    var value = (ObjectNode) attribute(wrongType, key).path("value");
    value.removeAll();
    value.put("intValue", "42");
    assertThrows(AssertionError.class, () -> verifyParameters(wrongType, expectedParameters()));
    var duplicate = parameterExport();
    ((ArrayNode) span(duplicate).path("attributes")).add(attribute(duplicate, key).deepCopy());
    assertThrows(AssertionError.class, () -> verifyParameters(duplicate, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"product_id", "product_version", "profession_oid"})
  void dottedAliasesCannotReplaceTheRequiredAttribute(String key) {
    var batch = parameterExport();
    attribute(batch, key).put("key", key.replace('_', '.'));
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @Test
  void requestTargetCannotReplaceTheExpectedRoute() {
    var batch = parameterExport();
    attribute(batch, "http.route").put("key", "unrelated");
    var expected = new RawOtlpTraceAssertions.ExpectedAttributes(OPERATION.target(), null, null, null, null, null, null);
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expected));
  }

  @Test
  void professionAbsenceRequiresNoAttributeRatherThanAnEmptyValue() {
    var expected = new RawOtlpTraceAssertions.ExpectedAttributes(null, null, null, null,
        new RawOtlpTraceAssertions.ProfessionExpectation(null), null, null);
    assertTrue(verifyParameters(export(), expected));
    var present = parameterExport();
    assertThrows(AssertionError.class, () -> verifyParameters(present, expected));
    ((ObjectNode) attribute(present, "profession_oid").path("value")).put("stringValue", "");
    assertThrows(AssertionError.class, () -> verifyParameters(present, expected));
  }

  @ParameterizedTest
  @ValueSource(strings = {"startTimeUnixNano", "endTimeUnixNano"})
  void requestedTimestampCannotBeMissing(String field) {
    var batch = parameterExport();
    span(batch).remove(field);
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "-1", "+1", "1.5", "1e18", " 1800000000000000000", "18446744073709551616"})
  void malformedOrUnsupportedTimestampCannotProvideTimeEvidence(String timestamp) {
    var batch = parameterExport();
    span(batch).put("startTimeUnixNano", timestamp);
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"startTimeUnixNano", "endTimeUnixNano"})
  void overflowingTimestampCannotExposeTheOriginalDecimalInDiagnostics(String field) {
    var batch = parameterExport();
    var decimal = "9876543210".repeat(1000);
    span(batch).put(field, decimal);
    var error = assertThrows(AssertionError.class,
        () -> verifyParameters(batch, expectedParameters()));
    assertTrue(error.getMessage().contains("outside the supported integer range: " + field));
    assertNoSensitiveDiagnostics(error, decimal, "9876543210");
  }

  @Test
  void fractionalAndNullTimestampNodesCannotProvideTimeEvidence() {
    var batch = parameterExport();
    span(batch).put("startTimeUnixNano", 1.8);
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
    span(batch).putNull("startTimeUnixNano");
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @Test
  void integerJsonTimestampsRetainTheEpochNanosecondValue() {
    var batch = parameterExport();
    span(batch).put("startTimeUnixNano", START).put("endTimeUnixNano", END);
    assertTrue(verifyParameters(batch, expectedParameters()));
  }

  @Test
  void timestampUnitAndResolutionDoNotDependOnADigitCount() {
    var batch = parameterExport();
    span(batch).put("startTimeUnixNano", "1000").put("endTimeUnixNano", "2000");
    var expected = new RawOtlpTraceAssertions.ExpectedAttributes(null, null, null, null, null, 1000L, 2000L);
    assertTrue(verifyParameters(batch, expected));
  }

  @Test
  void timestampsMustBeOrderedAndStayWithinTheIndependentWindow() {
    var beforeWindow = parameterExport();
    span(beforeWindow).put("startTimeUnixNano", Long.toString(START - 1));
    assertThrows(AssertionError.class, () -> verifyParameters(beforeWindow, expectedParameters()));
    var afterWindow = parameterExport();
    span(afterWindow).put("endTimeUnixNano", Long.toString(END + 1));
    assertThrows(AssertionError.class, () -> verifyParameters(afterWindow, expectedParameters()));
    var inverted = parameterExport();
    span(inverted).put("startTimeUnixNano", Long.toString(END)).put("endTimeUnixNano", Long.toString(START));
    assertThrows(AssertionError.class, () -> verifyParameters(inverted, expectedParameters()));
  }

  @ParameterizedTest
  @ValueSource(longs = {1000, 1000000})
  void convertedMicrosecondOrMillisecondValuesDoNotMatchTheNanosecondWindow(long divisor) {
    var batch = parameterExport();
    span(batch).put("startTimeUnixNano", Long.toString(START / divisor)).put("endTimeUnixNano", Long.toString(END / divisor));
    assertThrows(AssertionError.class, () -> verifyParameters(batch, expectedParameters()));
  }

  @Test
  void parameterMismatchInALaterExportCannotBeHiddenByAnEarlierMatchingSpan() {
    var first = parameterExport();
    var second = parameterExport();
    span(second).put("spanId", "e".repeat(16));
    ((ObjectNode) attribute(second, "product_version").path("value")).put("stringValue", "other");
    assertThrows(AssertionError.class, () -> RawOtlpTraceAssertions.verifyParameters(List.of(first, second),
        List.of(new RawOtlpTraceAssertions.Observation(OPERATION, expectedParameters()))));
  }

  @Test
  void reportsIndependentParameterFailuresAcrossEveryExportedSpan() {
    var first = parameterExport();
    ((ObjectNode) attribute(first, "server.address").path("value"))
        .put("stringValue", "unqualified-host");
    attribute(first, "product_id").put("key", "product.id");
    span(first).put("endTimeUnixNano", Long.toString(END + 1));
    var second = parameterExport();
    span(second).put("spanId", "e".repeat(16));
    ((ObjectNode) attribute(second, "product_version").path("value"))
        .put("stringValue", "other");
    var error = assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.verifyParameters(List.of(first, second),
            List.of(new RawOtlpTraceAssertions.Observation(OPERATION, expectedParameters()))));
    for (var expected : List.of("server.address", "product_id", "product_version",
        "independently observed operation window", "c".repeat(16), "e".repeat(16))) {
      assertTrue(error.getMessage().contains(expected), error.getMessage());
    }
  }

  @Test
  void parameterFailureDiagnosticsHaveAFixedDetailBound() {
    var batches = parameterMismatchExports(100);
    var error = assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.verifyParameters(batches,
            List.of(new RawOtlpTraceAssertions.Observation(OPERATION, expectedParameters()))));
    assertEquals(16, error.getMessage().lines().filter(line -> line.startsWith("GET status ")).count());
    assertTrue(error.getMessage().contains("Additional failing exports omitted=84"));
    assertTrue(error.getMessage().length() < 5000);
  }

  @Test
  void cappedParameterDiagnosticsStillRejectALaterInvalidSpanIdentifier() {
    var batches = parameterMismatchExports(100);
    var invalid = parameterExport();
    span(invalid).put("spanId", "sensitive-late-span-sentinel");
    batches.add(invalid);
    var error = assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.verifyParameters(batches,
            List.of(new RawOtlpTraceAssertions.Observation(OPERATION, expectedParameters()))));
    assertEquals("Exported span identifier is invalid", error.getMessage());
    assertNoSensitiveDiagnostics(error, "sensitive-late-span-sentinel");
  }

  @Test
  void cappedParameterDiagnosticsStillRejectALaterConflictingExport() {
    var batches = parameterMismatchExports(100);
    var conflicting = (ObjectNode) batches.getFirst().deepCopy();
    span(conflicting).put("endTimeUnixNano", Long.toString(END + 1));
    batches.add(conflicting);
    var error = assertThrows(AssertionError.class,
        () -> RawOtlpTraceAssertions.verifyParameters(batches,
            List.of(new RawOtlpTraceAssertions.Observation(OPERATION, expectedParameters()))));
    assertEquals("Conflicting repeated export of the same span", error.getMessage());
  }

  @Test
  void incompleteOrInvertedIndependentBoundsAreRejected() {
    assertThrows(AssertionError.class, () -> new RawOtlpTraceAssertions.ExpectedAttributes(null, null, null, null, null, START, null));
    assertThrows(AssertionError.class, () -> new RawOtlpTraceAssertions.ExpectedAttributes(null, null, null, null, null, null, END));
    assertThrows(AssertionError.class, () -> new RawOtlpTraceAssertions.ExpectedAttributes(null, null, null, null, null, END, START));
  }

  @ParameterizedTest
  @ValueSource(strings = {"otlp/gematik", "otlp/renamed"})
  void capturePipelinePreservesExistingRoutesAndHasNoProcessors(String receiver) {
    var original = JSON.readTree("""
        {"receivers":{"otlp/gematik":{}},"exporters":{"otlp/jaeger":{}},
         "service":{"pipelines":{"traces/ti_sim":{"receivers":["otlp/gematik"],"exporters":["otlp/jaeger"],"processors":["batch"]}}}}
        """.replace("otlp/gematik", receiver));
    var changed = RawOtlpCapture.withCapturePipeline(original, receiver);
    assertTrue(original.at("/service/pipelines").path("traces/ti_sim").equals(changed.at("/service/pipelines").path("traces/ti_sim")));
    assertTrue(changed.at("/service/pipelines").path("traces/cc_raw_capture").path("processors").isEmpty());
    assertEquals(receiver, changed.at("/service/pipelines").path("traces/cc_raw_capture").path("receivers").get(0).asString());
    assertFalse(original.path("exporters").has("file/cc_raw_capture"));
    assertThrows(AssertionError.class, () -> RawOtlpCapture.withCapturePipeline(changed, receiver));
  }

  private static boolean verify(ObjectNode batch) {
    return RawOtlpTraceAssertions.verifyServiceNames(List.of(batch), List.of(OPERATION));
  }

  /**
   * Checks rendered exception messages, causes and suppressed failures for forbidden values.
   *
   * @param error exception to render
   * @param forbidden values that must not occur in the rendered diagnostics
   */
  private static void assertNoSensitiveDiagnostics(Throwable error, String... forbidden) {
    var rendered = new StringWriter();
    error.printStackTrace(new PrintWriter(rendered));
    for (var value : forbidden) {
      assertFalse(rendered.toString().contains(value), "Exception diagnostics contain a forbidden value");
    }
  }

  /**
   * Creates exports with distinct span identifiers and one incorrect product attribute each.
   *
   * @param count number of exports
   * @return mutable export list
   */
  private static List<JsonNode> parameterMismatchExports(int count) {
    var batches = new ArrayList<JsonNode>();
    for (int index = 1; index <= count; index++) {
      var batch = parameterExport();
      span(batch).put("spanId", String.format("%016x", index));
      ((ObjectNode) attribute(batch, "product_id").path("value")).put("stringValue", "other");
      batches.add(batch);
    }
    return batches;
  }

  private static ObjectNode span(ObjectNode batch) {
    return (ObjectNode) batch.at("/resourceSpans/0/scopeSpans/0/spans/0");
  }

  private static boolean verifyParameters(ObjectNode batch, RawOtlpTraceAssertions.ExpectedAttributes expected) {
    return RawOtlpTraceAssertions.verifyParameters(List.of(batch), List.of(new RawOtlpTraceAssertions.Observation(OPERATION, expected)));
  }

  /**
   * Creates independent attribute expectations for one observed destination.
   *
   * @param serverAddress observed destination FQDN
   * @return complete parameter expectations with the supplied destination
   */
  private static RawOtlpTraceAssertions.ExpectedAttributes expectedServer(String serverAddress) {
    var expected = expectedParameters();
    return new RawOtlpTraceAssertions.ExpectedAttributes(expected.route(), serverAddress,
        expected.productId(), expected.productVersion(), expected.profession(),
        expected.earliestUnixNano(), expected.latestUnixNano());
  }

  private static RawOtlpTraceAssertions.ExpectedAttributes expectedParameters() {
    return new RawOtlpTraceAssertions.ExpectedAttributes("/pep/{resource}", "resource.example.org", "client-product", "2.1.3",
        new RawOtlpTraceAssertions.ProfessionExpectation(PROFESSION), START, END);
  }

  private static ObjectNode attribute(ObjectNode batch, String key) {
    return (ObjectNode) span(batch).path("attributes").valueStream()
        .filter(value -> key.equals(value.path("key").asString())).findFirst().orElseThrow();
  }

  /**
   * Removes all span attributes with the supplied key from a fixture.
   *
   * @param batch fixture export
   * @param key attribute key to remove
   */
  private static void removeAttribute(ObjectNode batch, String key) {
    var attributes = (ArrayNode) span(batch).path("attributes");
    for (int index = attributes.size() - 1; index >= 0; index--) {
      if (key.equals(attributes.get(index).path("key").asString())) {
        attributes.remove(index);
      }
    }
  }

  private static ObjectNode parameterExport() {
    var batch = export();
    span(batch).put("startTimeUnixNano", Long.toString(START)).put("endTimeUnixNano", Long.toString(END));
    var attributes = (ArrayNode) span(batch).path("attributes");
    attributes.addObject().put("key", "http.route").putObject("value").put("stringValue", "/pep/{resource}");
    attributes.addObject().put("key", "server.address").putObject("value").put("stringValue", "resource.example.org");
    attributes.addObject().put("key", "product_id").putObject("value").put("stringValue", "client-product");
    attributes.addObject().put("key", "product_version").putObject("value").put("stringValue", "2.1.3");
    attributes.addObject().put("key", "profession_oid").putObject("value").put("stringValue", PROFESSION);
    return batch;
  }

  private static ObjectNode export() {
    return (ObjectNode) JSON.readTree("""
        {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"ZETA Guard PEP HTTP proxy"}}]},
         "scopeSpans":[{"scope":{"name":"nginx"},"spans":[{"traceId":"%s","parentSpanId":"%s","spanId":"%s","kind":2,
         "attributes":[{"key":"http.method","value":{"stringValue":"GET"}},
                       {"key":"http.target","value":{"stringValue":"/pep/resource"}},
                       {"key":"http.response.status_code","value":{"intValue":"200"}}]}]}]}]}
        """.formatted("a".repeat(32), "b".repeat(16), "c".repeat(16)));
  }
}
