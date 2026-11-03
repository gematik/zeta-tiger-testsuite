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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.model.CommandResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeycloakReadOnlyStoreTest {
  @Test
  void rejectsUnsafeTargetsAndNonReadOnlyTransactionsBeforeAnyCommand() {
    var service = mock(ZetaDeploymentModificationService.class);
    try (var configuration = mockStatic(ZetaDeploymentConfiguration.class)) {
      configuration.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
      assertThrows(
          AssertionError.class,
          () ->
              KeycloakReadOnlyStore.read(
                  "pod/db;exit", "BEGIN TRANSACTION READ ONLY; SELECT 1; COMMIT;"));
      assertThrows(
          AssertionError.class, () -> KeycloakReadOnlyStore.read("pod/db", "DELETE FROM client;"));
      verifyNoInteractions(service);
    }
  }

  @Test
  void preservesTheProjectionAndTimeoutAndRejectsAmbiguousPrimariesWithoutLeakingOutput() {
    final var commands = new ArrayList<List<String>>();
    final var service = mock(ZetaDeploymentModificationService.class);
    TigerGlobalConfiguration.putValue(
        "zetaDeploymentConfig.keycloak.databasePodSelector", "app=postgres");
    TigerGlobalConfiguration.putValue(
        "zetaDeploymentConfig.keycloak.databaseContainerName", "postgres");
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.databaseName", "keycloak");
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.kubectlRequestTimeout", "10s");
    try (var configuration = mockStatic(ZetaDeploymentConfiguration.class)) {
      configuration.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
      configuration.when(ZetaDeploymentConfiguration::getNamespace).thenReturn("test");
      when(service.executeKubectlCommand(eq(false), any(String[].class)))
          .thenAnswer(
              invocation -> {
                var args = List.of((String[]) invocation.getRawArguments()[1]);
                commands.add(args);
                return new CommandResult(
                    args, 0, args.contains("get") ? "pod/primary\n" : "{\"count\":1}\n", "");
              });
      var sql = "BEGIN TRANSACTION READ ONLY; SET LOCAL statement_timeout='5s'; SELECT 1; COMMIT;";
      assertEquals("{\"count\":1}\n", KeycloakReadOnlyStore.read(sql));
      assertEquals(sql, commands.getLast().getLast());
      assertEquals(2, commands.size());
      org.junit.jupiter.api.Assertions.assertTrue(
          commands.stream().allMatch(command -> command.contains("--request-timeout=10s")));
      when(service.executeKubectlCommand(eq(false), any(String[].class)))
          .thenReturn(
              new CommandResult(
                  List.of(), 0, "sensitive-primary-one\nsensitive-primary-two\n", ""));
      var error = assertThrows(AssertionError.class, KeycloakReadOnlyStore::primaryPod);
      assertFalse(error.toString().contains("sensitive"));
    } finally {
      TigerGlobalConfiguration.reset();
    }
  }
}
