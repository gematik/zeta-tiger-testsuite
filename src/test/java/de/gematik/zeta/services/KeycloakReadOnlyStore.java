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
import java.util.List;

/**
 * Executes the existing bounded read-only projections against the one primary AS database.
 */
public final class KeycloakReadOnlyStore {
  private KeycloakReadOnlyStore() {}

  /**
   * Finds exactly one primary database; no SQL, credentials or results are logged.
   */
  public static String primaryPod() {
    var result =
        ZetaDeploymentConfiguration.getServiceInstance()
            .executeKubectlCommand(
                false,
                "-n",
                ZetaDeploymentConfiguration.getNamespace(),
                "get",
                "pod",
                "-l",
                config("databasePodSelector"),
                "-o",
                "name",
                "--request-timeout=" + config("kubectlRequestTimeout"));
    var names = nonemptyLines(result.stdout());
    require(
        result.exitCode() == 0 && names.size() == 1, "Expected one primary Keycloak database pod");
    return names.getFirst();
  }

  /**
   * Keeps each projection's own transaction and statement timeout unchanged.
   */
  public static String read(String sql) {
    return read(primaryPod(), sql);
  }

  /**
   * Executes against an already selected primary so callers can retain precise clock brackets.
   */
  public static String read(String pod, String sql) {
    require(pod.matches("pod/[a-z0-9][a-z0-9-]*"), "Unsafe database pod selector");
    require(
        sql != null && sql.stripLeading().startsWith("BEGIN TRANSACTION READ ONLY;"),
        "The AS observation requires an explicit read-only transaction");
    var result =
        ZetaDeploymentConfiguration.getServiceInstance()
            .executeKubectlCommand(
                false,
                "-n",
                ZetaDeploymentConfiguration.getNamespace(),
                "exec",
                pod,
                "-c",
                config("databaseContainerName"),
                "--request-timeout=" + config("kubectlRequestTimeout"),
                "--",
                "psql",
                "-X",
                "-d",
                config("databaseName"),
                "-qAt",
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                sql);
    require(
        result.exitCode() == 0, "Read-only AS inventory query failed; check the storage profile");
    return result.stdout();
  }

  private static List<String> nonemptyLines(String value) {
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.keycloak." + key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
