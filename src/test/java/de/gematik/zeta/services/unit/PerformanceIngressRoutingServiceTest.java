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

package de.gematik.zeta.services.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.services.PerformanceIngressRoutingService;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.steps.Hooks;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Tests temporary direct routing and exact ingress restoration. */
class PerformanceIngressRoutingServiceTest {

  /** Verifies all HTTPS-environment routes and annotations are restored exactly. */
  @Test
  void routesAllPerformanceTrafficDirectlyAndRestoresHttpsEnvironment() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);

    var snapshot = service.enableDirectRouting("zeta-staging");

    assertEquals(3, snapshot.routes().size());
    assertPatchContains(kubectl, "zeta-guard-pep", "pep-proxy-svc");
    assertPatchContains(kubectl, "zeta-guard-auth", "authserver");
    assertPatchContains(kubectl, "zeta-guard-auth", "nginx.org~1ssl-services");
    assertPatchContains(kubectl, "testdriver-smcb", "testdriver-smcb");
    assertTrue(kubectl.patchTargets.contains("configmap/pep-test-nginx-conf"));
    assertFalse(kubectl.currentPepConfig.contains("tiger-proxy"));
    assertEquals(kubectl.directPepConfig, kubectl.activePepConfig);
    assertEquals(1, kubectl.pepRestartCount);
    assertTrue(kubectl.patchFiles.stream().noneMatch(Files::exists));

    service.restore(snapshot);

    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
    assertEquals(kubectl.originalPepConfig, kubectl.currentPepConfig);
    assertEquals(kubectl.originalPepConfig, kubectl.activePepConfig);
    assertEquals(2, kubectl.pepRestartCount);
    assertTrue(kubectl.patchFiles.stream().noneMatch(Files::exists));
  }

  /** Routes the prefixed SMC-B proxy path, preserving sibling paths and rewrite annotations. */
  @Test
  void routesSmcbRegexProxyPathAndPreservesOtherIngressState() {
    var kubectl = new FakeKubectlService(false);
    var service = new PerformanceIngressRoutingService(kubectl);
    var json = new ObjectMapper();
    var original = json.readTree(kubectl.originalIngresses.get("testdriver-smcb"));

    final var snapshot = service.enableDirectRouting("zeta-achelos");
    var direct = json.readTree(kubectl.currentIngresses.get("testdriver-smcb"));
    var originalPaths = original.at("/spec/rules/0/http/paths");
    var directPaths = direct.at("/spec/rules/0/http/paths");

    assertEquals(originalPaths.get(0), directPaths.get(0));
    assertEquals("/td-smcb/(proxy)(/|$)(.*)", directPaths.get(1).path("path").asText());
    assertEquals("/td-smcb/proxy/achelos_testfachdienst/ws",
        rewrittenPath(originalPaths.get(1), "/td-smcb/proxy/achelos_testfachdienst/ws"));
    assertEquals("/proxy/achelos_testfachdienst/ws",
        rewrittenPath(directPaths.get(1), "/td-smcb/proxy/achelos_testfachdienst/ws"));
    assertEquals("testdriver-smcb", directPaths.get(1).at("/backend/service/name").asText());
    assertEquals("http", directPaths.get(1).at("/backend/service/port/name").asText());
    assertEquals(originalPaths.get(2), directPaths.get(2));
    assertEquals(originalPaths.get(3), directPaths.get(3));
    assertEquals("tiger-proxy", directPaths.get(3).at("/backend/service/name").asText());
    assertEquals("/proxy/achelos_testfachdienst/ws",
        rewrittenPath(directPaths.get(3), "/proxy/achelos_testfachdienst/ws"));
    assertEquals("tiger-proxy,testdriver-smcb",
        direct.at("/metadata/annotations/nginx.org~1websocket-services").asText());
    assertEquals(original.at("/metadata/annotations/nginx.org~1rewrite-target"),
        direct.at("/metadata/annotations/nginx.org~1rewrite-target"));
    assertEquals(original.at("/metadata/annotations/nginx.org~1path-regex"),
        direct.at("/metadata/annotations/nginx.org~1path-regex"));

    service.restore(snapshot);
    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
  }

  /** Adds the direct driver's WebSocket support without disabling the legacy Tiger-backed route. */
  @Test
  void addsSmcbWebsocketServiceAndRestoresOriginalAnnotation() {
    var kubectl = new FakeKubectlService(false);
    var json = new ObjectMapper();
    var original = json.readTree(kubectl.originalIngresses.get("testdriver-smcb"));
    ((ObjectNode) original.at("/metadata/annotations"))
        .put("nginx.org/websocket-services", "tiger-proxy");
    kubectl.currentIngresses.put("testdriver-smcb", original.toString());
    var service = new PerformanceIngressRoutingService(kubectl);

    var snapshot = service.enableDirectRouting("zeta-achelos");
    var direct = json.readTree(kubectl.currentIngresses.get("testdriver-smcb"));

    assertEquals("tiger-proxy,testdriver-smcb",
        direct.at("/metadata/annotations/nginx.org~1websocket-services").asText());
    assertEquals("tiger-proxy", direct.at("/spec/rules/0/http/paths/3/backend/service/name").asText());

    service.restore(snapshot);
    assertEquals(original, json.readTree(kubectl.currentIngresses.get("testdriver-smcb")));
  }

  /** An absent WebSocket annotation is restored as absent after enabling direct driver upgrades. */
  @Test
  void restoresAbsentSmcbWebsocketAnnotation() {
    var kubectl = new FakeKubectlService(false, false);
    var json = new ObjectMapper();
    var original = json.readTree(kubectl.originalIngresses.get("testdriver-smcb"));
    ((ObjectNode) original.at("/metadata/annotations")).remove("nginx.org/websocket-services");
    kubectl.currentIngresses.put("testdriver-smcb", original.toString());
    var service = new PerformanceIngressRoutingService(kubectl);

    var snapshot = service.enableDirectRouting("zeta-achelos");
    var direct = json.readTree(kubectl.currentIngresses.get("testdriver-smcb"));

    assertEquals("testdriver-smcb", direct.at("/metadata/annotations/nginx.org~1websocket-services").asText());

    service.restore(snapshot);
    assertEquals(original, json.readTree(kubectl.currentIngresses.get("testdriver-smcb")));
  }

  /** A missing proxy route is a setup failure and must not cause partial routing changes. */
  @Test
  void missingSmcbProxyPathPreventsAllRoutingMutations() {
    var kubectl = new FakeKubectlService(false);
    kubectl.currentIngresses.compute("testdriver-smcb",
        (name, value) -> value.replace("/(td-smcb/proxy)(/|$)(.*)", "/unexpected"));
    var service = new PerformanceIngressRoutingService(kubectl);

    var failure = assertThrows(AssertionError.class, () -> service.enableDirectRouting("zeta-achelos"));

    assertTrue(failure.getMessage().contains("testdriver-smcb"));
    assertTrue(failure.getMessage().contains("/td-smcb/(proxy)(/|$)(.*)"));
    assertTrue(kubectl.patchTargets.isEmpty());
    assertEquals(0, kubectl.pepRestartCount);
  }

  /** Handles a deployment that already routes prefixed SMC-B traffic directly. */
  @Test
  void routesAndRestoresAlreadyDirectSmcbIngress() {
    var kubectl = new FakeKubectlService(false, false);
    var service = new PerformanceIngressRoutingService(kubectl);
    final var snapshot = service.enableDirectRouting("zeta-achelos");
    var direct = new ObjectMapper().readTree(kubectl.currentIngresses.get("testdriver-smcb"));
    var proxy = direct.at("/spec/rules/0/http/paths/1");

    assertEquals("/td-smcb/(proxy)(/|$)(.*)", proxy.path("path").asText());
    assertEquals("testdriver-smcb", proxy.at("/backend/service/name").asText());
    assertEquals("testdriver-smcb", direct.at("/metadata/annotations/nginx.org~1websocket-services").asText());
    assertEquals("/proxy/achelos_testfachdienst/ws",
        rewrittenPath(proxy, "/td-smcb/proxy/achelos_testfachdienst/ws"));

    service.restore(snapshot);
    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
  }

  /** Retains the backup rather than overwriting an externally changed path during cleanup. */
  @Test
  void rejectsExternalSmcbPathChangeBeforeAnyRestoration() {
    var kubectl = new FakeKubectlService(false);
    var service = new PerformanceIngressRoutingService(kubectl);
    var snapshot = service.enableDirectRouting("zeta-achelos");
    kubectl.currentIngresses.compute("testdriver-smcb",
        (name, value) -> value.replace("/td-smcb/(proxy)(/|$)(.*)", "/external"));
    var before = kubectl.patchTargets.size();

    assertThrows(AssertionError.class, () -> service.restore(snapshot));

    assertEquals(before, kubectl.patchTargets.size());
    assertTrue(kubectl.currentIngresses.get("testdriver-smcb").contains("/external"));
    assertTrue(kubectl.journal.path("data").has("snapshot"));
  }

  /** Verifies an HTTP-only authserver does not receive an SSL services annotation. */
  @Test
  void routesAuthserverOverHttpWithoutSslAnnotation() {
    var kubectl = new FakeKubectlService(false);
    var service = new PerformanceIngressRoutingService(kubectl);

    var snapshot = service.enableDirectRouting("zeta-dev");

    var authPatch = kubectl.firstPatchFor("zeta-guard-auth");
    assertTrue(authPatch.contains("\"name\":\"http\""));
    assertFalse(authPatch.contains("ssl-services"));

    service.restore(snapshot);
    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
    assertEquals(kubectl.originalPepConfig, kubectl.currentPepConfig);
    assertEquals(kubectl.originalPepConfig, kubectl.activePepConfig);
  }

  /** Verifies a failed PEP rollout restores routes and the original active nginx configuration. */
  @Test
  void rollsBackPepConfigurationWhenDirectRolloutFails() {
    var kubectl = new FakeKubectlService(true);
    kubectl.failPepRolloutOnce = true;
    var service = new PerformanceIngressRoutingService(kubectl);

    assertThrows(AssertionError.class, () -> service.enableDirectRouting("zeta-staging"));

    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
    assertEquals(kubectl.originalPepConfig, kubectl.currentPepConfig);
    assertEquals(kubectl.originalPepConfig, kubectl.activePepConfig);
  }

  /** Verifies a partial direct-routing failure immediately rolls every ingress back. */
  @Test
  void rollsBackCapturedRoutesWhenDirectRoutingFailsPartially() {
    var kubectl = new FakeKubectlService(true);
    kubectl.failPatchIngressOnce = "zeta-guard-auth";
    var service = new PerformanceIngressRoutingService(kubectl);

    assertThrows(AssertionError.class, () -> service.enableDirectRouting("zeta-staging"));

    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
  }

  /** Recovers a durable backup with a fresh service after the owning process disappeared. */
  @Test
  void recoversAfterProcessTermination() {
    var kubectl = new FakeKubectlService(true);
    new PerformanceIngressRoutingService(kubectl).enableDirectRouting("zeta-staging");
    ((ObjectNode) kubectl.journal.path("data")).put("pid", "2147483647");

    new PerformanceIngressRoutingService(kubectl).recoverAbandonedRouting("zeta-staging");

    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
    assertEquals(kubectl.originalPepConfig, kubectl.activePepConfig);
    assertTrue(kubectl.journal.path("data").isEmpty());
  }

  /** Leaves another active scenario's routes and backup untouched. */
  @Test
  void refusesRecoveryAndSwitchingWhileOwnerIsAlive() {
    var kubectl = new FakeKubectlService(true);
    var first = new PerformanceIngressRoutingService(kubectl);
    final var snapshot = first.enableDirectRouting("zeta-staging");
    var before = kubectl.patchTargets.size();
    var second = new PerformanceIngressRoutingService(kubectl);

    assertThrows(AssertionError.class, () -> second.recoverAbandonedRouting("zeta-staging"));
    assertThrows(AssertionError.class, () -> second.enableDirectRouting("zeta-staging"));
    assertEquals(before, kubectl.patchTargets.size());
    first.restore(snapshot);
  }

  /** Retains failed restoration evidence and retries it before the next scenario. */
  @Test
  void retriesFailedRestorationFromDurableBackup() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);
    var snapshot = service.enableDirectRouting("zeta-staging");
    kubectl.failPepRolloutOnce = true;

    assertThrows(AssertionError.class, () -> service.restore(snapshot));
    assertTrue(kubectl.journal.path("data").has("snapshot"));
    new PerformanceIngressRoutingService(kubectl).recoverAbandonedRouting("zeta-staging");
    assertEquals(kubectl.originalPepConfig, kubectl.activePepConfig);
    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
    assertTrue(kubectl.journal.path("data").isEmpty());
  }

  /** Never overwrites an unrelated configuration change, even during cleanup. */
  @Test
  void preservesExternalPepChangesAndBackup() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);
    var snapshot = service.enableDirectRouting("zeta-staging");
    kubectl.currentPepConfig += "# external modification\n";
    var before = kubectl.patchTargets.size();

    assertThrows(AssertionError.class, () -> service.restore(snapshot));
    assertEquals(before, kubectl.patchTargets.size());
    assertTrue(kubectl.currentPepConfig.contains("external modification"));
    assertTrue(kubectl.journal.path("data").has("snapshot"));
  }

  /** Does not restore a resource recreated under the same name. */
  @Test
  void rejectsRecreatedIngressBeforeAnyRestoreMutation() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);
    var snapshot = service.enableDirectRouting("zeta-staging");
    kubectl.currentIngresses.compute("testdriver-smcb", (name, value) -> value.replace("stable", "recreated"));
    var before = kubectl.patchTargets.size();

    assertThrows(AssertionError.class, () -> service.restore(snapshot));
    assertEquals(before, kubectl.patchTargets.size());
    assertTrue(kubectl.journal.path("data").has("snapshot"));
  }

  /** Requires the owning host to resolve a backup whose process cannot be checked locally. */
  @Test
  void refusesForeignHostRecovery() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);
    final var snapshot = service.enableDirectRouting("zeta-staging");
    ((ObjectNode) kubectl.journal.path("data")).put("host", "other-host");
    var before = kubectl.patchTargets.size();

    assertThrows(AssertionError.class,
        () -> new PerformanceIngressRoutingService(kubectl).recoverAbandonedRouting("zeta-staging"));
    assertEquals(before, kubectl.patchTargets.size());
    service.restore(snapshot);
  }

  /** Normal scenarios do not create backups or mutate routing when there is nothing to recover. */
  @Test
  void doesNothingWithoutAbandonedBackup() {
    var kubectl = new FakeKubectlService(true);
    new PerformanceIngressRoutingService(kubectl).recoverAbandonedRouting("zeta-staging");
    assertTrue(kubectl.patchTargets.isEmpty());
    assertEquals(0, kubectl.pepRestartCount);
  }

  /** Deferred routing cleanup remains recoverable after dependent deployment cleanup failed. */
  @Test
  void deferredRestorationCanBeRecoveredByNextScenario() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);
    service.enableDirectRouting("zeta-staging");
    service.deferRestoration();
    assertTrue(kubectl.journal.path("data").has("snapshot"));
    new PerformanceIngressRoutingService(kubectl).recoverAbandonedRouting("zeta-staging");
    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
    assertEquals(kubectl.originalPepConfig, kubectl.activePepConfig);
    assertTrue(kubectl.journal.path("data").isEmpty());
  }

  /** Does not change routing if its durable backup cannot be saved first. */
  @Test
  void backupFailurePreventsAllRoutingMutations() {
    var kubectl = new FakeKubectlService(true);
    kubectl.failJournalWrite = true;

    assertThrows(AssertionError.class,
        () -> new PerformanceIngressRoutingService(kubectl).enableDirectRouting("zeta-staging"));
    assertTrue(kubectl.patchTargets.isEmpty());
    assertEquals(kubectl.originalIngresses, kubectl.currentIngresses);
  }

  /** A failed journal clear retains a recoverable backup despite restored routes. */
  @Test
  void failedJournalCompletionCanBeRetried() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);
    var snapshot = service.enableDirectRouting("zeta-staging");
    kubectl.failJournalWrite = true;
    assertThrows(AssertionError.class, () -> service.restore(snapshot));
    assertTrue(kubectl.journal.path("data").has("snapshot"));

    kubectl.failJournalWrite = false;
    new PerformanceIngressRoutingService(kubectl).recoverAbandonedRouting("zeta-staging");
    assertTrue(kubectl.journal.path("data").isEmpty());
  }

  /** Refuses a stale after-hook snapshot after another actor replaced the journal claim. */
  @Test
  void changedJournalOwnershipPreventsRestoration() {
    var kubectl = new FakeKubectlService(true);
    var service = new PerformanceIngressRoutingService(kubectl);
    var snapshot = service.enableDirectRouting("zeta-staging");
    ((ObjectNode) kubectl.journal.path("data")).put("owner", "different-run");
    var before = kubectl.patchTargets.size();

    assertThrows(AssertionError.class, () -> service.restore(snapshot));
    assertEquals(before, kubectl.patchTargets.size());
    assertTrue(kubectl.journal.path("data").has("snapshot"));
  }

  /**
   * Checks Cucumber's ascending Before and descending After ordering for routing protection.
   *
   * @throws NoSuchMethodException if a lifecycle method is renamed without updating the test
   */
  @Test
  void routingHooksSurroundOtherStateChanges() throws NoSuchMethodException {
    var recover = Hooks.class.getMethod("recoverAbandonedPerformanceRouting").getAnnotation(Before.class).order();
    var reset = Hooks.class.getMethod("resetTigerProxyState").getAnnotation(Before.class).order();
    var deployment = Hooks.class.getMethod("restoreDeploymentModifications", Scenario.class)
        .getAnnotation(After.class).order();
    var routing = Hooks.class.getMethod("restorePerformanceRouting", Scenario.class).getAnnotation(After.class).order();
    assertTrue(recover < reset);
    assertTrue(deployment > routing);
  }

  /**
   * Asserts that the first patch for an ingress contains expected JSON.
   *
   * @param kubectl fake kubectl service
   * @param ingressName ingress name
   * @param expected expected JSON fragment
   */
  private void assertPatchContains(
      final FakeKubectlService kubectl,
      final String ingressName,
      final String expected) {
    assertTrue(kubectl.firstPatchFor(ingressName).contains(expected));
  }

  /**
   * Applies the Helm ingress rewrite /$1$2$3 to a matching request path.
   *
   * @param route ingress path node
   * @param requestPath external request path
   * @return path received by the selected backend
   */
  private String rewrittenPath(final JsonNode route, final String requestPath) {
    var matcher = Pattern.compile(route.path("path").asText(), Pattern.CASE_INSENSITIVE).matcher(requestPath);
    assertTrue(matcher.matches(), "Configured driver request must match the ingress route");
    return "/" + matcher.group(1) + matcher.group(2) + matcher.group(3);
  }

  /** Stateful Kubernetes fake applying routing patches and journal resourceVersion checks. */
  private static final class FakeKubectlService extends ZetaDeploymentModificationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean https;
    private final boolean routeViaTigerProxy;
    private final Map<String, String> originalIngresses;
    private final Map<String, String> currentIngresses;
    private final Map<String, List<String>> patches = new HashMap<>();
    private final List<String> patchTargets = new ArrayList<>();
    private final List<Path> patchFiles = new ArrayList<>();
    private final String originalPepConfig = """
        pep_popp_issuer http://tiger-proxy/popp;
        location /pep/ {
          proxy_pass https://tiger-proxy:80/testfachdienst/;
        }
        """;
    private final String directPepConfig = """
        pep_popp_issuer http://popp-statics;
        location /pep/ {
          proxy_pass https://testfachdienst:443/;
        }
        """;
    private String currentPepConfig = originalPepConfig;
    private String activePepConfig = originalPepConfig;
    private int pepRestartCount;
    private String failPatchIngressOnce;
    private boolean failPepRolloutOnce;
    private ObjectNode journal;
    private boolean failJournalWrite;

    /**
     * Creates a fake Kubernetes environment.
     *
     * @param https whether the authserver exposes HTTPS
     */
    private FakeKubectlService(final boolean https) {
      this(https, true);
    }

    /**
     * Creates an environment with the selected SMC-B ingress routing mode.
     *
     * @param https whether the authserver exposes HTTPS
     * @param routeViaTigerProxy whether SMC-B proxy traffic initially uses TigerProxy
     */
    private FakeKubectlService(final boolean https, final boolean routeViaTigerProxy) {
      super(1, 1);
      this.https = https;
      this.routeViaTigerProxy = routeViaTigerProxy;
      this.originalIngresses = originalIngresses();
      this.currentIngresses = new HashMap<>(originalIngresses);
    }

    /**
     * Emulates the kubectl get and patch commands used by the routing service.
     *
     * @param arguments kubectl arguments
     * @param verbose ignored logging flag
     * @return canned command result
     */
    @Override
    public CommandResult executeKubectlCommand(
        final List<String> arguments,
        final boolean verbose) {
      return execute(arguments);
    }

    /**
     * Emulates the boolean-first kubectl overload.
     *
     * @param verbose ignored logging flag
     * @param arguments kubectl arguments
     * @return canned command result
     */
    @Override
    public CommandResult executeKubectlCommand(
        final boolean verbose,
        final List<String> arguments) {
      return execute(arguments);
    }

    /**
     * Emulates the varargs kubectl overload used by production code.
     *
     * @param verbose ignored logging flag
     * @param arguments kubectl arguments
     * @return canned command result
     */
    @Override
    public CommandResult executeKubectlCommand(
        final boolean verbose,
        final String... arguments) {
      return execute(List.of(arguments));
    }

    /**
     * Activates the currently stored fake PEP configuration after a simulated rollout.
     *
     * @param namespace ignored namespace
     * @param deploymentName expected PEP deployment
     * @param timeoutSeconds ignored timeout
     * @return simulated rollout result
     */
    @Override
    public CommandResult waitForDeploymentRollout(
        final String namespace,
        final String deploymentName,
        final int timeoutSeconds) {
      activePepConfig = currentPepConfig;
      pepRestartCount++;
      var command = List.of("kubectl", "rollout", "status", deploymentName);
      if (failPepRolloutOnce) {
        failPepRolloutOnce = false;
        return new CommandResult(command, 1, "", "Simulated PEP rollout failure");
      }
      return success(command, "deployment successfully rolled out");
    }

    /**
     * Dispatches one fake kubectl invocation.
     *
     * @param arguments kubectl arguments
     * @return fake command result
     */
    private CommandResult execute(final List<String> arguments) {
      if (arguments.contains("testsuite-performance-routing") && arguments.contains("get")) {
        return success(arguments, journal == null ? "" : journal.toString());
      }
      if (arguments.contains("create") || arguments.contains("replace")) {
        if (failJournalWrite) {
          return new CommandResult(arguments, 1, "", "Simulated journal write failure");
        }
        try {
          var next = (ObjectNode) JSON.readTree(Files.readString(
              Path.of(arguments.get(arguments.indexOf("-f") + 1))));
          if ((arguments.contains("create") && journal != null)
              || (arguments.contains("replace") && (journal == null
              || !next.path("metadata").path("resourceVersion")
                  .equals(journal.path("metadata").path("resourceVersion"))))) {
            return new CommandResult(arguments, 1, "", "Conflict");
          }
          ((ObjectNode) next.path("metadata")).put("resourceVersion",
              Integer.toString(journal == null ? 1 : journal.path("metadata").path("resourceVersion").asInt() + 1));
          journal = next;
          return success(arguments, journal.toString());
        } catch (IOException exception) {
          throw new AssertionError(exception);
        }
      }
      if (arguments.contains("get") && arguments.contains("deployment")) {
        return success(arguments, authserverDeployment(https));
      }
      if (arguments.contains("get") && arguments.contains("ingress")) {
        var ingressName = arguments.get(arguments.indexOf("ingress") + 1);
        return success(arguments, currentIngresses.get(ingressName));
      }
      if (arguments.contains("get") && arguments.contains("configmap")) {
        return success(arguments, pepConfigMap(currentPepConfig));
      }
      if (arguments.contains("exec")) {
        return success(arguments, activePepConfig.replaceFirst("\n\\z", ""));
      }
      if (arguments.contains("rollout") && arguments.contains("restart")) {
        return success(arguments, "deployment.apps/pep-deployment restarted");
      }
      if (arguments.contains("patch")) {
        assertTrue(journal.path("data").has("snapshot"), "Durable backup must precede any routing mutation");
        var target = arguments.get(arguments.indexOf("patch") + 1);
        patchTargets.add(target);
        var patchFileIndex = arguments.indexOf("--patch-file");
        if (patchFileIndex < 0
            || !arguments.contains("--field-manager=helm")
            || arguments.stream().anyMatch(argument -> argument.startsWith("--patch="))) {
          return new CommandResult(arguments, 1, "", "Expected a --patch-file argument");
        }
        var patchFile = Path.of(arguments.get(patchFileIndex + 1));
        patchFiles.add(patchFile);
        final String patch;
        try {
          patch = Files.readString(patchFile);
        } catch (IOException exception) {
          return new CommandResult(arguments, 1, "", "Could not read patch file");
        }
        if (target.equals("configmap/pep-test-nginx-conf")) {
          return patchPepConfigMap(arguments, patch);
        }
        if (!target.startsWith("ingress/")) {
          return new CommandResult(arguments, 1, "", "Unexpected patch target");
        }
        var ingressName = target.substring("ingress/".length());
        patches.computeIfAbsent(ingressName, ignored -> new ArrayList<>()).add(patch);
        if (ingressName.equals(failPatchIngressOnce)) {
          failPatchIngressOnce = null;
          return new CommandResult(arguments, 1, "", "Simulated patch failure");
        }
        currentIngresses.put(ingressName, applyPatch(JSON.readTree(currentIngresses.get(ingressName)), patch).toString());
        return success(arguments, "ingress.networking.k8s.io/" + ingressName + " patched");
      }
      return new CommandResult(arguments, 1, "", "Unexpected fake kubectl command");
    }

    /**
     * Applies one fake PEP ConfigMap merge patch.
     *
     * @param arguments fake kubectl arguments
     * @param patch serialized merge patch
     * @return fake command result
     */
    private CommandResult patchPepConfigMap(
        final List<String> arguments,
        final String patch) {
      try {
        currentPepConfig = applyPatch(JSON.readTree(pepConfigMap(currentPepConfig)), patch)
            .path("data").path("nginx.conf").asText();
      } catch (JacksonException exception) {
        return new CommandResult(arguments, 1, "", "Could not parse ConfigMap patch");
      }
      return success(arguments, "configmap/pep-test-nginx-conf patched");
    }

    /**
     * Creates the current fake PEP ConfigMap JSON.
     *
     * @param configuration nginx configuration value
     * @return ConfigMap JSON
     */
    private String pepConfigMap(final String configuration) {
      var configMap = JSON.createObjectNode();
      configMap.putObject("metadata").put("uid", "pep-config").put("resourceVersion", "1");
      configMap.putObject("data").put("nginx.conf", configuration);
      return configMap.toString();
    }

    /**
     * Returns the first patch issued for one ingress.
     *
     * @param ingressName ingress name
     * @return JSON patch
     */
    private String firstPatchFor(final String ingressName) {
      return patches.get(ingressName).getFirst();
    }

    /**
     * Creates a successful command result.
     *
     * @param command fake command
     * @param stdout standard output
     * @return successful result
     */
    private CommandResult success(final List<String> command, final String stdout) {
      return new CommandResult(command, 0, stdout, "");
    }

    /**
     * Creates original TigerProxy-backed ingress resources.
     *
     * @return ingress JSON by name
     */
    private Map<String, String> originalIngresses() {
      return Map.of(
          "zeta-guard-pep", ingress(
              "zeta-guard-pep", "/", "tiger-proxy", "number", "80",
              "nginx.org/websocket-services", "tiger-proxy"),
          "zeta-guard-auth", ingress(
              "zeta-guard-auth", "/auth", "tiger-proxy", "number", "80",
              null, null),
          "testdriver-smcb", smcbIngress());
    }

    /**
     * Creates the split SMC-B ingress with regex paths and rewrite annotations from Helm.
     *
     * @return SMC-B ingress JSON
     */
    private String smcbIngress() {
      var ingress = JSON.readTree(ingress(
          "testdriver-smcb", "/td-smcb/(testdriver-api)(/|$)(.*)", "testdriver-smcb", "number", "80",
          "nginx.org/websocket-services", "tiger-proxy,testdriver-smcb"));
      var annotations = (ObjectNode) ingress.at("/metadata/annotations");
      annotations.put("nginx.org/path-regex", "case_insensitive");
      annotations.put("nginx.org/rewrite-target", "/$1$2$3");
      var paths = (ArrayNode) ingress.at("/spec/rules/0/http/paths");
      var proxy = paths.get(0).deepCopy();
      ((ObjectNode) proxy).put("path", routeViaTigerProxy
          ? "/(td-smcb/proxy)(/|$)(.*)" : "/td-smcb/(proxy)(/|$)(.*)");
      ((ObjectNode) proxy.at("/backend/service")).put("name", routeViaTigerProxy ? "tiger-proxy" : "testdriver-smcb");
      paths.add(proxy);
      var load = paths.get(0).deepCopy();
      ((ObjectNode) load).put("path", "/td-smcb/(load)(/|$)(.*)");
      paths.add(load);
      var compatibility = proxy.deepCopy();
      ((ObjectNode) compatibility).put("path", "/(proxy)(/|$)(.*)");
      paths.add(compatibility);
      if (!routeViaTigerProxy) {
        annotations.put("nginx.org/websocket-services", "testdriver-smcb");
      }
      return ingress.toString();
    }

    /**
     * Creates one minimal ingress JSON document.
     *
     * @param name ingress name
     * @param path ingress path
     * @param service backend service
     * @param portKind {@code name} or {@code number}
     * @param port port value
     * @param annotationName optional annotation name
     * @param annotationValue optional annotation value
     * @return ingress JSON
     */
    private String ingress(
        final String name,
        final String path,
        final String service,
        final String portKind,
        final String port,
        final String annotationName,
        final String annotationValue) {
      var annotation = annotationName == null
          ? "\"meta.helm.sh/release-name\":\"test\""
          : "\"meta.helm.sh/release-name\":\"test\",\"" + annotationName
              + "\":\"" + annotationValue + "\"";
      var portValue = "number".equals(portKind) ? port : "\"" + port + "\"";
      return JSON.readTree("""
          {
            "metadata":{"name":"%s","uid":"stable","resourceVersion":"1","annotations":{%s}},
            "spec":{"rules":[{"http":{"paths":[{
              "path":"%s",
              "backend":{"service":{"name":"%s","port":{"%s":%s}}}
            }]}}]}
          }
          """.formatted(name, annotation, path, service, portKind, portValue)).toString();
    }

    /**
     * Applies actual JSON patch operations including concurrency preconditions.
     *
     * @param resource current object
     * @param patch JSON patch
     * @return changed object
     */
    private JsonNode applyPatch(final JsonNode resource, final String patch) {
      var updated = resource.deepCopy();
      for (var operation : JSON.readTree(patch)) {
        var pointer = operation.path("path").asText();
        if ("test".equals(operation.path("op").asText())) {
          assertEquals(operation.path("value"), updated.at(pointer));
          continue;
        }
        var separator = pointer.lastIndexOf('/');
        var parent = (ObjectNode) updated.at(pointer.substring(0, separator));
        var key = pointer.substring(separator + 1).replace("~1", "/").replace("~0", "~");
        if ("remove".equals(operation.path("op").asText())) {
          parent.remove(key);
        } else {
          parent.set(key, operation.path("value"));
        }
      }
      return updated;
    }

    /**
     * Creates a minimal authserver deployment.
     *
     * @param useHttps whether to expose the HTTPS port
     * @return deployment JSON
     */
    private String authserverDeployment(final boolean useHttps) {
      var ports = useHttps
          ? "[{\"name\":\"http-port\"},{\"name\":\"https-port\"}]"
          : "[{\"name\":\"http-port\"}]";
      return "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"ports\":"
          + ports + "}]}}}}";
    }
  }
}
