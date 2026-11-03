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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.zeta.services.KvnrGenerator;
import io.cucumber.java.After;
import io.cucumber.java.de.Gegebensei;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.specification.RequestSpecification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Creates and removes only the additional SDK contexts owned by the current scenario. */
public class SdkInstanceSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final LinkedHashMap<Integer, Set<Integer>> ownedContexts = new LinkedHashMap<>();
  private final Runnable primaryReset;
  private boolean ownsMonitoringPrimary;

  /** Creates scenario-owned lifecycle state with the public testdriver reset operation. */
  public SdkInstanceSteps() {
    this(SdkInstanceSteps::resetPrimary);
  }

  SdkInstanceSteps(Runnable primaryReset) {
    this.primaryReset = primaryReset;
  }

  /** Records primary session ownership; preparation remains visible in the feature. */
  @Gegebensei("merke den primären SDK-Kontext zur Bereinigung")
  public void ownMonitoringPrimary() {
    ownsMonitoringPrimary = true;
  }

  /** Clears only a primary monitoring session explicitly owned by this scenario. */
  @After(value = "@monitoring_client_variant", order = 90)
  public void cleanupMonitoringPrimary() {
    if (ownsMonitoringPrimary) {
      primaryReset.run();
      ownsMonitoringPrimary = false;
    }
  }

  private static void resetPrimary() {
    require(http().get(config("paths.client.reset")).statusCode() == 200, "Primary monitoring SDK reset failed");
  }

  /**
   * Registers an instance created by existing HTTP steps for failure-safe cleanup.
   *
   * @param createdResponse full JSON response from the public instance creation operation
   * @param previousInventory full JSON inventory observed before instance creation
   */
  @Gegebensei("merke die zusätzliche SDK-Instanz aus Antwort {tigerResolvedString} mit vorherigem Bestand {tigerResolvedString} zur Bereinigung")
  public void ownAdditionalInstance(String createdResponse, String previousInventory) {
    var previousIds = instanceIds(JSON.readTree(previousInventory));
    int ownedId = createdId(JSON.readTree(createdResponse), previousIds);
    require(!ownedContexts.containsKey(ownedId), "SDK instance is already owned by this scenario");
    ownedContexts.put(ownedId, previousIds);
  }

  /**
   * Uses the public load-driver API to preserve the primary client's live SDK context.
   *
   * @param identity existing synthetic identity to register on the independent second SDK
   */
  @Gegebensei("eine zusätzliche unabhängige SDK-Instanz für KVNR {tigerResolvedString}")
  public void create(String identity) {
    var fields = new LinkedHashMap<String, String>();
    for (var name :
        new String[] {
          "fachdienstUrl", "oidcBaseUri", "oidcIdpIss", "oidcIdpAlias", "disableTlsVerification"
        }) {
      fields.put(name, config("testdata.ccSdkInstances." + name));
    }
    var payload = createRequest(identity, config("oidcTestData.emailDomain"), fields);
    final int ownedId = createOwned(payload);
    TigerGlobalConfiguration.putValue(
        "CC_SECOND_SDK_ID", ownedId, ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue(
        "CC_SECOND_SDK_PATH",
        config("paths.client.loadResourcePathPrefix") + "/" + ownedId + config("paths.fachdienst.helloZetaPath"),
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  private int createOwned(JsonNode payload) {
    final var previousIds = list();
    var response =
        http()
            .contentType("application/json")
            .body(JSON.writeValueAsString(payload))
            .post(config("paths.client.loadCreateInstances"));
    require(response.statusCode() == 200, "Additional SDK instance creation failed");
    final int ownedId = createdId(JSON.readTree(response.asString()), previousIds);
    ownedContexts.put(ownedId, previousIds);
    var instance = findInstance(listResponse(), ownedId);
    require("CREATED".equals(instance.path("state").asText()), "Unexpected initial SDK state");
    return ownedId;
  }

  /**
   * Creates another identity without resetting the primary SDK or any previously created context.
   *
   * @param original identity that must not be reused
   * @param variable variable receiving the fresh foreign identity
   */
  @Gegebensei("eine zusätzliche SDK-Instanz für eine andere Identität als {tigerResolvedString} mit KVNR in Variable {string}")
  public void createForeign(String original, String variable) {
    require(original.matches("[A-Z][0-9]{9}"), "Expected the original synthetic identity");
    String identity;
    do {
      identity = KvnrGenerator.generateRandomValidKvnr();
    } while (identity.equals(original));
    TigerGlobalConfiguration.putValue(variable, identity, ConfigurationValuePrecedence.TEST_CONTEXT);
    create(identity);
  }

  /** Deletes only owned local SDK instances, including after failure of another cleanup. */
  @After(value = "@additional_sdk_instance", order = 100)
  public void cleanup() {
    cleanupContexts(ownedContexts, (ownedId, previousIds) -> {
      var response = http().queryParam("id", ownedId)
          .delete(config("paths.client.loadDeleteInstances"));
      require(response.statusCode() == 200, "Could not remove the scenario's additional SDK instance");
      verifyCleanup(JSON.readTree(response.asString()), list(), previousIds, ownedId);
    });
  }

  static void cleanupContexts(LinkedHashMap<Integer, Set<Integer>> owned,
      BiConsumer<Integer, Set<Integer>> remove) {
    var failure = new AssertionError("Additional SDK context cleanup failed");
    // The later context's initial inventory includes earlier owned contexts: remove in reverse order.
    for (int id : new ArrayList<>(owned.keySet()).reversed()) {
      try {
        remove.accept(id, owned.get(id));
        owned.remove(id);
      } catch (RuntimeException | AssertionError error) {
        failure.addSuppressed(error);
      }
    }
    if (failure.getSuppressed().length > 0) {
      throw failure;
    }
  }

  static JsonNode createRequest(String identity, String domain, Map<String, String> config) {
    require(identity != null && identity.matches("[A-Z][0-9]{9}") && domain != null && domain.matches("[A-Za-z0-9.-]+"),
        "Unresolved/invalid synthetic SDK identity");
    // Defer registration/authentication until ownership is recorded, so their failures cannot leak a context.
    var request = JSON.createObjectNode().put("count", 1).put("autoInit", false).putNull("logLevel");
    var instance = request.putArray("instances").addObject().put("authMode", "OIDC")
        .put("oidcTestKvnr", identity).put("oidcBindingEmail", identity + "@" + domain);
    for (var name : new String[] {"fachdienstUrl", "oidcBaseUri", "oidcIdpIss", "oidcIdpAlias"}) {
      var value = config.get(name);
      require(value != null && !value.isBlank() && !value.contains("${"), "Unresolved SDK profile: " + name);
      instance.put(name, value);
    }
    var tls = config.get("disableTlsVerification");
    require("true".equals(tls) || "false".equals(tls), "Explicit SDK TLS profile required");
    instance.put("disableTlsVerification", Boolean.parseBoolean(tls));
    return request;
  }

  static int createdId(JsonNode response, Set<Integer> existing) {
    require(response.path("created").isIntegralNumber() && response.path("created").canConvertToInt()
        && response.path("created").intValue() == 1,
        "Expected exactly one created SDK context");
    var ids = response.path("ids");
    require(ids.isArray() && ids.size() == 1, "Expected one owned SDK instance ID");
    int id = id(ids.get(0));
    require(!existing.contains(id), "The reported SDK instance was already present");
    return id;
  }

  static Set<Integer> instanceIds(JsonNode response) {
    require(response.isArray(), "SDK instance inventory is not an array");
    var ids = new HashSet<Integer>();
    for (var instance : response) {
      require(ids.add(id(instance.path("id"))), "Duplicate SDK instance ID");
    }
    return Set.copyOf(ids);
  }

  static void verifyCleanup(JsonNode response, Set<Integer> current, Set<Integer> previous, int owned) {
    var removed = response.path("ids");
    require(response.path("removed").isIntegralNumber() && response.path("removed").canConvertToInt()
        && response.path("removed").intValue() == 1
        && removed.isArray() && removed.size() == 1 && id(removed.get(0)) == owned,
        "Cleanup did not remove exactly the owned SDK instance");
    require(!current.contains(owned) && current.containsAll(previous), "SDK cleanup changed another context or retained its own");
  }

  private static int id(JsonNode value) {
    require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0, "Invalid SDK instance ID");
    return value.intValue();
  }

  private static JsonNode findInstance(JsonNode response, int target) {
    instanceIds(response);
    return response.valueStream().filter(node -> node.path("id").intValue() == target).findFirst()
        .orElseThrow(() -> new AssertionError("Created SDK instance is absent"));
  }

  private static Set<Integer> list() {
    return instanceIds(listResponse());
  }

  private static JsonNode listResponse() {
    var response = http().get(config("paths.client.loadListInstances"));
    require(response.statusCode() == 200, "SDK instance inventory unavailable");
    return JSON.readTree(response.asString());
  }

  private static RequestSpecification http() {
    var timeout = HttpClientConfig.httpClientConfig().setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 20000);
    return TigerHttpClient.givenDefaultSpec().config(RestAssured.config().httpClient(timeout));
  }

  private static String config(String name) {
    return TigerGlobalConfiguration.readStringOptional(name).map(TigerGlobalConfiguration::resolvePlaceholders).orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
