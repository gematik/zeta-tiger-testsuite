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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import com.sun.net.httpserver.HttpServer;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.RbelModificationDescription;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.JwtVariant;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies Access Token manipulation requests and direct route management, including server failures.
 */
class TigerProxyManipulationsStepsTest {

  /**
   * Preserves the existing signed, JWK and Access Token configuration paths.
   *
   * @param route configuration path
   */
  @ParameterizedTest
  @ValueSource(strings = {"signed", "jwk", "access"})
  void configuresVariantsThroughExistingManipulations(String route) {
    for (var variant : List.of(JwtVariant.UNSUPPORTED_ALG, JwtVariant.DUPLICATE_ALG_HEADERS,
        JwtVariant.RESIGN_CORRECT_KEY)) {
      var received = new AtomicReference<List<Object>>();
      var steps = new TigerProxyManipulationsSteps() {
        @Override
        public void setTigerProxyJwtManipulationWithCondition(String location, String field,
            String value, String key, String condition, Integer executions) {
          received.set(List.of("signed", location, field, value, key, condition, executions));
        }

        @Override
        public void setTigerProxyJwtManipulationWithConditionAndReplaceJwk(String location,
            String field, String value, String key, String condition, Integer executions) {
          received.set(List.of("jwk", location, field, value, key, condition, executions));
        }

        @Override
        public void setTigerProxyAccessTokenManipulationWithAthUpdate(String field, String value,
            String accessKey, String dpopKey, String condition, Integer executions) {
          received.set(List.of("access", "access-token", field, value, accessKey,
              condition, executions, dpopKey));
        }
      };
      var algorithms = variant == JwtVariant.DUPLICATE_ALG_HEADERS
          ? "[\"RS999\",\"ES256\"]" : "";
      switch (route) {
        case "signed" -> steps.setSignedJwtVariant("$.body.subject_token", variant,
            algorithms, "signing-key", "/token", 1);
        case "jwk" -> steps.setSignedJwtVariantReplacingJwk("$.body.subject_token", variant,
            algorithms, "signing-key", "/token", 1);
        case "access" -> steps.setAccessTokenVariant(variant, algorithms,
            "signing-key", "dpop-key", "/token", 1);
        default -> throw new AssertionError(route);
      }
      var expectedField = switch (variant) {
        case DUPLICATE_ALG_HEADERS -> "variant.duplicate_alg_headers";
        case RESIGN_CORRECT_KEY -> "header.alg";
        default -> "variant.name";
      };
      var expectedValue = switch (variant) {
        case DUPLICATE_ALG_HEADERS -> "[\"RS999\",\"ES256\"]";
        case RESIGN_CORRECT_KEY -> "ES256";
        default -> "unsupported_alg";
      };
      assertThat(received.get()).startsWith(route,
          route.equals("access") ? "access-token" : "$.body.subject_token",
          expectedField, expectedValue, "signing-key", "/token", 1);
      if (route.equals("access")) {
        assertThat(received.get()).endsWith("dpop-key");
      }
    }
  }

