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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression tests for Service identity matching in RBEL sender assertions.
 */
class KubernetesServiceSenderPatternTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Accepts the expected hostname and the current Service IP on the required port.
   */
  @Test
  void acceptsServiceNameAndCurrentClusterIp() {
    var pattern = pattern("10.96.130.94");
    assertThat("pep-proxy-svc:80").matches(pattern);
    assertThat("10.96.130.94:80").matches(pattern);
  }

  /**
   * Rejects unrelated Services, other ports, partial matches and regex lookalikes.
   */
  @Test
  void rejectsOtherEndpointsAndPartialMatches() {
    var pattern = pattern("10.96.130.94");
    assertThat("tiger-proxy:80").doesNotMatch(pattern);
    assertThat("10.96.130.95:80").doesNotMatch(pattern);
    assertThat("pep-proxy-svc:443").doesNotMatch(pattern);
    assertThat("10.96.130.94:8080").doesNotMatch(pattern);
    assertThat("prefix-pep-proxy-svc:80").doesNotMatch(pattern);
    assertThat("10x96x130x94:80").doesNotMatch(pattern);
  }

  /**
   * Uses a recreated Service's current IP instead of accepting a stale address.
   */
  @Test
  void followsChangedClusterIp() {
    var pattern = pattern("10.96.42.7");
    assertThat("10.96.42.7:80").matches(pattern);
    assertThat("10.96.130.94:80").doesNotMatch(pattern);
  }

  /**
   * Missing or headless Service addresses fail rather than broadening the assertion.
   */
  @Test
  void rejectsMissingOrHeadlessClusterIp() {
    for (var ip : new String[]{"", "None"}) {
      assertThatThrownBy(() -> pattern(ip)).isInstanceOf(AssertionError.class)
          .hasMessageContaining("no ClusterIP");
    }
    var description = service("10.96.130.94");
    ((tools.jackson.databind.node.ObjectNode) description.path("spec")).remove("clusterIP");
    assertThatThrownBy(() -> KubernetesProbeSteps.serviceSenderPattern(description, "pep-proxy-svc", 80))
        .isInstanceOf(AssertionError.class).hasMessageContaining("no ClusterIP");
  }

  /**
   * The resolver verifies the requested Service and its advertised TCP port.
   */
  @Test
  void rejectsWrongServiceOrNonTcpPort() {
    var description = service("10.96.130.94");
    assertThatThrownBy(() -> KubernetesProbeSteps.serviceSenderPattern(description, "other-service", 80))
        .isInstanceOf(AssertionError.class).hasMessageContaining("does not identify Service");
    assertThatThrownBy(() -> KubernetesProbeSteps.serviceSenderPattern(description, "pep-proxy-svc", 81))
        .isInstanceOf(AssertionError.class).hasMessageContaining("does not expose TCP port 81");
    ((tools.jackson.databind.node.ObjectNode) description.at("/spec/ports/0")).put("protocol", "UDP");
    assertThatThrownBy(() -> KubernetesProbeSteps.serviceSenderPattern(description, "pep-proxy-svc", 80))
        .isInstanceOf(AssertionError.class).hasMessageContaining("does not expose TCP port 80");
  }

  /**
   * Builds the anchored assertion from the selected live Service fixture.
   *
   * @param clusterIp current Service IP
   * @return sender regex
   */
  private static String pattern(String clusterIp) {
    return KubernetesProbeSteps.serviceSenderPattern(service(clusterIp), "pep-proxy-svc", 80);
  }

  /**
   * Models the fields returned by kubectl for a ClusterIP Service.
   *
   * @param clusterIp current Service IP
   * @return Service JSON
   */
  private static JsonNode service(String clusterIp) {
    var description = JSON.createObjectNode();
    description.putObject("metadata").put("name", "pep-proxy-svc");
    var spec = description.putObject("spec");
    spec.put("clusterIP", clusterIp);
    spec.putArray("ports").addObject().put("port", 80).put("protocol", "TCP");
    return description;
  }
}
