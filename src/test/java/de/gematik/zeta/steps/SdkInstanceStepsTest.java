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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import io.restassured.RestAssured;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

class SdkInstanceStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void createsExactlyOneIsolatedOidcIdentityWithoutChangingGlobalLogLevel() {
    var config = Map.of("fachdienstUrl", "https://guard.invalid/pep/", "oidcBaseUri", "http://localhost:8090/callback",
        "oidcIdpIss", "https://idp.invalid", "oidcIdpAlias", "test-idp", "disableTlsVerification", "false");
    var result = SdkInstanceSteps.createRequest("M404230279", "example.invalid", config);
    assertEquals(1, result.path("count").intValue());
    assertEquals(1, result.path("instances").size());
    assertEquals(false, result.path("autoInit").booleanValue());
    assertEquals("M404230279@example.invalid", result.path("instances").get(0).path("oidcBindingEmail").asText());
    assertEquals("OIDC", result.path("instances").get(0).path("authMode").asText());
    assertEquals(false, result.path("instances").get(0).path("disableTlsVerification").booleanValue());
    assertEquals(true, result.path("logLevel").isNull());
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.createRequest("${identity}", "example.invalid", config));
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.createRequest("M404230279", "example.invalid", Map.of()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"oidc", "smcb"})
  void configuredProfileOwnsTheWholeInstanceLifecycleEvenWhenAnotherDriverHasTheSameId(String profile)
          throws Exception {
    String prefix = "/td-" + profile;
    var owned = new ConcurrentHashMap<Integer, String>(Map.of(2, "READY"));
    var foreign = new ConcurrentHashMap<Integer, String>(Map.of(7, "READY"));
    var requests = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      requests.add(exchange.getRequestMethod() + " " + path);
      String driverPrefix = path.startsWith(prefix + "/")
              ? prefix : "/td-" + (profile.equals("oidc") ? "smcb" : "oidc");
      var instances = driverPrefix.equals(prefix) ? owned : foreign;
      String response;
      int status = 200;
      if (path.equals(driverPrefix + "/load/list_instances")) {
        var inventory = JSON.createArrayNode();
        instances.forEach((id, state) -> inventory.addObject().put("id", id).put("state", state));
        response = inventory.toString();
      } else if (path.equals(driverPrefix + "/load/create_instances")) {
        var payload = JSON.readTree(exchange.getRequestBody().readAllBytes());
        if (payload.path("autoInit").booleanValue()) {
          status = 400;
        }
        instances.put(7, "CREATED");
        response = "{\"created\":1,\"ids\":[7]}";
      } else if (path.equals(driverPrefix + "/load/7/resource")) {
        instances.put(7, "READY");
        response = driverPrefix.substring(4);
      } else if (path.equals(driverPrefix + "/loaddriver-api/7/registration")
              || path.equals(driverPrefix + "/loaddriver-api/7/reset")) {
        response = driverPrefix.substring(4);
      } else if (path.equals(driverPrefix + "/load/delete_instances")) {
        if (!exchange.getRequestURI().getQuery().equals("id=7")) {
          status = 400;
        }
        instances.remove(7);
        response = "{\"removed\":1,\"ids\":[7]}";
      } else {
        status = 404;
        response = "Unexpected driver route";
      }
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    var values = new HashMap<String, String>();
    Map<?, ?> yaml = new Yaml().load(
            Files.readString(Path.of("tiger/client-paths-" + profile + ".yaml")));
    ((Map<?, ?>) yaml.get("client")).forEach((key, value) -> {
      if (value instanceof String text) {
        values.put("paths.client." + key, text);
      }
    });
    values.put("paths.client.baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
    values.put("paths.fachdienst.helloZetaPath", "/resource");
    values.put("oidcTestData.emailDomain", "example.invalid");
    Map.of("fachdienstUrl", "https://guard.invalid/pep/", "oidcBaseUri", "http://localhost:8090/callback",
                    "oidcIdpIss", "https://idp.invalid", "oidcIdpAlias", "test-idp", "disableTlsVerification", "false")
            .forEach((key, value) -> values.put("testdata.ccSdkInstances." + key, value));
    try (var config = mockStatic(TigerGlobalConfiguration.class);
         var tiger = mockStatic(TigerHttpClient.class)) {
      config.when(() -> TigerGlobalConfiguration.readStringOptional(anyString()))
              .thenAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0))));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString())).thenAnswer(call -> {
        String resolved = call.getArgument(0);
        for (int i = 0; i < 5; i++) {
          for (var entry : values.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
          }
        }
        return resolved;
      });
      config.when(() -> TigerGlobalConfiguration.putValue("CC_SECOND_SDK_ID", 7,
              ConfigurationValuePrecedence.TEST_CONTEXT)).thenAnswer(call -> {
                values.put("CC_SECOND_SDK_ID", "7");
                return null;
              });
      config.when(() -> TigerGlobalConfiguration.putValue(eq("CC_SECOND_SDK_PATH"), anyString(),
              eq(ConfigurationValuePrecedence.TEST_CONTEXT))).thenAnswer(call -> {
                values.put("CC_SECOND_SDK_PATH", call.getArgument(1));
                return null;
              });
      tiger.when(TigerHttpClient::givenDefaultSpec).thenAnswer(call -> RestAssured.given());
      var steps = new SdkInstanceSteps(() -> {});
      steps.create("M404230279");
      assertEquals(prefix + "/load/7/resource", values.get("CC_SECOND_SDK_PATH"));
      assertEquals(profile, TigerHttpClient.givenDefaultSpec()
              .get(values.get("paths.client.baseUrl") + values.get("CC_SECOND_SDK_PATH")).asString());

      String registrationUrl = TigerGlobalConfiguration.resolvePlaceholders(
              values.get("paths.client.loadRegistration"));
      assertEquals(profile, TigerHttpClient.givenDefaultSpec().get(registrationUrl).asString());

      String resetUrl = TigerGlobalConfiguration.resolvePlaceholders(
              "${paths.client.baseUrl}${paths.client.loadDriverPathPrefix}/7/reset");
      assertEquals(profile, TigerHttpClient.givenDefaultSpec().get(resetUrl).asString());

      steps.cleanup();
      steps.cleanup();
      assertEquals(Map.of(2, "READY"), owned);
      assertEquals(Map.of(7, "READY"), foreign);
      assertTrue(requests.stream().allMatch(request -> request.contains(" " + prefix + "/")));
      assertEquals(1, requests.stream().filter(request -> request.startsWith("DELETE ")).count());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void neverTakesOwnershipOfAnExistingContext() {
    var result = JSON.readTree("{\"created\":1,\"ids\":[5]}");
    assertEquals(5, SdkInstanceSteps.createdId(result, Set.of(2)));
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.createdId(result, Set.of(5)));
  }

  @Test
  void primaryOwnershipMakesNoSdkCallAndCleansAfterSubsequentPreparationFailure() {
    var actions = new ArrayList<String>();
    var steps = new SdkInstanceSteps(() -> actions.add("reset"));
    steps.cleanupMonitoringPrimary();
    steps.ownMonitoringPrimary();
    assertEquals(List.of(), actions);
    // A failed feature step still leaves ownership registered for the After hook.
    steps.cleanupMonitoringPrimary();
    steps.cleanupMonitoringPrimary();
    assertEquals(List.of("reset"), actions);
  }

  @Test
  void failedPrimaryCleanupRemainsOwnedForRetry() {
    var actions = new ArrayList<String>();
    var steps = new SdkInstanceSteps(() -> {
      actions.add("reset");
      if (actions.size() == 1) {
        throw new AssertionError("Temporary cleanup failure");
      }
    });
    steps.ownMonitoringPrimary();
    assertThrows(AssertionError.class, steps::cleanupMonitoringPrimary);
    steps.cleanupMonitoringPrimary();
    steps.cleanupMonitoringPrimary();
    assertEquals(List.of("reset", "reset"), actions);
  }

  @Test
  void instanceOwnershipUsesObservedInventoryAndCannotBeReplacedByAnotherCreationResponse() {
    var actions = new ArrayList<String>();
    var steps = new SdkInstanceSteps(() -> actions.add("reset"));
    steps.ownAdditionalInstance("{\"created\":1,\"ids\":[5]}", "[{\"id\":2}]");
    assertEquals(List.of(), actions);
    assertThrows(AssertionError.class,
        () -> steps.ownAdditionalInstance("{\"created\":1,\"ids\":[5]}", "[{\"id\":3}]"));
  }

  @Test
  void invalidCreationCannotTakeOwnershipOfExistingOrUnreportedInstances() {
    var steps = new SdkInstanceSteps(() -> {});
    assertThrows(AssertionError.class,
        () -> steps.ownAdditionalInstance("{\"created\":1,\"ids\":[5]}", "[{\"id\":5}]"));
    assertThrows(AssertionError.class,
        () -> steps.ownAdditionalInstance("{\"created\":2,\"ids\":[5,6]}", "[]"));
    steps.ownAdditionalInstance("{\"created\":1,\"ids\":[5]}", "[]");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"created\":0,\"ids\":[5]}", "{\"created\":1,\"ids\":[2,3]}",
      "{\"created\":1,\"ids\":[-1]}", "{\"created\":1,\"ids\":[2147483648]}", "{\"created\":1,\"ids\":[\"5\"]}",
      "{\"created\":4294967297,\"ids\":[5]}"})
  void ambiguousOrUnsafeCreationResponsesFail(String response) {
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.createdId(JSON.readTree(response), Set.of()));
  }

  @Test
  void cleanupMustRemoveOnlyTheOwnedContextAndPreserveExistingOnes() {
    var result = JSON.readTree("{\"removed\":1,\"ids\":[5]}");
    assertDoesNotThrow(() -> SdkInstanceSteps.verifyCleanup(result, Set.of(2, 6), Set.of(2), 5));
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.verifyCleanup(result, Set.of(2, 5), Set.of(2), 5));
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.verifyCleanup(result, Set.of(), Set.of(2), 5));
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.verifyCleanup(result, Set.of(2), Set.of(2), 6));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "[{\"id\":1},{\"id\":1}]", "[{\"id\":0}]", "[{\"id\":\"2\"}]"})
  void malformedInventoryFails(String response) {
    assertThrows(AssertionError.class, () -> SdkInstanceSteps.instanceIds(JSON.readTree(response)));
  }

  @Test
  void multipleContextsAreRemovedInReverseOrderWithTheirOriginalInventories() {
    var owned = new LinkedHashMap<Integer, Set<Integer>>();
    owned.put(5, Set.of(2));
    owned.put(6, Set.of(2, 5));
    owned.put(7, Set.of(2, 5, 6));
    var order = new ArrayList<Integer>();
    SdkInstanceSteps.cleanupContexts(owned, (id, previous) -> {
      order.add(id);
      assertEquals(id == 7 ? Set.of(2, 5, 6) : id == 6 ? Set.of(2, 5) : Set.of(2), previous);
    });
    assertEquals(List.of(7, 6, 5), order);
    assertEquals(Map.of(), owned);
  }

  @Test
  void oneCleanupFailureDoesNotPreventCleanupOfAnotherOwnedContext() {
    var owned = new LinkedHashMap<Integer, Set<Integer>>();
    owned.put(5, Set.of(2));
    owned.put(6, Set.of(2, 5));
    var order = new ArrayList<Integer>();
    var failure = assertThrows(AssertionError.class, () -> SdkInstanceSteps.cleanupContexts(owned, (id, previous) -> {
      order.add(id);
      if (id == 6) {
        throw new AssertionError("simulated deletion failure");
      }
    }));
    assertEquals(List.of(6, 5), order);
    assertEquals(Set.of(6), owned.keySet());
    assertEquals(1, failure.getSuppressed().length);
  }
}
