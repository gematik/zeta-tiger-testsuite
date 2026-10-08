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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves endpoint-specific rate-limit configuration evidence from Kubernetes resource JSON.
 */
public final class KubernetesRateLimitConfigurationEvidence {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Prevents construction of the utility class.
   */
  private KubernetesRateLimitConfigurationEvidence() {
  }

  /**
   * Finds rate-limit configuration evidence for one endpoint in Kubernetes resource JSON.
   *
   * @param resourcesJson Kubernetes list JSON returned by {@code kubectl get ... -o json}
   * @param endpoint      endpoint path that must have rate-limit configuration evidence
   * @param configuration expected rate-limit configuration marker
   * @return report evidence when a matching resource exists
   */
  public static Optional<String> find(String resourcesJson, String endpoint, String configuration) {
    return findNormalized(resourcesJson, normalizeEndpointPath(endpoint), normalizeConfigMarker(configuration));
  }

  /**
   * Finds rate-limit configuration evidence for one normalized endpoint in Kubernetes resource JSON.
   *
   * @param resourcesJson Kubernetes list JSON returned by {@code kubectl get ... -o json}
   * @param endpoint      normalized endpoint path that must have rate-limit configuration evidence
   * @param configuration normalized expected rate-limit configuration marker
   * @return report evidence when a matching resource exists
   */
  public static Optional<String> findNormalized(String resourcesJson, String endpoint, String configuration) {
    try {
      var items = JSON.readTree(resourcesJson).path("items");
      if (!items.isArray()) {
        throw new AssertionError("kubectl returned no Kubernetes resource list in field 'items'.");
      }

      return findInItems(items, endpoint, configuration);
    } catch (JacksonException ex) {
      throw new AssertionError("Failed to parse Kubernetes resource JSON output.", ex);
    }
  }

  /**
   * Normalizes a path read from Gherkin/Tiger placeholders for text matching against Kubernetes resources.
   *
   * @param endpoint endpoint path or simple regex
   * @return literal endpoint path for resource matching
   */
  public static String normalizeEndpointPath(String endpoint) {
    return TigerGlobalConfiguration.resolvePlaceholders(endpoint)
        .replaceFirst("^\\.\\*", "")
        .replaceFirst("\\$$", "")
        .trim();
  }

  /**
   * Normalizes a rate-limit configuration marker read from Gherkin/Tiger placeholders.
   *
   * @param configuration rate-limit configuration marker
   * @return literal marker for resource matching
   */
  public static String normalizeConfigMarker(String configuration) {
    return TigerGlobalConfiguration.resolvePlaceholders(configuration).trim();
  }

  /**
   * Finds the first Kubernetes resource that contains the endpoint and expected configuration marker.
   *
   * @param items         Kubernetes resource items
   * @param endpoint      endpoint path to match
   * @param configuration expected configuration marker
   * @return report evidence when a matching resource exists
   */
  private static Optional<String> findInItems(JsonNode items, String endpoint, String configuration) {
    for (var item : items) {
      var resourceContent = item.toPrettyString();
      if (!resourceContent.contains(endpoint) || !resourceContent.contains(configuration)) {
        continue;
      }

      var kind = item.path("kind").asString("<unknown>");
      var name = item.path("metadata").path("name").asString("<unknown>");
      return Optional.of("ENDPOINT=" + endpoint + " | CONFIG=" + configuration + " | RESOURCE=" + kind + "/" + name);
    }
    return Optional.empty();
  }
}
