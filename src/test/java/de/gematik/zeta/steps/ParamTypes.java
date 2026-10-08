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

import de.gematik.zeta.JwtVariant;
import de.gematik.zeta.TigerMetric;
import io.cucumber.java.ParameterType;

/**
 * Central Cucumber parameter types for custom step definitions.
 */
public class ParamTypes {

  /**
   * Defines a parameter type for Tiger metrics. Allowed values: e2e_ms, forward_ms, service_ms,
   * return_ms, middleware_overhead_ms.
   *
   * @param value column name in Tiger CSV
   * @return TigerMetric matching the given value
   */
  @ParameterType("e2e_ms|forward_ms|service_ms|return_ms|middleware_overhead_ms")
  public TigerMetric tigerMetric(String value) {
    for (TigerMetric m : TigerMetric.values()) {
      if (m.getColumnName().equalsIgnoreCase(value)) {
        return m;
      }
    }
    throw new IllegalArgumentException("Unknown Tiger metric: " + value);
  }

  /**
   * Defines a parameter type for supported Kubernetes probe fields in pod specs.
   * Allowed values: {@code livenessProbe}, {@code readinessProbe}, {@code startupProbe}.
   *
   * @param probeName probe field name from the step text
   * @return normalized probe field name as-is
   */
  @ParameterType("livenessProbe|readinessProbe|startupProbe")
  public String kubeProbe(String probeName) {
    return probeName;
  }

  /**
   * Defines a parameter type for supported JWT manipulation variants.
   *
   * @param variant JWT variant token from the step
   * @return matching JWT variant
   */
  @ParameterType(JwtVariant.PATTERN)
  public JwtVariant jwtVariant(String variant) {
    return JwtVariant.fromToken(variant);
  }

  /**
   * Defines a parameter type for toggling a binary configuration.
   *
   * @param action action from the step text
   * @return {@code true} for enable actions, {@code false} for disable actions
   */
  @ParameterType("aktiviere|deaktiviere|activate|deactivate")
  public boolean toggleAction(String action) {
    return switch (action) {
      case "aktiviere", "activate" -> true;
      case "deaktiviere", "deactivate" -> false;
      default -> throw new IllegalArgumentException("Unsupported toggle action: " + action);
    };
  }
}