  /** Verifies that algorithm values cannot be silently omitted or ignored. */
  @Test
  void rejectsMissingOrUnexpectedAlgorithmParameters() {
    assertThatThrownBy(() -> JwtVariant.DUPLICATE_ALG_HEADERS.manipulationValue(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JwtVariant.UNSUPPORTED_ALG.manipulationValue("[\"ES256\",\"RS999\"]"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Rejects invalid duplicate algorithms before entering any proxy configuration step.
   *
   * @param parameter malformed or incomplete algorithm parameter
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"[]", "[\"ES256\"]", "{}", "null", "[null,\"RS999\"]",
      "[1,\"RS999\"]", "[true,\"RS999\"]", "[\"\",\"RS999\"]",
      "[\" \",\"RS999\"]", "[\"ES256\",", "", " ", "ES256,RS999",
      "[\"ES256\",\"RS999\"] []"})
  void rejectsDuplicateAlgorithmsBeforeConfiguringProxy(String parameter) {
    var steps = new TigerProxyManipulationsSteps() {
      @Override
      public void setTigerProxyJwtManipulationWithCondition(String location, String field,
          String value, String key, String condition, Integer executions) {
        throw new AssertionError("Invalid parameter reached signed JWT configuration");
      }

      @Override
      public void setTigerProxyJwtManipulationWithConditionAndReplaceJwk(String location,
          String field, String value, String key, String condition, Integer executions) {
        throw new AssertionError("Invalid parameter reached JWK configuration");
      }

      @Override
      public void setTigerProxyAccessTokenManipulationWithAthUpdate(String field, String value,
          String accessKey, String dpopKey, String condition, Integer executions) {
        throw new AssertionError("Invalid parameter reached Access Token configuration");
      }
    };
    assertThatThrownBy(() -> steps.setSignedJwtVariant("$.body.subject_token",
        JwtVariant.DUPLICATE_ALG_HEADERS, parameter, "key", "/token", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> steps.setSignedJwtVariantReplacingJwk("$.header.dpop",
        JwtVariant.DUPLICATE_ALG_HEADERS, parameter, "key", "/token", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> steps.setAccessTokenVariant(JwtVariant.DUPLICATE_ALG_HEADERS,
        parameter, "key", "dpop-key", "/resource", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolvesStructuredRulesAndDelegatesToTheExistingBoundedManipulation() {
    var calls = new AtomicInteger();
    var steps = new TigerProxyManipulationsSteps() {
      @Override
      public void setTigerProxyManipulationWithExecutions(String condition, String field, String value, Integer executions) {
        calls.incrementAndGet();
        assertThat(condition).contains("isResponse", "own-client", "token-exchange");
        assertThat(field).isEqualTo("$.body.result");
        assertThat(new ObjectMapper().readTree(value).path("aud").asString()).isEqualTo("https://route.example");
        assertThat(executions).isEqualTo(20);
      }
    };
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(call -> ((String) call.getArgument(0)).replace("${client}", "own-client"));
      steps.setTigerProxyStructuredManipulation("""
          {
            "when": {
              "isResponse": true,
              "requestPathMatches": ".*/decision",
              "requestBody": {
                "$.input.client_id": "${client}",
                "$.input.grant_type": "token-exchange"
              }
            },
            "replace": {"path": "$.body.result", "value": {"aud": "https://route.example"}},
            "executions": 20
          }
          """);
      assertThat(calls).hasValue(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"client\"quoted", "client\\backslash", "client\"quoted\\path", "client\\nend", "client'\\$.body@.field\nend"})
  void structuredPlaceholdersRemainLiteralInSelectorsAndNestedReplacementValues(String client) {
    var mapper = new ObjectMapper();
    var logger = RbelLogger.build();
    var calls = new AtomicInteger();
    var steps = new TigerProxyManipulationsSteps() {
      @Override
      public void setTigerProxyManipulationWithExecutions(String condition, String field, String value, Integer executions) {
        calls.incrementAndGet();
        assertThat(field).isEqualTo("$.body.result");
        assertThat(executions).isEqualTo(20);
        logger.getRbelModifier().addModification(RbelModificationDescription.builder()
            .name("resolved-structured-rule").condition(condition).targetElement(field)
            .replaceWith(value).deleteAfterNExecutions(executions).build());
      }
    };
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(call -> ((String) call.getArgument(0))
              .replace("${client}", client).replace("${selector}", "$.input.client_id")
              .replace("${requestPath}", "\\Q/decision\\E").replace("${target}", "$.body.result"));
      steps.setTigerProxyStructuredManipulation("""
          {
            "when": {
              "isResponse": true,
              "requestPathMatches": "${requestPath}",
              "requestBody": {"${selector}": "${client}"}
            },
            "replace": {
              "path": "${target}",
              "value": {"client": "${client}", "${client}": ["${client}", 7, true, null, {"nested": "${client}"}]}
            },
            "executions": 20
          }
          """);
    }
    assertThat(calls).hasValue(1);
    for (var requestClient : List.of(client, client + "-other")) {
      var body = mapper.createObjectNode();
      body.putObject("input").put("client_id", requestClient);
      var requestJson = mapper.writeValueAsString(body);
      logger.getRbelConverter().parseMessage(("POST /decision HTTP/1.1\r\nHost: route.example\r\n"
          + "Content-Type: application/json\r\nContent-Length: " + requestJson.getBytes(StandardCharsets.UTF_8).length
          + "\r\n\r\n" + requestJson)
          .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
      var responseJson = "{\"result\":{\"allow\":false},\"control\":\"preserved\"}";
      var response = logger.getRbelConverter().parseMessage(("HTTP/1.1 200 OK\r\n"
          + "Content-Type: application/json\r\nContent-Length: " + responseJson.getBytes(StandardCharsets.UTF_8).length
          + "\r\n\r\n" + responseJson)
          .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
      var changed = logger.getRbelModifier().applyModifications(response);
      var result = mapper.readTree(changed.findElement("$.body").orElseThrow().getRawStringContent());
      var expected = mapper.createObjectNode();
      expected.put("client", client);
      expected.putArray("${client}").add(client).add(7).add(true).addNull().addObject().put("nested", client);
      assertThat(result.path("result")).isEqualTo(requestClient.equals(client)
          ? expected : mapper.readTree("{\"allow\":false}"));
      assertThat(result.path("control").asString()).isEqualTo("preserved");
    }
  }

  @Test
  void resolvesUnsignedJsonFaultsBeforeUsingTheExistingModificationApi() {
    var received = new AtomicReference<String>();
    var steps = new TigerProxyManipulationsSteps() {
      @Override
      public void setTigerProxyManipulation(String condition, String field, String value) {
        assertThat(condition).isEqualTo("isRequest");
        assertThat(field).isEqualTo("$.body");
        received.set(value);
      }
    };
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(call -> ((String) call.getArgument(0)).replace("${client}", "own-client"));
      steps.setTigerProxyJsonManipulation("isRequest", "$.body",
          "{\"client_id\":\"${client}\",\"redirect_uris\":[\"https://example.invalid/forbidden\"]}");
      var value = new ObjectMapper().readTree(received.get());
      assertThat(value.path("client_id").asString()).isEqualTo("own-client");
      assertThat(value.path("redirect_uris").get(0).asString())
          .isEqualTo("https://example.invalid/forbidden");
      assertThatThrownBy(() -> steps.setTigerProxyJsonManipulation("isRequest", "$.body", "[]"))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Test
  void sendsAtomicTypedClaimsInOneConfigurationWithoutReplacingHeaderKey() throws Exception {
    var received = new AtomicReference<JsonNode>();
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/modifyJwt",
        exchange -> {
          calls.incrementAndGet();
          received.set(new ObjectMapper().readTree(exchange.getRequestBody()));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config
          .when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      config
          .when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.modifyJwtPath}"))
          .thenReturn("/modifyJwt");
      config
          .when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("unit-test-proxy"));
      var fields =
          io.cucumber.datatable.DataTable.create(
              java.util.List.of(
                  java.util.List.of("Feld", "JSON-Wert"),
                  java.util.List.of("body.iat", "1700000000"),
                  java.util.List.of("body.exp", "1700000030")));
      new TigerProxyManipulationsSteps()
          .setAtomicJwtFields("$.body.subject_token", "original-key", "/token", 1, fields);
      assertThat(calls).hasValue(1);
      assertThat(received.get().path("jwtFields").path("body.iat").isIntegralNumber()).isTrue();
      assertThat(received.get().path("jwtFields").path("body.exp").asLong()).isEqualTo(1700000030);
      assertThat(received.get().has("jwtField")).isFalse();
      assertThat(received.get().has("replaceJwk")).isFalse();
      assertThat(received.get().path("privateKeyPem").asString()).isEqualTo("original-key");
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {0, -1})
  void rejectsInvalidRegexExecutionCountsBeforeResolvingOrSendingRequests(Integer executions) {
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      var steps = new TigerProxyManipulationsSteps();
      assertThatThrownBy(() -> steps.setTigerProxyRegexManipulationWithExecutions(
          "request", "$.body", "original", "replacement", executions))
          .isInstanceOf(AssertionError.class)
          .hasMessage("Executions must be positive");
      config.verifyNoInteractions();
    }
  }

  @Test
  void separateGlueInstancesRetainAllRulesInTheSharedRegistry() throws Exception {
    var registry = new java.util.concurrent.ConcurrentHashMap<String, JsonNode>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/rules", exchange -> {
      if (exchange.getRequestMethod().equals("PUT")) {
        var rule = new ObjectMapper().readTree(exchange.getRequestBody());
        registry.put(rule.path("name").asString(), rule);
      }
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders(
                      "${paths.tigerProxy.modificationPath}"))
          .thenReturn("/rules");
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("unit-test-proxy"));
      var first = new TigerProxyManipulationsSteps();
      var second = new TigerProxyManipulationsSteps();
      first.setTigerProxyRegexManipulationWithExecutions(
          "outer", "$.body", "missing-client", "client_id=own&", 20);
      second.setTigerProxyRegexManipulation("inner", "$.body", "missing-claims", "claims=%7B%7D&");
      first.setTigerProxyRegexManipulation("next", "$.body", "later-condition", "next=value&");
      assertThat(registry).hasSize(3);
      var byCondition = registry.values().stream().collect(java.util.stream.Collectors.toMap(
          rule -> rule.path("condition").asString(), rule -> rule));
      assertThat(byCondition.keySet()).containsExactlyInAnyOrder("outer", "inner", "next");
      assertThat(byCondition.get("outer").path("deleteAfterNExecutions").asInt()).isEqualTo(20);
      assertThat(byCondition.get("inner").path("deleteAfterNExecutions").asInt()).isEqualTo(1);
      assertThat(byCondition.get("outer").path("replaceWith").asString())
          .isEqualTo("client_id=own&");
      assertThat(byCondition.get("inner").path("replaceWith").asString())
          .isEqualTo("claims=%7B%7D&");
      assertThat(byCondition.get("inner").path("regexFilter").asString())
          .isEqualTo("missing-claims");
      assertThat(registry.values())
          .allMatch(rule -> "$.body".equals(rule.path("targetElement").asString()));
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"string", "number", "delete", "delete-unlimited"})
  void signedClaimMutationPreservesTypeDeletionAndOriginalHeader(String operation) throws Exception {
    var received = new AtomicReference<JsonNode>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/modifyJwt", exchange -> {
      received.set(new ObjectMapper().readTree(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.modifyJwtPath}"))
          .thenReturn("/modifyJwt");
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("unit-test-proxy"));
      boolean delete = operation.startsWith("delete");
      boolean unlimited = "delete-unlimited".equals(operation);
      var type = "number".equals(operation) ? "number" : "string";
      var steps = new TigerProxyManipulationsSteps();
      if (unlimited) {
        steps.removeTigerProxyJwtFieldPreservingHeader("$.body.client_assertion",
            "body.exp", "own-key", "/par");
      } else {
        steps.manipulateSignedJwtClaim("$.body.client_assertion",
            "body.exp", delete ? "" : "0", type, delete, "own-key", "/par", 20);
      }
      var body = received.get();
      assertThat(body.path("jwtLocation").asString()).isEqualTo("$.body.client_assertion");
      assertThat(body.path("jwtField").asString()).isEqualTo("body.exp");
      assertThat(body.path("valueType").asString()).isEqualTo(type);
      assertThat(body.path("deleteField").asBoolean()).isEqualTo(delete);
      assertThat(body.path("privateKeyPem").asString()).isEqualTo("own-key");
      assertThat(body.path("condition").asString()).isEqualTo("/par");
      if (unlimited) {
        assertThat(body.has("deleteAfterNExecutions")).isFalse();
      } else {
        assertThat(body.path("deleteAfterNExecutions").asInt()).isEqualTo(20);
      }
      assertThat(body.has("replaceJwk")).isFalse();
      assertThat(body.has("updateAth")).isFalse();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void numericAccessTokenClaimPreservesNumberTypeAndDpopBinding() throws Exception {
    var keys = java.util.List.of("paths.tigerProxy.baseUrl", "paths.tigerProxy.modifyJwtPath",
        "headers.authorization.root", "headers.dpop.root", "tiger.tigerProxy.proxyId");
    var previous = new java.util.HashMap<String, String>();
    keys.forEach(
        key -> previous.put(key, TigerGlobalConfiguration.readStringOptional(key).orElse("")));
    var requests = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/modifyJwt", exchange -> {
      requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    try {
      var base = "http://localhost:" + server.getAddress().getPort();
      var values =
          java.util.Map.of(
              keys.get(0),
              base,
              keys.get(1),
              "/modifyJwt",
              keys.get(2),
              "$.header.Authorization",
              keys.get(3),
              "$.header.DPoP",
              keys.get(4),
              "fixture");
      values.forEach((key, value) -> TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT));
      new TigerProxyManipulationsSteps()
          .setNumericAccessTokenClaim("body.ver", 2, "as-key", "proof-key", "/resource", 1);
      assertThat(requests).hasSize(1);
      var body = new tools.jackson.databind.ObjectMapper().readTree(requests.getFirst());
      assertThat(body.path("valueType").asString()).isEqualTo("number");
      assertThat(body.path("replaceWith").asString()).isEqualTo("2");
      assertThat(body.path("jwtField").asString()).isEqualTo("body.ver");
      assertThat(body.path("updateAth").asBoolean()).isTrue();
      assertThat(body.path("dpopPrivateKeyPem").asString()).isEqualTo("proof-key");
      assertThat(body.path("deleteAfterNExecutions").asInt()).isEqualTo(1);
    } finally {
      server.stop(0);
      previous.forEach((key, value) -> TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT));
    }
  }

  /**
   * Checks the serialized HTTP request, including omission of valueType for the original step.
   *
   * @param valueType optional JSON type
   * @throws Exception if the local HTTP server cannot be started
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"string", "number"})
  void forwardsOptionalValueTypeWithoutChangingExistingRequest(String valueType) throws Exception {
    var received = new AtomicReference<JsonNode>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/manipulation/modifyJwt", exchange -> {
      received.set(new ObjectMapper().readTree(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.modifyJwtPath}"))
          .thenReturn("/manipulation/modifyJwt");
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("unit-test-proxy"));
      config.when(() -> TigerGlobalConfiguration.readStringOptional("headers.authorization.root"))
          .thenReturn(Optional.of("$.header.authorization"));
      config.when(() -> TigerGlobalConfiguration.readStringOptional("headers.dpop.root"))
          .thenReturn(Optional.of("$.header.dpop"));
      var steps = new TigerProxyManipulationsSteps();
      if (valueType == null) {
        steps.setTigerProxyAccessTokenManipulationWithAthUpdate(
            "body.nbf", "4102444800", "access-key", "dpop-key", "/push/v1/pushers", 1);
      } else {
        steps.setTigerProxyAccessTokenManipulationWithValueType(
            "body.nbf", "4102444800", valueType, "access-key", "dpop-key", "/push/v1/pushers", 1);
      }
      var body = received.get();
      assertThat(body).isNotNull();
      assertThat(body.path("replaceWith").isTextual()).isTrue();
      assertThat(body.path("replaceWith").asText()).isEqualTo("4102444800");
      assertThat(body.path("jwtField").asText()).isEqualTo("body.nbf");
      assertThat(body.path("privateKeyPem").asText()).isEqualTo("access-key");
      assertThat(body.path("dpopPrivateKeyPem").asText()).isEqualTo("dpop-key");
      assertThat(body.path("updateAth").booleanValue()).isTrue();
      assertThat(body.path("deleteAfterNExecutions").intValue()).isEqualTo(1);
      if (valueType == null) {
        assertThat(body.has("valueType")).isFalse();
      } else {
        assertThat(body.path("valueType").asText()).isEqualTo(valueType);
      }
    } finally {
      server.stop(0);
    }
  }

  /**
   * Verifies repeated route updates bypass the JVM proxy and still reject an unsuccessful response.
   *
   * @throws Exception if fixture startup or a successful route update fails
   */
  @Test
  void reusesDirectTransportAndRejectsFailedRouteUpdates() throws Exception {
    var previousProxy = ProxySelector.getDefault();
    var previousUrl =
        TigerGlobalConfiguration.readStringOptional("paths.tigerProxy.baseUrl").orElse("");
    var requests = new CopyOnWriteArrayList<String>();
    var status = new AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/route", exchange -> {
      requests.add(exchange.getRequestMethod() + " "
          + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(status.get(), -1);
      exchange.close();
    });
    server.start();
    try {
      TigerGlobalConfiguration.putValue(
          "paths.tigerProxy.baseUrl",
          "http://localhost:" + server.getAddress().getPort(),
          ConfigurationValuePrecedence.TEST_CONTEXT);
      ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("localhost", 1)));
      var steps = new TigerProxyManipulationsSteps();
      var route = "{\"from\":\"/crl\",\"to\":\"http://mock/crl\"}";
      steps.configureTigerProxyRoute(route);
      steps.configureTigerProxyRoute(route);
      status.set(500);
      assertThatThrownBy(() -> steps.configureTigerProxyRoute(route))
          .isInstanceOf(IOException.class).hasMessageContaining("returned HTTP 500");
      assertThat(requests).containsExactly("PUT " + route, "PUT " + route, "PUT " + route);
    } finally {
      server.stop(0);
      ProxySelector.setDefault(previousProxy);
      TigerGlobalConfiguration.putValue("paths.tigerProxy.baseUrl", previousUrl,
          ConfigurationValuePrecedence.TEST_CONTEXT);
    }
  }

