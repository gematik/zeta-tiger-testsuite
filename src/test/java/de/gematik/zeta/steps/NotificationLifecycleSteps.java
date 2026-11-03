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
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.de.Gegebensei;
import io.restassured.http.Method;
import io.restassured.response.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Independent notification profile observation and cleanup of scenario-owned pusher registrations.
 */
public class NotificationLifecycleSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final BiFunction<Method, URI, Response> request;
  private final Path records;
  private final BiConsumer<String, String> selectIdentity;
  private final List<OwnedPusher> ownedPushers = new ArrayList<>();

  private record OwnedPusher(ObjectNode ownership, Path record, URI endpoint, URI deletion,
                             boolean restoreIdentity) {}

  /**
   * Uses the existing SDK testdriver and preserves interrupted-run ownership in test artifacts.
   */
  public NotificationLifecycleSteps() {
    this((method, uri) -> TigerHttpClient.givenDefaultSpec().request(method, uri),
        Path.of("target", "notification-lifecycle"));
  }

  NotificationLifecycleSteps(BiFunction<Method, URI, Response> request, Path records) {
    this(request, records, NotificationLifecycleSteps::selectIdentity);
  }

  NotificationLifecycleSteps(BiFunction<Method, URI, Response> request, Path records,
      BiConsumer<String, String> selectIdentity) {
    this.request = request;
    this.records = records;
    this.selectIdentity = selectIdentity;
  }

  /**
   * Registers cleanup before the first write, without changing the current SDK identity or keys.
   *
   * @param pushkey unique pusher key owned by this scenario
   * @param appId owning application
   * @param identity independently generated OIDC identity
   * @throws IOException if the recovery record cannot be persisted before registration
   */
  @Gegebensei("die gezielte Bereinigung für Pusher {tigerResolvedString}, Anwendung {tigerResolvedString} und Nutzer {tigerResolvedString}")
  public void prepareCleanup(String pushkey, String appId, String identity) throws IOException {
    prepareCleanup(pushkey, appId, identity, config("paths.client.notifications.pushers"),
        config("oidcTestData.emailDomain"), ZetaDeploymentConfiguration.getNamespace());
  }

  void prepareCleanup(String pushkey, String appId, String identity, String url,
      String emailDomain, String namespace) throws IOException {
    prepareCleanup(pushkey, appId, identity, url, emailDomain, namespace, false);
  }

  void prepareCleanup(String pushkey, String appId, String identity, String url,
      String emailDomain, String namespace, boolean restoreIdentity) throws IOException {
    require(ownedPushers.stream().noneMatch(p -> identity.equals(p.ownership.path("identity").asString())
        && pushkey.equals(p.ownership.path("pushkey").asString())
        && appId.equals(p.ownership.path("appId").asString())), "Pusher cleanup already registered");
    require(pushkey.startsWith("zeta-lifecycle-") && !appId.isBlank()
        && identity.matches("[A-Z][0-9]{9}"), "Invalid owned pusher identity");
    var endpoint = URI.create(url);
    var deletion = URI.create(url + (url.contains("?") ? "&" : "?") + "pushkey="
        + URLEncoder.encode(pushkey, StandardCharsets.UTF_8) + "&appId="
        + URLEncoder.encode(appId, StandardCharsets.UTF_8));
    var prepared = JSON.createObjectNode().put("pushkey", pushkey).put("appId", appId)
        .put("identity", identity).put("email", identity + "@" + emailDomain)
        .put("namespace", namespace).put("endpoint", url)
        .put("restoreIdentity", restoreIdentity)
        .put("createdAt", Instant.now().toString()).put("state", "pending");
    Files.createDirectories(records);
    var record = records.resolve(UUID.randomUUID() + ".json");
    Files.writeString(record, prepared.toPrettyString());
    ownedPushers.add(new OwnedPusher(prepared, record, endpoint, deletion, restoreIdentity));
  }

  /**
   * Captures an owned registration whose identity may no longer be active when cleanup runs.
   *
   * @param pushkey unique scenario-owned key
   * @param appId owning application
   * @param identity independent OIDC identity to restore only during cleanup
   * @throws IOException if ownership cannot be persisted before the first write
   */
  @Gegebensei("die nutzergebundene Bereinigung für Pusher {tigerResolvedString}, Anwendung {tigerResolvedString} und Nutzer {tigerResolvedString}")
  public void prepareIdentityCleanup(String pushkey, String appId, String identity) throws IOException {
    prepareCleanup(pushkey, appId, identity, config("paths.client.notifications.pushers"),
        config("oidcTestData.emailDomain"), ZetaDeploymentConfiguration.getNamespace(), true);
  }

  /**
   * Deletes only the captured key/application and verifies absence, also after a failed step.
   * A failed cleanup fails the hook and leaves its pending recovery record intact.
   *
   * @throws IOException if the successful cleanup cannot be recorded
   */
  @After(value = "@notification_pusher_lifecycle or @notification_pusher_ownership", order = 100)
  public void cleanup() throws IOException {
    var failures = new AssertionError("Owned pusher cleanup failed; pending records retained");
    for (var owned : List.copyOf(ownedPushers)) {
      try {
        if (owned.restoreIdentity) {
          selectIdentity.accept(owned.ownership.path("identity").asString(),
              owned.ownership.path("email").asString());
        }
        require(request.apply(Method.DELETE, owned.deletion).statusCode() == 200,
            "Owned pusher deletion failed; recovery record: " + owned.record);
        var response = request.apply(Method.GET, owned.endpoint);
        require(response.statusCode() == 200, "Pusher cleanup verification failed: " + owned.record);
        verifyAbsent(response.asString(), owned.ownership.path("pushkey").asString(),
            owned.ownership.path("appId").asString());
        owned.ownership.put("state", "cleaned");
        Files.writeString(owned.record, owned.ownership.toPrettyString());
        ownedPushers.remove(owned);
      } catch (IOException | RuntimeException | AssertionError failure) {
        failures.addSuppressed(new AssertionError("Cleanup record: " + owned.record, failure));
      }
    }
    if (failures.getSuppressed().length > 0) {
      throw failures;
    }
  }

  private static void selectIdentity(String identity, String email) {
    require(TigerHttpClient.givenDefaultSpec().get(config("paths.client.reset")).statusCode() == 200,
        "Cannot reset SDK before restoring owned pusher identity");
    var body = JSON.createObjectNode().put("kvnr", identity).put("email", email);
    require(TigerHttpClient.givenDefaultSpec().contentType("application/json").body(body.toString())
        .post(config("paths.client.oidc.kvnrEmail")).statusCode() == 200,
        "Cannot restore owned pusher identity");
  }

  static void verifyAbsent(String body, String pushkey, String appId) {
    var pushers = JSON.readTree(body);
    require(pushers != null && pushers.isArray(), "Cleanup SDK response is not a pusher array");
    for (var pusher : pushers) {
      require(pusher.path("pushkey").isString() && pusher.path("app_id").isString(),
          "Cleanup SDK response contains an unidentifiable pusher");
      require(!pushkey.equals(pusher.path("pushkey").asString())
          || !appId.equals(pusher.path("app_id").asString()), "Owned pusher remains after cleanup");
    }
  }

  /**
   * Reads the active FDV pod's History flag independently of its discovery response.
   * The local adapter requires one ready pod and an explicit boolean environment value.
   */
  @Gegebensei("der History-Featurestatus des laufenden Notification Service")
  public void observeHistoryFeature() {
    var service = ZetaDeploymentConfiguration.getServiceInstance();
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    var pod = service.getSingleReadyPodNameForDeployment(namespace,
        config("testdata.notificationService.fdvDeployment"));
    var result = service.executeKubectlCommand(false, "-n", namespace, "exec", pod, "-c",
        config("testdata.notificationService.container"), "--", "printenv", "NOTIFICATION_HISTORY_ENABLED");
    require(result.exitCode() == 0, "Cannot independently read Notification History feature status");
    var count = historyScopeCount(result.stdout());
    TigerGlobalConfiguration.putValue("notificationLifecycleHistoryScopeCount", count,
        ConfigurationValuePrecedence.TEST_CONTEXT);
    SerenityReportUtils.addCustomData("Notification History profile", "Pod " + pod
        + ", NOTIFICATION_HISTORY_ENABLED=" + result.stdout().trim());
  }

  static int historyScopeCount(String value) {
    require(value != null && (value.trim().equals("true") || value.trim().equals("false")),
        "Missing or ambiguous Notification History feature status");
    return value.trim().equals("true") ? 1 : 0;
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.resolvePlaceholders("${" + key + "}");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
