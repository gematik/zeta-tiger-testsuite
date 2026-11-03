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

import de.gematik.zeta.services.model.CommandResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class NotificationRetentionProfileTest {
  @Test
  void preflightFailureDoesNotPublishPartialCleanupStateOrMutateTheCluster() {
    var calls = new ArrayList<List<String>>();
    var service =
        new ZetaDeploymentModificationService(5, 5) {
          @Override
          public CommandResult executeKubectlCommand(boolean verbose, List<String> args) {
            calls.add(List.copyOf(args));
            String result =
                calls.size() == 1
                    ? "{\"spec\":{\"template\":{},\"selector\":{\"matchLabels\":{\"app\":\"ns\"}}}}"
                    : "{\"items\":[]}";
            return new CommandResult(args, 0, result, "");
          }
        };
    var profile = new NotificationRetentionProfile(
        service, "test", "ns", NotificationDispatchProfile::retentionEnvironment);
    assertThrows(AssertionError.class, profile::start);
    assertDoesNotThrow(profile::close);
    assertEquals(2, calls.size());
    assertEquals(List.of("get", "get"), calls.stream().map(a -> a.get(2)).toList());
  }

  @Test
  void isolatesRoutingPinsImageAndPreservesOriginalProtection() {
    var json = new ObjectMapper();
    var source =
        json.readTree(
            """
            {"spec":{"template":{"metadata":{"labels":{"app":"shared-service"}},"spec":{
            "containers":[{"name":"ns","image":"ns:mutable","env":[
            {"name":"NOTIFICATION_PERSISTENCE_SEALING_ENABLED","value":"false"},
            {"name":"NOTIFICATION_HISTORY_ENABLED","value":"false"}]}]}}}}
            """);
    var original = source.deepCopy();
    var running = json.readTree("{\"spec\":{\"nodeName\":\"node1\"}}");
    var clone =
        NotificationRetentionProfile.manifest(
            source, running, "own", "ns@sha256:known",
            NotificationDispatchProfile::retentionEnvironment);
    assertEquals(original, source);
    assertEquals("ns@sha256:known", clone.at("/spec/containers/0/image").asString());
    assertEquals("Never", clone.at("/spec/restartPolicy").asString());
    assertEquals("own", clone.at("/metadata/labels/cc-ns-retention").asString());
    assertFalse(clone.at("/metadata/labels").has("app"));
    ((ObjectNode) source.at("/spec/template/spec")).put("hostNetwork", true);
    assertThrows(
        AssertionError.class,
        () -> NotificationRetentionProfile.manifest(
            source, running, "own", "image", NotificationDispatchProfile::retentionEnvironment));
  }
}
