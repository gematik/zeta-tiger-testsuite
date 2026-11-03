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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.testdriver.SdkLogoutProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SdkProbeRuntimeTest {
  @ParameterizedTest
  @ValueSource(strings = {"OIDC", "SMB"})
  void acceptsOnlyTheExplicitRequiredMode(String mode) {
    var env = new ObjectMapper().readTree("[{\"name\":\"AUTH_MODE\",\"value\":\"" + mode + "\"}]");
    SdkProbeRuntime.verifyAuthMode(env, mode);
    assertThrows(
        AssertionError.class,
        () -> SdkProbeRuntime.verifyAuthMode(env, mode.equals("SMB") ? "OIDC" : "SMB"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "null",
        "{}",
        "[{\"name\":\"AUTH_MODE\",\"value\":\"\"}]",
        "[{\"name\":\"AUTH_MODE\",\"valueFrom\":{}}]",
        "[{\"name\":\"AUTH_MODE\",\"value\":\"SMB\",\"valueFrom\":{}}]",
        "[{\"name\":\"AUTH_MODE\",\"value\":\"SMB\"},{\"name\":\"AUTH_MODE\",\"value\":\"OIDC\"}]",
        "[{\"name\":\"AUTH_MODE\",\"value\":\"SMB\"},{\"name\":\"AUTH_MODE\",\"value\":\"SMB\"}]"
      })
  void rejectsMissingIndirectAndDuplicateProfile(String json) {
    assertThrows(
        AssertionError.class,
        () -> SdkProbeRuntime.verifyAuthMode(new ObjectMapper().readTree(json), "SMB"));
  }

  @Test
  void rejectsUnknownRequiredProfile() {
    assertThrows(
        AssertionError.class,
        () ->
            SdkProbeRuntime.verifyAuthMode(
                new ObjectMapper().readTree("[{\"name\":\"AUTH_MODE\",\"value\":\"arbitrary\"}]"),
                "arbitrary"));
  }

  @Test
  void failedUploadStillCleansOnlyOwnedDirectoryAndSupportsRepeatedClose() throws Exception {
    var commands = new ArrayList<List<String>>();
    var service = mock(ZetaDeploymentModificationService.class);
    var pod =
        """
        {"metadata":{"name":"driver","uid":"original"},
         "spec":{"containers":[{"name":"driver","env":[{"name":"AUTH_MODE","value":"OIDC"}]}]},
         "status":{"containerStatuses":[{"name":"driver","ready":true,"imageID":"sha256:original",
           "containerID":"original","restartCount":0}]}}
        """;
    TigerGlobalConfiguration.putValue("probe.deployment", "driver");
    TigerGlobalConfiguration.putValue("probe.container", "driver");
    try (var configuration = mockStatic(ZetaDeploymentConfiguration.class)) {
      configuration.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
      configuration.when(ZetaDeploymentConfiguration::getNamespace).thenReturn("test");
      when(service.executeKubectlCommand(eq(false), anyList()))
          .thenAnswer(
              invocation -> {
                List<String> args = invocation.getArgument(1);
                commands.add(List.copyOf(args));
                var output = "";
                if (args.contains("deployment")) {
                  output = "{\"spec\":{\"selector\":{\"matchLabels\":{\"app\":\"driver\"}}}}";
                } else if (args.contains("pods")) {
                  output = "{\"items\":[" + pod + "]}";
                } else if (args.contains("get")) {
                  output = pod;
                }
                return new CommandResult(args, args.contains("cp") ? 1 : 0, output, "");
              });
      var runtime = new SdkProbeRuntime("probe", "cc-sdk-unit", 100);
      assertThrows(
          AssertionError.class, () -> runtime.start(SdkLogoutProbe.class, List.of(), List.of()));
      runtime.close();
      runtime.close();
      var staging =
          commands.stream()
              .filter(args -> args.contains("mkdir"))
              .findFirst()
              .orElseThrow()
              .getLast();
      var cleanup =
          commands.stream()
              .filter(args -> args.stream().anyMatch(s -> s.startsWith("find ")))
              .toList();
      assertEquals(1, cleanup.size());
      assertEquals(staging, cleanup.getFirst().getLast());
      var copy = commands.stream().filter(args -> args.contains("cp")).findFirst().orElseThrow();
      assertFalse(Files.exists(Path.of(copy.get(copy.indexOf("cp") + 1))));
      assertFalse(commands.stream().anyMatch(args -> args.contains("timeout")));
    } finally {
      TigerGlobalConfiguration.reset();
    }
  }
}
