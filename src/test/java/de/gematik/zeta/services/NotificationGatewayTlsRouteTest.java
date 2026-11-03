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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.gematik.zeta.services.model.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Checks conditional gateway routing and observed endpoint convergence. */
class NotificationGatewayTlsRouteTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void restoresOnlyTheOwnedSelectorAndCleanupIsRepeatable() throws Exception {
    var api = new RoutingApi();
    var route = new NotificationGatewayTlsRoute(api, "test", "gateway");
    route.start(peer());
    route.verify();
    route.close();
    route.close();
    assertEquals("original", api.live.at("/spec/selector/app").asString());
    assertEquals(2, api.patches);
    api.patchFiles.forEach(file -> assertFalse(Files.exists(file)));
  }

  /**
   * Waits for controller convergence within the configured Kubernetes readiness budget.
   *
   * @param attaching whether endpoint attachment or detachment is delayed
   * @throws Exception if route attachment is interrupted
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void usesConfiguredReadinessTimeoutForEndpointConvergence(boolean attaching) throws Exception {
    var api = new RoutingApi(8);
    var route = new NotificationGatewayTlsRoute(api, "test", "gateway");
    if (attaching) {
      api.convergenceDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5200);
    }
    route.start(peer());
    if (!attaching) {
      api.convergenceDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5200);
    }
    route.close();
    assertEquals("original", api.live.at("/spec/selector/app").asString());
  }

  @Test
  void lostPatchReplyStillRestoresTheOwnedRoute() {
    var api = new RoutingApi();
    api.loseReply = true;
    var route = new NotificationGatewayTlsRoute(api, "test", "gateway");
    var failure = assertThrows(AssertionError.class, () -> route.start(peer()));
    assertEquals("Gateway TLS fixture routing operation failed: patch (exit 1): lost reply",
        failure.getMessage());
    api.patchFiles.forEach(file -> assertFalse(Files.exists(file)));
    assertDoesNotThrow(route::close);
    assertEquals("original", api.live.at("/spec/selector/app").asString());
    api.patchFiles.forEach(file -> assertFalse(Files.exists(file)));
  }

  @Test
  void concurrentSelectorChangeAndServiceReplacementArePreserved() throws Exception {
    for (var replacement : List.of(false, true)) {
      var api = new RoutingApi();
      var route = new NotificationGatewayTlsRoute(api, "test", "gateway");
      route.start(peer());
      if (replacement) {
        ((ObjectNode) api.live.path("metadata")).put("uid", "foreign");
      } else {
        ((ObjectNode) api.live.path("spec")).putObject("selector").put("app", "foreign");
      }
      assertThrows(AssertionError.class, route::close);
      assertEquals(1, api.patches);
    }
  }

  @Test
  void ordinaryProductServicesCannotBeHijackedAsTlsFixtures() {
    var api = new RoutingApi();
    ((ObjectNode) api.live.path("metadata")).remove("annotations");
    assertThrows(AssertionError.class,
        () -> new NotificationGatewayTlsRoute(api, "test", "gateway"));
    assertEquals(0, api.patches);
  }

  private static TlsTestToolEndpoint peer() {
    var peer = mock(TlsTestToolEndpoint.class);
    when(peer.address()).thenReturn("10.0.0.8");
    when(peer.name()).thenReturn("tool");
    when(peer.selector()).thenReturn(JSON.readTree("{\"app\":\"tls-tool\"}"));
    return peer;
  }

  private static class RoutingApi extends ZetaDeploymentModificationService {
    final ObjectNode live = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"gateway","uid":"uid","resourceVersion":"1",
        "annotations":{"testsuite.gematik.de/tls-peer":"true"}},
        "spec":{"selector":{"app":"original"},"ports":[{"port":443,"targetPort":4433,"protocol":"TCP"}]}}
        """);
    int patches;
    boolean loseReply;
    long convergenceDeadline;
    final List<Path> patchFiles = new ArrayList<>();

    RoutingApi() {
      this(5);
    }

    RoutingApi(int readyTimeoutSeconds) {
      super(5, readyTimeoutSeconds);
    }

    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> args) {
      assertEquals(List.of("-n", "test"), args.subList(0, 2));
      String output = "";
      if (args.get(2).equals("patch")) {
        assertEquals(List.of("service", "gateway", "--type=json", "--patch-file"),
            args.subList(3, 7));
        var file = Path.of(args.getLast());
        patchFiles.add(file);
        String patch;
        try {
          patch = Files.readString(file);
        } catch (IOException e) {
          throw new AssertionError("Missing JSON patch file", e);
        }
        var operations = JSON.readTree(patch);
        for (var operation : operations) {
          if (operation.path("op").asString().equals("test")) {
            if (!live.at(operation.path("path").asString()).equals(operation.path("value"))) {
              return new CommandResult(args, 1, "", "conflict");
            }
          } else {
            ((ObjectNode) live.path("spec")).set("selector", operation.path("value"));
          }
        }
        patches++;
        if (loseReply) {
          loseReply = false;
          return new CommandResult(args, 1, "", "lost reply");
        }
      } else {
        assertEquals("get", args.get(2));
        if (args.get(3).equals("service")) {
          output = live.toString();
        } else {
          boolean connected = !live.at("/spec/selector/app").asString().equals("original");
          if (System.nanoTime() < convergenceDeadline) {
            connected = !connected;
          }
          output = connected ? """
              {"items":[{"endpoints":[{"conditions":{"ready":true},"addresses":["10.0.0.8"],
              "targetRef":{"name":"tool"}}]}]}
              """ : "{\"items\":[]}";
        }
      }
      return new CommandResult(args, 0, output, "");
    }
  }
}