  /** Requires both resets to succeed without touching recorded traffic. */
  @ParameterizedTest
  @CsvSource({"strict,0", "strict,1", "strict,2", "strict,3",
      "hook,0", "hook,1", "hook,2", "hook,3",
      "step,0", "step,1", "step,2", "step,3"})
  void cleanupAttemptsBothResetsAndRejectsFailures(String caller, int failingCall) throws IOException {
    var requests = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
      exchange.sendResponseHeaders(failingCall == 3 || requests.size() == failingCall ? 503 : 200, -1);
      exchange.close();
    });
    server.start();
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("unit-proxy"));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("http://localhost:" + server.getAddress().getPort());
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders(
                      "${paths.tigerProxy.manipulationPath}"))
          .thenReturn("/manipulation/modification");
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders(
                      "${paths.tigerProxy.resetJwtManipulationPath}"))
          .thenReturn("/manipulation/resetJwtManipulation");
      var steps = new TigerProxyManipulationsSteps();
      Runnable reset = switch (caller) {
        case "strict" -> steps::stopAllTigerProxyManipulationsStrictly;
        case "hook" -> steps::resetTigerProxyStateIfAvailable;
        default -> steps::stopAllTigerProxyManipulations;
      };
      if (failingCall == 0) {
        reset.run();
      } else {
        assertThatThrownBy(reset::run).isInstanceOf(AssertionError.class)
            .hasCauseInstanceOf(IOException.class)
            .satisfies(error -> assertThat(error.getSuppressed()).hasSize(failingCall == 3 ? 1 : 0));
      }
      assertThat(requests)
          .containsExactly(
              "DELETE /manipulation/modification", "POST /manipulation/resetJwtManipulation");
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"strict", "hook", "step"})
  void interruptedFirstResetStillSendsIndependentJwtResetAndPreservesInterrupt(String caller) throws IOException {
    var requests = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      requests.add(exchange.getRequestMethod());
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("unit-proxy"));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("http://localhost:" + server.getAddress().getPort());
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders(
                      "${paths.tigerProxy.manipulationPath}"))
          .thenReturn("/modification");
      config
          .when(
              () ->
                  TigerGlobalConfiguration.resolvePlaceholders(
                      "${paths.tigerProxy.resetJwtManipulationPath}"))
          .thenReturn("/resetJwt");
      var steps = new TigerProxyManipulationsSteps();
      Runnable reset = switch (caller) {
        case "strict" -> steps::stopAllTigerProxyManipulationsStrictly;
        case "hook" -> steps::resetTigerProxyStateIfAvailable;
        default -> steps::stopAllTigerProxyManipulations;
      };
      Thread.currentThread().interrupt();
      assertThatThrownBy(reset::run).isInstanceOf(AssertionError.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(requests).containsExactly("POST");
    } finally {
      Thread.interrupted();
      server.stop(0);
    }
  }

  /** Absence is optional only when proxy usage is actually disabled. */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void resetSkipsDisabledProxyButRejectsUnresolvedConfiguredProxy(boolean configured) {
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(configured ? Optional.of("unit-proxy") : Optional.empty());
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("${paths.tigerProxy.baseUrl}");
      var steps = new TigerProxyManipulationsSteps();
      if (configured) {
        assertThatThrownBy(steps::resetTigerProxyStateIfAvailable).isInstanceOf(AssertionError.class)
            .hasMessageContaining("base URL is unresolved");
      } else {
        steps.resetTigerProxyStateIfAvailable();
      }
      assertThatThrownBy(steps::stopAllTigerProxyManipulationsStrictly).isInstanceOf(AssertionError.class);
    }
  }

  @Test
  void waitsForIndexedMessagesThatHaveNotReachedLocalHistoryYet() throws Exception {
    var first = org.mockito.Mockito.mock(de.gematik.rbellogger.data.RbelElement.class);
    var late = org.mockito.Mockito.mock(de.gematik.rbellogger.data.RbelElement.class);
    org.mockito.Mockito.when(first.getUuid()).thenReturn("first");
    org.mockito.Mockito.when(late.getUuid()).thenReturn("late");
    var retriever = org.mockito.Mockito.mock(de.gematik.test.tiger.lib.rbel.RbelMessageRetriever.class);
    org.mockito.Mockito.when(retriever.getCurrentRequest()).thenReturn(first);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/messages", exchange -> {
      var body = "{\"messages\":[{\"uuid\":\"first\",\"sequenceNumber\":1},"
          + "{\"uuid\":\"late\",\"sequenceNumber\":2}]}";
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var selected = mockStatic(de.gematik.test.tiger.lib.rbel.RbelMessageRetriever.class);
        var history = mockStatic(de.gematik.zeta.services.RecordedMessages.class)) {
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(call -> call.getArgument(0));
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("unit-proxy"));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
          .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.messagesWithMetaPath}"))
          .thenReturn("/messages");
      selected.when(de.gematik.test.tiger.lib.rbel.RbelMessageRetriever::getInstance)
          .thenReturn(retriever);
      history.when(de.gematik.zeta.services.RecordedMessages::snapshot)
          .thenReturn(java.util.List.of(first), java.util.List.of(first, late));
      var steps = new TigerProxyManipulationsSteps();
      steps.rememberProxyHistoryStart();
      steps.synchronizeProxyHistory();
      history.verify(de.gematik.zeta.services.RecordedMessages::snapshot,
          org.mockito.Mockito.atLeast(2));
    } finally {
      server.stop(0);
    }
  }

}
