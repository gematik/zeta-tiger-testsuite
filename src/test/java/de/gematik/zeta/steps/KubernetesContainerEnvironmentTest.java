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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks that channel preconditions cannot pass on absent configuration or partial CSV matches.
 */
class KubernetesContainerEnvironmentTest {

  /**
   * Preserves the literal CSV representation and selects the exact container and environment entry.
   */
  @Test
  void readsLiteralValueFromSelectedContainer() {
    var pod = podWithEnvironment("""
        [{"name":"NOTIFICATION_CHANNELS_ALLOWED","value":" other, zeta "}]
        """);
    assertThat(KubernetesProbeSteps.literalContainerEnvironment(
        pod, "notification-service", "NOTIFICATION_CHANNELS_ALLOWED")).isEqualTo(" other, zeta ");
    assertThatThrownBy(() -> KubernetesProbeSteps.literalContainerEnvironment(
        pod, "missing-container", "NOTIFICATION_CHANNELS_ALLOWED")).isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> KubernetesProbeSteps.literalContainerEnvironment(
        pod, "notification-service", "OTHER_ENTRY")).isInstanceOf(AssertionError.class);
  }

  /**
   * Rejects absent, ambiguous, empty, indirect and expanded values instead of treating them as an empty list.
   *
   * @param entries Kubernetes environment entries
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "[]",
      "[{\"name\":\"NOTIFICATION_CHANNELS_ALLOWED\",\"value\":\" \"}]",
      "[{\"name\":\"NOTIFICATION_CHANNELS_ALLOWED\",\"value\":null}]",
      "[{\"name\":\"NOTIFICATION_CHANNELS_ALLOWED\",\"value\":42}]",
      "[{\"name\":\"NOTIFICATION_CHANNELS_ALLOWED\",\"valueFrom\":{\"configMapKeyRef\":{}}}]",
      "[{\"name\":\"NOTIFICATION_CHANNELS_ALLOWED\",\"value\":\"$(CHANNELS)\"}]",
      "[{\"name\":\"NOTIFICATION_CHANNELS_ALLOWED\",\"value\":\"zeta\"},"
          + "{\"name\":\"NOTIFICATION_CHANNELS_ALLOWED\",\"value\":\"other\"}]"
  })
  void rejectsUnresolvedEnvironment(String entries) {
    assertThatThrownBy(() -> KubernetesProbeSteps.literalContainerEnvironment(
        podWithEnvironment(entries), "notification-service", "NOTIFICATION_CHANNELS_ALLOWED"))
        .isInstanceOf(AssertionError.class);
  }

  /**
   * Rejects missing or non-running pod data before using any environment value as evidence.
   */
  @Test
  void rejectsUnavailablePod() {
    assertThatThrownBy(() -> KubernetesProbeSteps.literalContainerEnvironment(
        null, "notification-service", "NOTIFICATION_CHANNELS_ALLOWED")).isInstanceOf(AssertionError.class);
    var pod = new ObjectMapper().readTree("{\"status\":{\"phase\":\"Pending\"}}");
    assertThatThrownBy(() -> KubernetesProbeSteps.literalContainerEnvironment(
        pod, "notification-service", "NOTIFICATION_CHANNELS_ALLOWED")).isInstanceOf(AssertionError.class);
  }

  /**
   * Exercises the actual Gherkin regexes against full entries, whitespace, extra channels and lookalikes.
   *
   * @param csv configured CSV string
   * @param referenceAllowed whether the reference channel is present
   * @param negativeExcluded whether the negative channel is absent
   * @throws IOException if the feature cannot be read
   */
  @ParameterizedTest
  @CsvSource(value = {
      "zeta|true|true",
      "other, zeta , more|true|true",
      "zeta.more|false|true",
      "other|false|true",
      "zeta,zeta.testsuite.not_assigned|true|false",
      " zeta.testsuite.not_assigned ,zeta|true|false",
      "zeta, zeta.testsuite.not_assigned ,other|true|false",
      "zeta,zeta.testsuite.not_assigned.more|true|true",
      "zeta,zetaXtestsuiteXnot_assigned|true|true"
  }, delimiter = '|')
  void gherkinChecksExactCsvMembership(String csv, boolean referenceAllowed, boolean negativeExcluded)
      throws IOException {
    var feature = Path.of("src/test/resources/features/UserStory_02/UseCase_01/"
        + "push_benachrichtigungen_verwalten.feature");
    var prefix = "    Und TGR prüfe Variable \"notificationAllowedChannels\" stimmt überein mit \"";
    var regexes = Files.readAllLines(feature).stream().filter(line -> line.startsWith(prefix))
        .map(line -> line.substring(prefix.length(), line.length() - 1)
            .replace("${testdata.notificationService.notAllowedChannel}", "zeta.testsuite.not_assigned"))
        .toList();
    assertThat(regexes).hasSize(2);
    assertThat(Pattern.matches(regexes.get(0), csv)).isEqualTo(referenceAllowed);
    assertThat(Pattern.matches(regexes.get(1), csv)).isEqualTo(negativeExcluded);
  }

  /**
   * Builds a running pod with an unrelated sidecar to keep container selection explicit.
   *
   * @param entries JSON array for the selected container's environment
   * @return pod fixture
   */
  private static JsonNode podWithEnvironment(String entries) {
    return new ObjectMapper().readTree("""
        {"metadata":{"uid":"test-pod"},
         "status":{"phase":"Running","containerStatuses":[
           {"name":"notification-service","ready":true,"state":{"running":{}}}]},
         "spec":{"containers":[
           {"name":"sidecar","env":[{"name":"NOTIFICATION_CHANNELS_ALLOWED","value":"wrong"}]},
           {"name":"notification-service","env":%s}]}}
        """.formatted(entries));
  }
}
