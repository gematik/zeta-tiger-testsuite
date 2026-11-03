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
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.zeta.services.model.CommandResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fault checks for independently observed PEP clock and container identity. */
class RawOtlpOperationClockTest {
  @Test
  void readsTheBoundContainersNanosecondClock() {
    var cluster = new Cluster();
    var clock = new RawOtlpOperationClock(cluster, "ns", "pep", "nginx");
    assertEquals(1792584846470915518L, clock.now());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1792584846%N", "", "-1", "1.2", "9999999999999999999999"})
  void rejectsUnsupportedOrMalformedClockOutput(String output) {
    var cluster = new Cluster();
    var clock = new RawOtlpOperationClock(cluster, "ns", "pep", "nginx");
    cluster.output = output;
    assertThrows(AssertionError.class, clock::now);
  }

  @Test
  void rejectsRestartBeforeClockRead() {
    var cluster = new Cluster();
    var clock = new RawOtlpOperationClock(cluster, "ns", "pep", "nginx");
    ((ObjectNode) cluster.pod.at("/status/containerStatuses/0")).put("restartCount", 1);
    assertThrows(AssertionError.class, clock::now);
  }

  @Test
  void rejectsPodReplacementDuringClockRead() {
    var cluster = new Cluster();
    var clock = new RawOtlpOperationClock(cluster, "ns", "pep", "nginx");
    cluster.replaceDuringRead = true;
    assertThrows(AssertionError.class, clock::now);
  }

  @Test
  void refusesAmbiguousPepPods() {
    var cluster = new Cluster();
    cluster.duplicatePod = true;
    assertThrows(AssertionError.class,
        () -> new RawOtlpOperationClock(cluster, "ns", "pep", "nginx"));
  }

  private static final class Cluster extends ZetaDeploymentModificationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectNode pod = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"pep-abc","uid":"uid-a"},"status":{"containerStatuses":[
         {"name":"nginx","ready":true,"restartCount":0,"imageID":"repo@sha256:abc",
          "state":{"running":{"startedAt":"2026-10-21T10:00:00Z"}}}]}}
        """);
    private String output = "1792584846470915518\n";
    private boolean duplicatePod;
    private boolean replaceDuringRead;

    private Cluster() {
      super(10, 10);
    }

    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) {
      var command = arguments.subList(2, arguments.size());
      final String result;
      if (command.getFirst().equals("exec")) {
        assertEquals(List.of("exec", "pep-abc", "-c", "nginx", "--", "date", "+%s%N"), command);
        result = output;
        if (replaceDuringRead) {
          ((ObjectNode) pod.path("metadata")).put("uid", "uid-b");
        }
      } else if (command.get(1).equals("deployment")) {
        result = "{\"spec\":{\"selector\":{\"matchLabels\":{\"app\":\"pep\"}}}}";
      } else if (command.get(1).equals("pods")) {
        var items = JSON.createArrayNode().add(pod);
        if (duplicatePod) {
          items.add(pod);
        }
        result = JSON.createObjectNode().set("items", items).toString();
      } else {
        result = pod.toString();
      }
      return new CommandResult(arguments, 0, result, "");
    }
  }
}
