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

package de.gematik.zeta.services.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.HelmDeploymentRequest;
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import de.gematik.zeta.services.model.ZetaClientDataForwardingToggleRequest;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenValidityRequest;
import de.gematik.zeta.services.model.ZetaRequiredScopesRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Unit tests for ZetaDeploymentConfigurationService.
 */
@SuppressWarnings("unused")
public class ZetaDeploymentModificationServiceTest {

  final String evilDeploymentVersion = "6.6.6";

  private static final ZetaDeploymentDetails details = new ZetaDeploymentDetails(
      "zeta-local",
      "pep-deployment",
      "pep-test-nginx-conf",
      new String[]{"data", "nginx.conf"},
      "pep-well-known",
      new String[]{"data", "oauth-protected-resource"}
  );

  private final ZetaDeploymentModificationService service = new ZetaDeploymentModificationService(240, 120);

  @Test
  @Ignore
  public void verifyRequirements() {
    final String namespace = "zeta-local";
    service.verifyRequirements(namespace);
    Assert.assertThrows(AssertionError.class, () -> service.verifyRequirements("this-namespace-very-probably-does-not-exist"));
  }

  @Test
  @Ignore
  public void testConfigQuery() {
    CommandResult result =
        service.executeKubectlCommand("-n", "zeta-local", "get", "cm", "pep-test-nginx-conf", "-o", "yaml");

    assertTrue(result.stdout().contains("app.kubernetes.io/component: pep"));
    assertTrue(result.stderr().isBlank());
  }

  @Test
  @Ignore
  public void testConfigNonExistent() {
    CommandResult result =
        service.executeKubectlCommand("-n", "zeta-local", "get", "cm", "this-resource-does-not-exist", "-o", "yaml");

    assertFalse(result.stderr().isBlank());
  }

  @Test
  @Ignore
  public void restartPod() throws TimeoutException, InterruptedException {
    // wait for pod readiness
    service.restartPod("zeta-local", "pep-deployment", true, 120);
  }

  @Test
  @Ignore
  public void toggleAsl() {

    Map<String, String> envVars = Map.of(
        "SMB_KEYSTORE_PW_FILE", "/mnt/c/Users/usr/pdp-keystore-pass",
        "SMB_KEYSTORE_FILE_B64", "/mnt/c/Users/usr/pdp-keystore.b64"
    );
    final HelmDeploymentRequest enableAslRequest = new HelmDeploymentRequest(
        "/mnt/c/Users/usr/zeta-guard-helm/",
        "local",
        List.of("zeta-guard.pepproxy.asl_enabled=true"),
        envVars
    );

    final HelmDeploymentRequest disableAslRequest =  new HelmDeploymentRequest(
        "/mnt/c/Users/usr/zeta-guard-helm/",
        "local",
        List.of(),
        envVars
    );

    CommandResult enableResult = service.enableAsl(enableAslRequest);
    assertEquals(0, enableResult.exitCode());

    CommandResult disableResult = service.disableAsl(disableAslRequest);
    assertEquals(0, disableResult.exitCode());
  }

  /**
   * Verifies that generic Helm deployments pass all configured value overrides through the make target.
   */
  @Test
  public void deployZetaGuardWithHelmOverridesFormatsMultipleSetArgs() {
    List<String> capturedArgs = new ArrayList<>();
    Map<String, String> capturedEnv = new HashMap<>();
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeMakeCommand(String workDir, List<String> args, Map<String, String> environmentVariables)
          throws AssertionError {
        assertEquals("/helm", workDir);
        capturedArgs.addAll(args);
        capturedEnv.putAll(environmentVariables);
        return new CommandResult(List.of("make"), 1, "failed update", "rollback");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid", "zeta-guard.pepproxy.asl_enabled=true"),
        Map.of("SMB_KEYSTORE_FILE_B64", "/secret/keystore")
    );

    CommandResult result = capturingService.deployZetaGuardWithHelmOverrides(request);

    assertEquals(1, result.exitCode());
    assertEquals(List.of(
        "deploy",
        "stage=local",
        "HELM_EXTRA_VALUES_PARAMS='--set zeta-guard.pepproxy.image.tag=invalid "
            + "--set zeta-guard.pepproxy.asl_enabled=true'"
    ), capturedArgs);
    assertEquals("/secret/keystore", capturedEnv.get("SMB_KEYSTORE_FILE_B64"));
  }

  /**
   * Verifies that direct Helm rollback-on-failure deployments use the chart values and timeout from the request.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureFormatsHelmCommand() {
    List<String> capturedArgs = new ArrayList<>();
    Map<String, String> capturedEnv = new HashMap<>();
    List<Integer> capturedTimeouts = new ArrayList<>();
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        assertEquals("/helm", workDir);
        capturedArgs.addAll(args);
        capturedEnv.putAll(environmentVariables);
        capturedTimeouts.add(commandTimeoutSeconds);
        return new CommandResult(List.of("helm"), 1, "upgrade failed", "rolled back");
      }

      /**
       * Returns the current tiger-proxy Service IP used to preserve immutable Service fields.
       */
      @Override
      public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
        return new CommandResult(List.of("kubectl"), 0, "10.96.143.74", "");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore",
            "HELM_ARGS", "--force-conflicts"
        ),
        "zeta-existing-release",
        "private/values.local.yaml"
    );

    CommandResult result = capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m", 660);

    assertEquals(1, result.exitCode());
    assertEquals(List.of(660), capturedTimeouts);
    assertEquals(List.of(
        "upgrade",
        "--install",
        "zeta-existing-release",
        ".",
        "-f",
        "private/values.local.yaml",
        "--set",
        "zeta-guard.pepproxy.image.tag=invalid",
        "--set-file",
        "smcb_keystore.password=/secret/password",
        "--set-file",
        "smcb_keystore.keystore=/secret/keystore",
        "--force-conflicts",
        "--set-string",
        "global.dns.tigerStaticClusterIP=10.96.143.74",
        "-n",
        "zeta-local",
        "--rollback-on-failure",
        "--timeout",
        "2m"
    ), capturedArgs);
    assertEquals("/secret/keystore", capturedEnv.get("SMB_KEYSTORE_FILE_B64"));
  }

  /**
   * Verifies that the rollback proof uses a temporary Helm release instead of upgrading the shared ZETA release.
   */
  @Test
  public void verifyIsolatedPepHelmRollbackOnFailureUsesTemporaryReleaseOnly() {
    List<List<String>> capturedHelmArgs = new ArrayList<>();
    List<String> capturedWorkDirs = new ArrayList<>();
    List<Integer> capturedTimeouts = new ArrayList<>();
    List<String> verifiedDeployments = new ArrayList<>();
    String stableImage = "registry.example.test:443/zeta/zeta-guard/ngx_pep:main";
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        capturedWorkDirs.add(workDir);
        capturedHelmArgs.add(new ArrayList<>(args));
        capturedTimeouts.add(commandTimeoutSeconds);
        if (args.contains("--rollback-on-failure")) {
          return new CommandResult(List.of("helm"), 1, "upgrade failed", "timed out waiting for the condition; rolled back");
        }
        return new CommandResult(List.of("helm"), 0, "ok", "");
      }

      /**
       * Captures the isolated deployment image verification after Helm reports the failed upgrade.
       */
      @Override
      public CommandResult verifyDeploymentUpdate(
          String namespace, String deploymentName, String containerName, String newImage) {
        verifiedDeployments.add(namespace + "/" + deploymentName + "/" + containerName + "/" + newImage);
        return new CommandResult(List.of("kubectl"), 0, newImage, "");
      }
    };

    CommandResult result = capturingService.verifyIsolatedPepHelmRollbackOnFailure(
        "zeta-local", "nginx", stableImage, "nonexistent-rollout-test", "10m", "30s", 660);

    assertEquals(1, result.exitCode());
    assertEquals(List.of(660, 660, 660), capturedTimeouts);
    assertEquals(3, capturedHelmArgs.size());
    final List<String> installArgs = capturedHelmArgs.getFirst();
    final List<String> rollbackArgs = capturedHelmArgs.get(1);
    final List<String> cleanupArgs = capturedHelmArgs.get(2);
    final String releaseName = installArgs.get(2);
    assertTrue(releaseName.startsWith("zeta-rollback-proof-"));
    assertEquals(List.of("upgrade", "--install", releaseName, "."), installArgs.subList(0, 4));
    assertTrue(installArgs.contains("image=" + stableImage));
    assertEquals("10m", installArgs.get(installArgs.indexOf("--timeout") + 1));
    assertEquals(List.of("upgrade", releaseName, "."), rollbackArgs.subList(0, 3));
    assertTrue(rollbackArgs.contains("--reuse-values"));
    assertTrue(rollbackArgs.contains("--rollback-on-failure"));
    assertTrue(rollbackArgs.contains("--wait"));
    assertEquals("30s", rollbackArgs.get(rollbackArgs.indexOf("--timeout") + 1));
    assertTrue(rollbackArgs.contains("image=registry.example.test:443/zeta/zeta-guard/ngx_pep:nonexistent-rollout-test"));
    assertFalse(rollbackArgs.contains("--install"));
    assertFalse(rollbackArgs.contains("-f"));
    assertFalse(rollbackArgs.contains("--set-file"));
    assertFalse(rollbackArgs.contains("--force-conflicts"));
    assertFalse(rollbackArgs.stream().anyMatch(arg -> arg.startsWith("global.dns.tigerStaticClusterIP=")));
    assertEquals(List.of("uninstall", releaseName, "-n", "zeta-local", "--wait", "--timeout", "60s"), cleanupArgs);
    assertEquals(1, verifiedDeployments.size());
    assertEquals("zeta-local/" + releaseName + "/nginx/" + stableImage, verifiedDeployments.getFirst());
    assertFalse(Files.exists(Path.of(capturedWorkDirs.getFirst())));
  }

  /**
   * Verifies that the isolated rollback proof fails when the faulty upgrade unexpectedly succeeds.
   */
  @Test
  public void verifyIsolatedPepHelmRollbackOnFailureRejectsSuccessfulFaultyUpgrade() {
    String stableImage = "registry.example.test:443/zeta/zeta-guard/ngx_pep:main";
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        return new CommandResult(List.of("helm"), 0, "upgrade succeeded", "");
      }
    };

    AssertionError error = Assert.assertThrows(AssertionError.class,
        () -> capturingService.verifyIsolatedPepHelmRollbackOnFailure(
            "zeta-local", "nginx", stableImage, "existing-tag", "10m", "10m", 660));

    assertTrue(error.getMessage().contains("unexpectedly succeeded"));
    assertTrue(error.getMessage().contains("registry.example.test:443/zeta/zeta-guard/ngx_pep:existing-tag"));
  }

  /**
   * Verifies that the isolated rollback proof rejects image tag values that are not plain tags.
   */
  @Test
  public void verifyIsolatedPepHelmRollbackOnFailureRejectsInvalidFaultyImageTag() {
    String stableImage = "registry.example.test:443/zeta/zeta-guard/ngx_pep:main";
    ZetaDeploymentModificationService service = new ZetaDeploymentModificationService(240, 120);

    AssertionError digestError = Assert.assertThrows(AssertionError.class,
        () -> service.verifyIsolatedPepHelmRollbackOnFailure(
            "zeta-local", "nginx", stableImage, "sha256:abc123", "10m", "30s", 660));
    AssertionError pathError = Assert.assertThrows(AssertionError.class,
        () -> service.verifyIsolatedPepHelmRollbackOnFailure(
            "zeta-local", "nginx", stableImage, "repo/tag", "10m", "30s", 660));

    assertTrue(digestError.getMessage().contains("must not contain ':' or '/'"));
    assertTrue(pathError.getMessage().contains("must not contain ':' or '/'"));
  }

  /**
   * Verifies that configured Helm arguments keep quoted values with spaces as single arguments.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureParsesQuotedHelmArgs() {
    List<String> capturedArgs = new ArrayList<>();
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        capturedArgs.addAll(args);
        return new CommandResult(List.of("helm"), 1, "upgrade failed", "rolled back");
      }

      /**
       * Returns the current tiger-proxy Service IP used to preserve immutable Service fields.
       */
      @Override
      public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
        return new CommandResult(List.of("kubectl"), 0, "10.96.143.74", "");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore",
            "HELM_ARGS", "--set \"global.annotations.key=value with spaces\" --debug --set 'other=value too'"
        )
    );

    CommandResult result = capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m");

    assertEquals(1, result.exitCode());
    int annotationValueIndex = capturedArgs.indexOf("global.annotations.key=value with spaces");
    int debugIndex = capturedArgs.indexOf("--debug");
    int otherSetIndex = capturedArgs.lastIndexOf("--set");
    int otherValueIndex = capturedArgs.indexOf("other=value too");
    assertTrue(annotationValueIndex > 0 && "--set".equals(capturedArgs.get(annotationValueIndex - 1)));
    assertEquals(annotationValueIndex + 1, debugIndex);
    assertEquals(otherSetIndex + 1, otherValueIndex);
    int clusterIpIndex = capturedArgs.indexOf("global.dns.tigerStaticClusterIP=10.96.143.74");
    assertTrue(clusterIpIndex > otherValueIndex);
    assertEquals("--set-string", capturedArgs.get(clusterIpIndex - 1));
  }

  /**
   * Verifies that direct Helm rollback-on-failure deployments continue without immutable Service overrides
   * when the target Service is not present yet.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureSkipsMissingImmutableServiceIp() {
    List<String> capturedArgs = new ArrayList<>();
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      /**
       * Captures the Helm command built after the missing Service lookup.
       */
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        capturedArgs.addAll(args);
        return new CommandResult(List.of("helm"), 1, "upgrade failed", "rolled back");
      }

      /**
       * Simulates a fresh namespace where tiger-proxy has not been created yet.
       */
      @Override
      public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
        return new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore"
        )
    );

    CommandResult result = capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m");

    assertEquals(1, result.exitCode());
    assertFalse(capturedArgs.contains("--set-string"));
    assertFalse(capturedArgs.stream().anyMatch(arg -> arg.startsWith("global.dns.tigerStaticClusterIP=")));
  }

  /**
   * Verifies that achelos direct Helm deployments preserve the tiger-proxy and cert-validation mock Service IPs.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailurePreservesAchelosServiceIps() {
    List<String> capturedArgs = new ArrayList<>();
    List<List<String>> capturedKubectlArgs = new ArrayList<>();
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      /**
       * Captures the Helm command built after both live Service lookups.
       */
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        capturedArgs.addAll(args);
        return new CommandResult(List.of("helm"), 1, "upgrade failed", "rolled back");
      }

      /**
       * Returns different Service IPs so each Helm override can be traced to its source Service.
       */
      @Override
      public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
        capturedKubectlArgs.add(List.of(arguments));
        String serviceName = arguments[5];
        if ("tiger-proxy".equals(serviceName)) {
          return new CommandResult(List.of("kubectl"), 0, "10.96.143.74", "");
        }
        if ("zeta-cert-validation-mock".equals(serviceName)) {
          return new CommandResult(List.of("kubectl"), 0, "10.96.143.75", "");
        }
        return new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "achelos",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore"
        )
    );

    CommandResult result = capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m");

    assertEquals(1, result.exitCode());
    assertEquals("tiger-proxy", capturedKubectlArgs.getFirst().get(5));
    assertEquals("zeta-cert-validation-mock", capturedKubectlArgs.get(1).get(5));
    assertTrue(capturedArgs.contains("global.dns.tigerStaticClusterIP=10.96.143.74"));
    assertTrue(capturedArgs.contains("zeta-cert-validation-mock.service.clusterIP=10.96.143.75"));
  }

  /**
   * Verifies that stage-specific immutable Service IP overrides reject a missing target stage explicitly.
   */
  @Test
  public void immutableServiceClusterIpOverrideRejectsNullCurrentTargetStageForStageSpecificOverride() {
    var override = new ZetaDeploymentModificationService.ImmutableServiceClusterIpOverride(
        "zeta-cert-validation-mock", "zeta-cert-validation-mock.service.clusterIP", "achelos");

    NullPointerException exception = Assert.assertThrows(NullPointerException.class, () -> override.appliesTo(null));

    assertEquals("currentTargetStage must not be null", exception.getMessage());
  }

  /**
   * Verifies that Helm deployment requests reject a missing target stage before command creation.
   */
  @Test
  public void helmDeploymentRequestRejectsNullTargetStage() {
    NullPointerException exception = Assert.assertThrows(NullPointerException.class, () -> new HelmDeploymentRequest(
        "/helm",
        null,
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore"
        ),
        "zeta-existing-release",
        "private/values.local.yaml"
    ));

    assertEquals("targetStage must not be null", exception.getMessage());
  }

  /**
   * Verifies that direct Helm deployment requests reject a missing release name before command creation.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureRejectsNullReleaseName() {
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        throw new AssertionError("Helm command must not be executed for invalid request metadata");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore"
        ),
        null,
        "private/values.local.yaml"
    );

    NullPointerException exception = Assert.assertThrows(NullPointerException.class,
        () -> capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m"));

    assertEquals("helmReleaseName must not be null", exception.getMessage());
  }

  /**
   * Verifies that direct Helm deployment requests reject a missing values file before command creation.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureRejectsNullValuesFile() {
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        throw new AssertionError("Helm command must not be executed for invalid request metadata");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore"
        ),
        "zeta-existing-release",
        null
    );

    NullPointerException exception = Assert.assertThrows(NullPointerException.class,
        () -> capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m"));

    assertEquals("helmValuesFile must not be null", exception.getMessage());
  }

  /**
   * Verifies that empty quoted configured Helm arguments fail locally before executing Helm.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureRejectsEmptyQuotedHelmArg() {
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        throw new AssertionError("Helm command must not be executed for malformed HELM_ARGS");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore",
            "HELM_ARGS", "--set \"\""
        )
    );

    IllegalArgumentException exception = Assert.assertThrows(IllegalArgumentException.class,
        () -> capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m"));

    assertEquals("Configured Helm arguments contain an empty quoted value.", exception.getMessage());
  }

  /**
   * Verifies that backslashes inside single quoted configured Helm arguments remain literal.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureKeepsBackslashInsideSingleQuotedHelmArgs() {
    List<String> capturedArgs = new ArrayList<>();
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        capturedArgs.addAll(args);
        return new CommandResult(List.of("helm"), 1, "upgrade failed", "rolled back");
      }

      /**
       * Returns the current tiger-proxy Service IP used to preserve immutable Service fields.
       */
      @Override
      public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
        return new CommandResult(List.of("kubectl"), 0, "10.96.143.74", "");
      }
    };

    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore",
            "HELM_ARGS", "--set 'global.annotations.key=value\\ntest'"
        )
    );

    CommandResult result = capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m");

    assertEquals(1, result.exitCode());
    assertTrue(capturedArgs.contains("global.annotations.key=value\\ntest"));
    assertTrue(capturedArgs.contains("global.dns.tigerStaticClusterIP=10.96.143.74"));
  }

  /**
   * Verifies that malformed configured Helm arguments fail locally before executing Helm.
   */
  @Test
  public void deployZetaGuardWithHelmRollbackOnFailureRejectsUnterminatedQuotedHelmArg() {
    ZetaDeploymentModificationService capturingService = new ZetaDeploymentModificationService(240, 120) {
      @Override
      public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
          int commandTimeoutSeconds)
          throws AssertionError {
        throw new AssertionError("Helm command must not be executed for malformed HELM_ARGS");
      }
    };
    var request = new HelmDeploymentRequest(
        "/helm",
        "local",
        List.of("zeta-guard.pepproxy.image.tag=invalid"),
        Map.of(
            "SMB_KEYSTORE_PW_FILE", "/secret/password",
            "SMB_KEYSTORE_FILE_B64", "/secret/keystore",
            "HELM_ARGS", "--set \"global.annotations.key=value with spaces"
        )
    );

    IllegalArgumentException exception = Assert.assertThrows(IllegalArgumentException.class,
        () -> capturingService.deployZetaGuardWithHelmRollbackOnFailure(request, "zeta-local", "2m"));

    assertEquals("Configured Helm arguments contain an unterminated quoted value.", exception.getMessage());
  }

  @Test
  @Ignore
  public void togglePoppVerification() throws IOException, InterruptedException, TimeoutException {

    ZetaPoppTokenToggleRequest request = new ZetaPoppTokenToggleRequest(
        "pep_require_popp\\s+(on|off)\\s*;",
        "pep_require_popp      on;",
        "pep_require_popp      off;"
    );

    // modify existing key
    KubectlPatchCommandResult disableResult = service.setPoppVerification(details, request, "/pep/", false);
    assertEquals(0, disableResult.commandResult().exitCode());

    KubectlPatchCommandResult enableResult = service.setPoppVerification(details, request, "/pep/", true);
    assertEquals(0, enableResult.commandResult().exitCode());

    // modify non-existing key
    disableResult = service.setPoppVerification(details, request, "/", false);
    assertEquals(0, disableResult.commandResult().exitCode());

    enableResult = service.setPoppVerification(details, request, "/", true);
    assertEquals(0, enableResult.commandResult().exitCode());
  }

  /**
   * Verifies that PoPP token validity modification patches the nginx ConfigMap and restarts the PEP pod.
   *
   * @throws IOException if temporary patch file handling fails unexpectedly
   * @throws InterruptedException if pod restart waiting is interrupted
   * @throws TimeoutException if pod readiness waiting exceeds the timeout
   */
  @Test
  public void setPoppTokenValidityReplacesExistingDirective() throws IOException, InterruptedException, TimeoutException {
    String nginxConfig = """
        pep_pdp_issuer https://zeta-kind.local/auth/realms/zeta-guard;
        pep_popp_issuer http://popp-statics;
        pep_popp_validity "quarter";
        location /pep/ {
            pep_require_popp      on;
        }
        """;
    String originalConfigMapYaml = """
        apiVersion: v1
        metadata:
          name: pep-test-nginx-conf
        data:
          nginx.conf: |
            pep_popp_validity "quarter";
        """;
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, nginxConfig, ""),
        new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)"),
        new CommandResult(List.of("kubectl"), 0, originalConfigMapYaml, ""),
        new CommandResult(List.of("kubectl"), 0, "configmap/pep-test-nginx-conf-tiger-original-backup created", ""),
        new CommandResult(List.of("kubectl"), 0, "configmap/pep-test-nginx-conf patched", ""),
        new CommandResult(List.of("kubectl"), 0, "pep-deployment-old 1/1 Running", ""),
        new CommandResult(List.of("kubectl"), 0, "pod/pep-deployment-old deleted", ""),
        new CommandResult(List.of("kubectl"), 0, "pep-deployment-new 1/1 Running", ""),
        new CommandResult(List.of("kubectl"), 0, "true", "")
    );
    var request = new ZetaPoppTokenValidityRequest(
        "pep_popp_validity\\s+\"[^\"]+\"\\s*;",
        "pep_popp_issuer\\s+[^;]+;",
        "pep_popp_validity \"%s\";");

    KubectlPatchCommandResult result = fakeService.setPoppTokenValidity(details, request, "300s");

    assertEquals(0, result.commandResult().exitCode());
    assertEquals(9, fakeService.commands.size());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "configmap",
        "pep-test-nginx-conf", "-o=jsonpath='{.data.nginx\\.conf}'"), fakeService.commands.getFirst());
    assertTrue(fakeService.patchFileContents.stream()
        .anyMatch(content -> content.contains("pep_popp_validity \\\"300s\\\";")));
  }

  /**
   * Verifies that invalid PoPP token validity values are rejected before any kubectl call is made.
   */
  @Test
  public void setPoppTokenValidityRejectsInvalidValue() {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService();
    var request = new ZetaPoppTokenValidityRequest(
        "pep_popp_validity\\s+\"[^\"]+\"\\s*;",
        "pep_popp_issuer\\s+[^;]+;",
        "pep_popp_validity \"%s\";");

    Assert.assertThrows(IllegalArgumentException.class,
        () -> fakeService.setPoppTokenValidity(details, request, "300; pep off"));
    assertTrue(fakeService.commands.isEmpty());
  }

  /**
   * Verifies that client-data forwarding can be enabled by adding the nginx directive to the route section.
   *
   * @throws IOException if temporary patch file handling fails unexpectedly
   * @throws InterruptedException if pod restart waiting is interrupted
   * @throws TimeoutException if pod readiness waiting exceeds the timeout
   */
  @Test
  public void enableClientDataForwardingAddsMissingDirective() throws IOException, InterruptedException, TimeoutException {
    String nginxConfig = """
        location /pep/ {
            proxy_pass http://fachdienst/;
            proxy_ssl_verify off;
        }
        """;
    FakeZetaDeploymentModificationService fakeService = newPepNginxPatchFake(nginxConfig);
    var request = new ZetaClientDataForwardingToggleRequest(
        "pep_forward_client_data\\s+(on|off)\\s*;",
        "pep_forward_client_data on;",
        "pep_forward_client_data off;");

    KubectlPatchCommandResult result = fakeService.setClientDataForwarding(details, request, "/pep/", true);

    assertEquals(0, result.commandResult().exitCode());
    assertEquals(9, fakeService.commands.size());
    assertTrue(fakeService.patchFileContents.stream()
        .anyMatch(content -> content.contains("pep_forward_client_data on;")));
  }

  /**
   * Verifies that client-data forwarding can be disabled by replacing the nginx directive in the route section.
   *
   * @throws IOException if temporary patch file handling fails unexpectedly
   * @throws InterruptedException if pod restart waiting is interrupted
   * @throws TimeoutException if pod readiness waiting exceeds the timeout
   */
  @Test
  public void disableClientDataForwardingReplacesExistingDirective()
      throws IOException, InterruptedException, TimeoutException {
    String nginxConfig = """
        location /pep/ {
            proxy_pass http://fachdienst/;
            pep_forward_client_data on;
        }
        """;
    FakeZetaDeploymentModificationService fakeService = newPepNginxPatchFake(nginxConfig);
    var request = new ZetaClientDataForwardingToggleRequest(
        "pep_forward_client_data\\s+(on|off)\\s*;",
        "pep_forward_client_data on;",
        "pep_forward_client_data off;");

    KubectlPatchCommandResult result = fakeService.setClientDataForwarding(details, request, "/pep/", false);

    assertEquals(0, result.commandResult().exitCode());
    assertEquals(9, fakeService.commands.size());
    assertTrue(fakeService.patchFileContents.stream()
        .anyMatch(content -> content.contains("pep_forward_client_data off;")));
  }

  /**
   * Verifies that PoPP verification can create a concrete PEP route from the generic route before setting the directive.
   *
   * @throws IOException if temporary patch file handling fails unexpectedly
   * @throws InterruptedException if pod restart waiting is interrupted
   * @throws TimeoutException if pod readiness waiting exceeds the timeout
   */
  @Test
  public void enablePoppVerificationCreatesMissingPepRouteLocation()
      throws IOException, InterruptedException, TimeoutException {
    String nginxConfig = """
        server {
            location /pep/ {
                include proxy_headers.conf;
                proxy_pass https://tiger-proxy:80/testfachdienst/;
                proxy_ssl_verify off;
            }
        }
        """;
    FakeZetaDeploymentModificationService fakeService = newPepNginxPatchFake(nginxConfig);
    var request = new ZetaPoppTokenToggleRequest(
        "pep_require_popp\\s+(on|off)\\s*;",
        "pep_require_popp      on;",
        "pep_require_popp      off;");

    KubectlPatchCommandResult result = fakeService.setPoppVerification(
        details, request, "/pep/achelos_testfachdienst/hellozeta", true);

    assertEquals(0, result.commandResult().exitCode());
    assertEquals(9, fakeService.commands.size());
    assertTrue(fakeService.patchFileContents.stream()
        .anyMatch(content -> content.contains("location \\/pep\\/achelos_testfachdienst\\/hellozeta")
            && content.contains("proxy_pass https:\\/\\/tiger-proxy:80\\/testfachdienst\\/achelos_testfachdienst\\/hellozeta;")
            && content.contains("pep_require_popp      on;")));
  }

  /**
   * Verifies that required scopes can create a concrete PEP route from the generic route.
   *
   * @throws IOException if temporary patch file handling fails unexpectedly
   * @throws InterruptedException if pod restart waiting is interrupted
   * @throws TimeoutException if pod readiness waiting exceeds the timeout
   */
  @Test
  public void setRequiredScopesCreatesMissingPepRouteLocation()
      throws IOException, InterruptedException, TimeoutException {
    String nginxConfig = """
        server {
            location /pep/ {
                include proxy_headers.conf;
                proxy_pass https://tiger-proxy:80/testfachdienst/;
                proxy_ssl_verify off;
            }
        }
        """;
    FakeZetaDeploymentModificationService fakeService = newPepNginxPatchFake(nginxConfig);
    var request = new ZetaRequiredScopesRequest(
        "pep_require_scope\\s+\"[^\"]+\"\\s*;",
        "pep_require_scope \"%s\";");

    KubectlPatchCommandResult result = fakeService.setRequiredScopes(
        details, request, "/pep/achelos_testfachdienst/hellozeta", "email zero:audience");

    assertEquals(0, result.commandResult().exitCode());
    assertEquals(9, fakeService.commands.size());
    assertTrue(fakeService.patchFileContents.stream()
        .anyMatch(content -> content.contains("location \\/pep\\/achelos_testfachdienst\\/hellozeta")
            && content.contains("pep_require_scope \\\"email zero:audience\\\";")));
  }

  /**
   * Verifies that required-scope values are restricted to safe nginx string content.
   */
  @Test
  public void setRequiredScopesRejectsUnsafeValue() {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService();
    var request = new ZetaRequiredScopesRequest(
        "pep_require_scope\\s+\"[^\"]+\"\\s*;",
        "pep_require_scope \"%s\";");

    for (String unsafeValue : List.of("email; pep_require_popp off", "email$variable", "email\\")) {
      Assert.assertThrows(IllegalArgumentException.class,
          () -> fakeService.setRequiredScopes(details, request, "/pep/", unsafeValue));
    }
    assertTrue(fakeService.commands.isEmpty());
  }

  @Test
  @Ignore
  public void configMapBackup() throws IOException {
    final String namespace = "zeta-local";
    final String cmName = "pep-test-nginx-conf";

    CommandResult backupResult = service.createConfigMapBackup(namespace, cmName);
    assertEquals(0, backupResult.exitCode());

    CommandResult restoreResult = service.restoreConfigMapBackup(namespace, cmName);
    assertEquals(0, restoreResult.exitCode());

    CommandResult deleteResult = service.deleteConfigMapBackup(namespace, cmName);
    assertEquals(0, deleteResult.exitCode());
  }

  /**
   * Verifies that ConfigMap restoration uses a sanitized server-side manifest and returns field
   * ownership to Helm without replacing the resource.
   *
   * @throws IOException if temporary restore manifest handling fails unexpectedly
   */
  @Test
  public void restoreConfigMapBackupUsesHelmServerSideApply() throws IOException {
    String backupConfigMapJson = """
        {
          "apiVersion": "v1",
          "kind": "ConfigMap",
          "metadata": {
            "annotations": {
              "kubectl.kubernetes.io/last-applied-configuration": "stale-client-side-state",
              "meta.helm.sh/release-name": "zeta-testenv"
            },
            "creationTimestamp": "2026-07-28T00:15:15Z",
            "labels": {
              "app.kubernetes.io/managed-by": "Helm"
            },
            "name": "pep-test-nginx-conf-tiger-original-backup",
            "namespace": "zeta-local",
            "resourceVersion": "74644393",
            "uid": "6c24063d-ca7d-42fd-92d7-eca69948d8bd"
          },
          "data": {
            "nginx.conf": "pep_popp_validity \\"quarter\\";"
          }
        }
        """;
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, backupConfigMapJson, ""),
        new CommandResult(List.of("kubectl"), 0, "configmap/pep-test-nginx-conf serverside-applied", "")
    );

    CommandResult restoreResult = fakeService.restoreConfigMapBackup("zeta-local", "pep-test-nginx-conf");

    assertEquals(0, restoreResult.exitCode());
    assertEquals(2, fakeService.commands.size());
    assertEquals(Arrays.asList(
        "-n", "zeta-local", "apply", "--server-side", "--force-conflicts", "--field-manager=helm",
        "-f", fakeService.commands.get(1).getLast()), fakeService.commands.get(1));
    assertFalse(fakeService.commands.get(1).contains("--force"));
    assertEquals(1, fakeService.patchFileContents.size());
    String restoreManifest = fakeService.patchFileContents.getFirst();
    assertTrue(restoreManifest.contains("\"name\":\"pep-test-nginx-conf\""));
    assertTrue(restoreManifest.contains("\"namespace\":\"zeta-local\""));
    assertTrue(restoreManifest.contains("pep_popp_validity"));
    assertTrue(restoreManifest.contains("meta.helm.sh/release-name"));
    assertFalse(restoreManifest.contains("last-applied-configuration"));
    assertFalse(restoreManifest.contains("resourceVersion"));
    assertFalse(restoreManifest.contains("creationTimestamp"));
    assertFalse(restoreManifest.contains("\"uid\""));
  }

  /**
   * Verifies that restoring a missing backup returns a no-op result without surfacing stderr noise.
   *
   * @throws IOException if temporary file handling inside the restore flow fails unexpectedly
   */
  @Test
  public void restoreConfigMapBackupReturnsNoOpWhenBackupIsMissing() throws IOException {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)")
    );

    CommandResult restoreResult = fakeService.restoreConfigMapBackup("zeta-local", "pep-well-known");

    assertEquals(0, restoreResult.exitCode());
    assertEquals("Not found", restoreResult.stdout());
    assertTrue(restoreResult.stderr().isBlank());
    assertEquals(1, fakeService.commands.size());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "configmap",
        "pep-well-known-tiger-original-backup", "-o", "json"), fakeService.commands.getFirst());
    assertFalse(fakeService.logStderrFlags.getFirst());
  }

  /**
   * Verifies that backup creation is skipped when a backup ConfigMap already exists.
   *
   * @throws IOException if temporary file handling inside the backup flow fails unexpectedly
   */
  @Test
  public void createConfigMapBackupSkipsCreationWhenBackupAlreadyExists() throws IOException {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, "apiVersion: v1", "")
    );

    CommandResult backupResult = fakeService.createConfigMapBackup("zeta-local", "pep-test-nginx-conf");

    assertEquals(0, backupResult.exitCode());
    assertEquals("Unchanged", backupResult.stdout());
    assertTrue(backupResult.stderr().isBlank());
    assertEquals(1, fakeService.commands.size());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "configmap",
        "pep-test-nginx-conf-tiger-original-backup", "-o", "json"), fakeService.commands.getFirst());
    assertFalse(fakeService.logStderrFlags.getFirst());
  }

  @Test
  public void getContainerImageReferenceForDeploymentReturnsFullImage() {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, "NAME READY STATUS\npep-deployment-abc 1/1 Running", ""),
        new CommandResult(List.of("kubectl"), 0,
            "registry.example.invalid:443/example/ngx_pep:0.3.0", "")
    );

    CommandResult imageResult = fakeService.getContainerImageReferenceForDeployment("zeta-local", "pep-deployment", "nginx");

    assertEquals(0, imageResult.exitCode());
    assertEquals("registry.example.invalid:443/example/ngx_pep:0.3.0", imageResult.stdout());
    assertTrue(imageResult.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods"), fakeService.commands.getFirst());
    assertEquals(Arrays.asList("get", "pod", "pep-deployment-abc", "-n", "zeta-local",
        "-o", "jsonpath='{.spec.containers[?(@.name==\"nginx\")].image}'"), fakeService.commands.get(1));
  }

  @Test
  public void getSingleReadyPodNameForDeploymentReturnsMatchingReadyPod() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "status": { "containerStatuses": [ { "ready": false } ] }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "status": { "containerStatuses": [ { "ready": true } ] }
            }
          ]
        }
        """;
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, "")
    );

    String podName = fakeService.getSingleReadyPodNameForDeployment("zeta-local", "pep-deployment");

    assertEquals("pep-deployment-new", podName);
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(1, fakeService.commands.size());
  }

  @Test
  public void verifyPodImagePullOccurredReturnsSuccessWhenMatchingEventExists() {
    String image = "registry.example.org/zeta/ngx_pep:1.2.3";
    String eventsJson = """
        {
          "items": [
            {
              "reason": "Scheduled",
              "message": "Successfully assigned namespace/pep-deployment-new to node-a"
            },
            {
              "reason": "Pulling",
              "message": "Pulling image \\"%s\\""
            },
            {
              "reason": "Pulled",
              "message": "Successfully pulled image \\"%s\\" in 5.432s"
            }
          ]
        }
        """.formatted(image, image);
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, image, ""),
        new CommandResult(List.of("kubectl"), 0, eventsJson, "")
    );

    CommandResult result = fakeService.verifyPodImagePullOccurred("zeta-local", "pep-deployment-new", "nginx");

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Pulling image"));
    assertTrue(result.stdout().contains("Successfully pulled image"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("get", "pod", "pep-deployment-new", "-n", "zeta-local",
        "-o", "jsonpath='{.spec.containers[?(@.name==\"nginx\")].image}'"), fakeService.commands.getFirst());
    assertEquals(Arrays.asList("get", "events", "-n", "zeta-local",
        "--field-selector", "involvedObject.kind=Pod,involvedObject.name=pep-deployment-new",
        "-o", "json"), fakeService.commands.get(1));
  }

  @Test
  public void verifyPodImagePullOccurredFailsWhenNoMatchingEventExists() {
    String image = "registry.example.org/zeta/ngx_pep:1.2.3";
    String eventsJson = """
        {
          "items": [
            {
              "reason": "Pulling",
              "message": "Pulling image \\"registry.example.org/zeta/other:9.9.9\\""
            },
            {
              "reason": "Started",
              "message": "Started container nginx"
            }
          ]
        }
        """;
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, image, ""),
        new CommandResult(List.of("kubectl"), 0, eventsJson, "")
    );

    CommandResult result = fakeService.verifyPodImagePullOccurred("zeta-local", "pep-deployment-new", "nginx");

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("No image pull event found"));
  }

  @Test
  public void verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSecondsSucceeds() {
    String failedImage = "registry.example.org/zeta/ngx_pep:6.6.6";
    String failedPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:00Z" } } } ]
              }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "%s" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ImagePullBackOff" } } } ]
              }
            }
          ]
        }
        """.formatted(failedImage);
    String recoveredPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:05Z" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, failedPodsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:1.2.3", ""),
        new CommandResult(List.of("kubectl"), 1, "", "deployment \"pep-deployment\" exceeded its progress deadline"),
        new CommandResult(List.of("kubectl"), 0, recoveredPodsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:1.2.3", ""),
        new CommandResult(List.of("kubectl"), 0, "deployment \"pep-deployment\" successfully rolled out", "")
    );

    CommandResult result = fakeService.verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        failedImage,
        "registry.example.org/zeta/ngx_pep:1.2.3",
        1
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Failed update remained inactive and automatic rollback verified"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(Arrays.asList("get", "deployment", "pep-deployment", "-n", "zeta-local",
        "-o", "jsonpath='{.spec.template.spec.containers[?(@.name==\"nginx\")].image}'"), fakeService.commands.get(1));
    assertEquals(Arrays.asList("rollout", "status", "deployment/pep-deployment", "-n", "zeta-local", "--timeout=2s"),
        fakeService.commands.get(2));
  }

  @Test
  public void verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSecondsFailsWhileFailedPodRemainsPending() {
    String failedImage = "registry.example.org/zeta/ngx_pep:6.6.6";
    String failedPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:00Z" } } } ]
              }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "%s" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ImagePullBackOff" } } } ]
              }
            }
          ]
        }
        """.formatted(failedImage);

    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, failedPodsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:1.2.3", ""),
        new CommandResult(List.of("kubectl"), 0, "deployment \"pep-deployment\" successfully rolled out", "")
    );

    CommandResult result = fakeService.verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        failedImage,
        "registry.example.org/zeta/ngx_pep:1.2.3",
        0
    );

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("pep-deployment"));
    assertTrue(result.stderr().contains("did not return automatically to stable image")
        || result.stderr().contains("No failed rollout evidence observed"));
  }

  @Test
  public void verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSecondsFailsWhenBadImageBecomesReady() {
    String failedImage = "registry.example.org/zeta/ngx_pep:6.6.6";
    String activeFailedPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "%s" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:03Z" } } } ]
              }
            }
          ]
        }
        """.formatted(failedImage);

    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, activeFailedPodsJson, "")
    );

    CommandResult result = fakeService.verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        failedImage,
        "registry.example.org/zeta/ngx_pep:1.2.3",
        1
    );

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("unexpectedly became active"));
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(1, fakeService.commands.size());
  }

  @Test
  public void cleanupFailedRolloutPodsScalesReplicaSetAndDeletesUnexpectedImagePod() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": {
                "name": "pep-deployment-799bb999bf-mnmmt",
                "ownerReferences": [ { "kind": "ReplicaSet", "name": "pep-deployment-799bb999bf" } ]
              },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-12T07:00:00Z" } } } ]
              }
            },
            {
              "metadata": {
                "name": "pep-deployment-5d9dc7b898-dr6d5",
                "ownerReferences": [ { "kind": "ReplicaSet", "name": "pep-deployment-5d9dc7b898" } ]
              },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:nonexistent-rollout-test-20260311" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ImagePullBackOff" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "deployment.apps/pep-deployment patched", ""),
        new CommandResult(List.of("kubectl"), 0, "replicaset.apps/pep-deployment-5d9dc7b898 scaled", ""),
        new CommandResult(List.of("kubectl"), 0, "pod \"pep-deployment-5d9dc7b898-dr6d5\" deleted", "")
    );

    CommandResult result = fakeService.cleanupFailedRolloutPods(
        "zeta-local",
        "pep-deployment",
        "nginx",
        "registry.example.org/zeta/ngx_pep:1.2.3"
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("pep-deployment-5d9dc7b898-dr6d5"));
    assertTrue(result.stdout().contains("Restored deployment 'pep-deployment' to image 'registry.example.org/zeta/ngx_pep:1.2.3'"));
    assertTrue(result.stdout().contains("Scaled ReplicaSet 'pep-deployment-5d9dc7b898' to 0"));
    assertTrue(result.stdout().contains("Deleted pod 'pep-deployment-5d9dc7b898-dr6d5'"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals("patch", fakeService.commands.get(1).getFirst());
    assertEquals("deployment", fakeService.commands.get(1).get(1));
    assertEquals("pep-deployment", fakeService.commands.get(1).get(2));
    assertTrue(fakeService.commands.get(1).contains("--field-manager=helm"));
    assertEquals(Arrays.asList("scale", "rs", "pep-deployment-5d9dc7b898", "-n", "zeta-local", "--replicas=0"),
        fakeService.commands.get(2));
    assertEquals(Arrays.asList("-n", "zeta-local", "delete", "pod", "pep-deployment-5d9dc7b898-dr6d5",
        "--ignore-not-found=true"), fakeService.commands.get(3));
  }

  @Test
  public void cleanupFailedRolloutPodsReturnsNoOpWhenOnlyStableImagePodsRemain() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": {
                "name": "pep-deployment-799bb999bf-mnmmt",
                "ownerReferences": [ { "kind": "ReplicaSet", "name": "pep-deployment-799bb999bf" } ]
              },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-12T07:00:00Z" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, "")
    );

    CommandResult result = fakeService.cleanupFailedRolloutPods(
        "zeta-local",
        "pep-deployment",
        "nginx",
        "registry.example.org/zeta/ngx_pep:1.2.3"
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("No failed rollout pods found"));
    assertTrue(result.stderr().isBlank());
    assertEquals(1, fakeService.commands.size());
  }

  @Test
  public void verifyDeploymentShowsPodWithImageWithinSecondsSucceedsWhenExpectedImageAppears() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:00Z" } } } ]
              }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.4" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ContainerCreating" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, "")
    );

    CommandResult result = fakeService.verifyDeploymentShowsPodWithImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        "registry.example.org/zeta/ngx_pep:1.2.4",
        1
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Observed deployment 'pep-deployment' with image"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(1, fakeService.commands.size());
  }

  /**
   * Verifies that backup existence checks use the quiet lookup variant for both present and missing backups.
   */
  @Test
  public void hasConfigMapBackupUsesQuietLookupResult() {
    FakeZetaDeploymentModificationService presentBackupService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, "apiVersion: v1", "")
    );
    FakeZetaDeploymentModificationService missingBackupService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)")
    );

    assertTrue(presentBackupService.hasConfigMapBackup("zeta-local", "pep-test-nginx-conf"));
    assertFalse(presentBackupService.logStderrFlags.getFirst());

    assertFalse(missingBackupService.hasConfigMapBackup("zeta-local", "pep-well-known"));
    assertFalse(missingBackupService.logStderrFlags.getFirst());
  }

  /**
   * Verifies that temporary deployment patches retain Helm field ownership for subsequent server-side upgrades.
   *
   * @throws IOException if a temporary image patch file cannot be created
   */
  @Test
  public void deploymentPatchesUseHelmFieldManager() throws IOException {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, "deployment.apps/authserver patched", ""),
        new CommandResult(List.of("kubectl"), 0, "2", ""),
        new CommandResult(List.of("kubectl"), 0, "deployment \"authserver\" successfully rolled out", ""),
        new CommandResult(List.of("kubectl"), 0, "authserver-a\nauthserver-b", ""),
        new CommandResult(List.of("kubectl"), 0, "deployment.apps/pep-deployment patched", ""),
        new CommandResult(List.of("kubectl"), 0, "deployment.apps/pep-deployment patched", "")
    );

    CommandResult scaleResult = fakeService.scaleDeployment("zeta-local", "authserver", 2);
    CommandResult imageResult = fakeService.setDeploymentContainerImage(
        "zeta-local", "pep-deployment", "nginx", "registry.example.org/zeta/ngx_pep:main");
    CommandResult strategyResult = fakeService.setDeploymentStrategy(
        "zeta-local", "pep-deployment", "{\"type\":\"RollingUpdate\"}");

    assertEquals(0, scaleResult.exitCode());
    assertEquals(0, imageResult.exitCode());
    assertEquals(0, strategyResult.exitCode());
    assertTrue(fakeService.commands.get(0).contains("--field-manager=helm"));
    assertTrue(fakeService.commands.get(4).contains("--field-manager=helm"));
    assertTrue(fakeService.commands.get(5).contains("--field-manager=helm"));
  }

  /**
   * Verifies that RollingUpdate strategy values are rejected locally when maxSurge is missing.
   */
  @Test
  public void setDeploymentRollingUpdateStrategyRejectsNullMaxSurge() {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService();

    NullPointerException exception = Assert.assertThrows(NullPointerException.class,
        () -> fakeService.setDeploymentRollingUpdateStrategy("zeta-local", "pep-deployment", null, "0"));

    assertEquals("maxSurge must not be null", exception.getMessage());
    assertTrue(fakeService.commands.isEmpty());
  }

  /**
   * Verifies that RollingUpdate strategy values are rejected locally when maxUnavailable is missing.
   */
  @Test
  public void setDeploymentRollingUpdateStrategyRejectsNullMaxUnavailable() {
    FakeZetaDeploymentModificationService fakeService = new FakeZetaDeploymentModificationService();

    NullPointerException exception = Assert.assertThrows(NullPointerException.class,
        () -> fakeService.setDeploymentRollingUpdateStrategy("zeta-local", "pep-deployment", "0", null));

    assertEquals("maxUnavailable must not be null", exception.getMessage());
    assertTrue(fakeService.commands.isEmpty());
  }

  /**
   * Verifies deployment image update and rollback against a live cluster setup.
   *
   * @throws IOException if kubectl command execution requires temporary file handling that fails
   * @throws InterruptedException if waiting for deployment rollout is interrupted
   */
  @Ignore
  public void updateAndRollbackDeploymentImage() throws IOException, InterruptedException {
    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.namespace"));
    String deploymentName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.podName"));
    String containerName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.containerName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.containerName"));
    String versionDowngrade = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.image.versionDowngrade")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.image.versionDowngrade"));

    // get Image path of running Deployment
    CommandResult imagePathResult = service.getContainerImagePathForDeployment(namespace, deploymentName, containerName);
    assertEquals(0, imagePathResult.exitCode());

    String imagePath = imagePathResult.stdout() == null ? "" : imagePathResult.stdout().trim();
    assertFalse(imagePath.isBlank());

    // successful patch downgrade version
    String downgradeImage = imagePath + ":" + versionDowngrade;
    CommandResult setResult = service.setDeploymentContainerImage(namespace, deploymentName, containerName, downgradeImage);
    assertEquals(0, setResult.exitCode());

    // checking for positive pod deployment
    CommandResult verifyDowngradeResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, downgradeImage);
    assertEquals(0, verifyDowngradeResult.exitCode());

    Thread.sleep(30_000L);

    // successful rollback to last working deployment version
    CommandResult rollbackResult = service.rollbackDeployment(namespace, deploymentName);
    assertEquals(0, rollbackResult.exitCode());

    // positive check of deployed Image is equals standard image
    String versionUpdate = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.image.versionUpdate")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.image.versionUpdate"));
    String updatedImage = imagePath + ":" + versionUpdate;

    CommandResult verifyUpdatedResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, updatedImage);
    assertEquals(0, verifyUpdatedResult.exitCode());
  }

  /**
   * Verifies that a failing deployment image update reports the rollout error and can still be rolled back.
   *
   * @throws IOException if kubectl command execution requires temporary file handling that fails
   */
  @Test
  @Ignore
  public void failingUpdateAndVerifyDeploymentImage() throws IOException {
    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.namespace"));
    String deploymentName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.podName"));
    String containerName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.containerName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.containerName"));

    // get Image path of running Deployment
    CommandResult imagePathResult = service.getContainerImagePathForDeployment(namespace, deploymentName, containerName);
    assertEquals(0, imagePathResult.exitCode());

    String imagePath = imagePathResult.stdout() == null ? "" : imagePathResult.stdout().trim();
    assertFalse(imagePath.isBlank());

    // successful patch evil version
    String evilImage = imagePath + ":" + evilDeploymentVersion;
    CommandResult setResult = service.setDeploymentContainerImage(namespace, deploymentName,
        containerName, evilImage);
    assertEquals(0, setResult.exitCode());

    // timeout while checking for positive pod deployment
    CommandResult verifyDowngradeResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, evilImage);
    assertNotEquals(0, verifyDowngradeResult.exitCode());
    assertNotNull(verifyDowngradeResult.stderr());
    assertFalse(verifyDowngradeResult.stderr().isBlank());

    // successful rollback to last working deployment version
    CommandResult rollbackResult = service.rollbackDeployment(namespace, deploymentName);
    assertEquals(0, rollbackResult.exitCode());

    // positive check of deployed Image is equals standard image
    String versionUpdate = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.image.versionUpdate")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.image.versionUpdate"));
    String updatedImage = imagePath + ":" + versionUpdate;

    CommandResult verifyUpdatedResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, updatedImage);
    assertEquals(0, verifyUpdatedResult.exitCode());
  }

  /**
   * Creates a fake service with canned responses for a PEP nginx ConfigMap patch and restart.
   *
   * @param nginxConfig nginx ConfigMap value returned by the initial lookup
   * @return fake service that records kubectl calls and patch file contents
   */
  private static FakeZetaDeploymentModificationService newPepNginxPatchFake(String nginxConfig) {
    String originalConfigMapYaml = """
        apiVersion: v1
        metadata:
          name: pep-test-nginx-conf
        data:
          nginx.conf: |
            location /pep/ {
              proxy_pass http://fachdienst/;
            }
        """;
    return new FakeZetaDeploymentModificationService(
        new CommandResult(List.of("kubectl"), 0, nginxConfig, ""),
        new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)"),
        new CommandResult(List.of("kubectl"), 0, originalConfigMapYaml, ""),
        new CommandResult(List.of("kubectl"), 0, "configmap/pep-test-nginx-conf-tiger-original-backup created", ""),
        new CommandResult(List.of("kubectl"), 0, "configmap/pep-test-nginx-conf patched", ""),
        new CommandResult(List.of("kubectl"), 0, "pep-deployment-old 1/1 Running", ""),
        new CommandResult(List.of("kubectl"), 0, "pod/pep-deployment-old deleted", ""),
        new CommandResult(List.of("kubectl"), 0, "pep-deployment-new 1/1 Running", ""),
        new CommandResult(List.of("kubectl"), 0, "true", "")
    );
  }

  private static class FakeZetaDeploymentModificationService extends ZetaDeploymentModificationService {

    private final Queue<CommandResult> responses = new ArrayDeque<>();
    private final List<List<String>> commands = new ArrayList<>();
    private final List<Boolean> logStderrFlags = new ArrayList<>();
    private final List<String> patchFileContents = new ArrayList<>();

    /**
     * Creates a fake deployment configuration service with pre-seeded command results.
     *
     * @param responses command results to be returned in invocation order
     */
    FakeZetaDeploymentModificationService(CommandResult... responses) {
      super(60, 120);
      this.responses.addAll(List.of(responses));
    }

    /**
     * Records the requested kubectl arguments and returns the next configured fake response.
     *
     * @param arguments kubectl arguments
     * @param logStderr whether stderr logging would be enabled for the lookup
     * @return configured fake command result
     */
    @Override
    public CommandResult executeKubectlCommand(List<String> arguments, boolean logStderr) {
      return recordCommand(arguments, logStderr);
    }

    /**
     * Records kubectl commands issued through the boolean-first overload as well so tests never
     * fall through to a real cluster command.
     *
     * @param verbose whether stderr logging would be enabled for the lookup
     * @param arguments kubectl arguments
     * @return configured fake command result
     */
    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) {
      return recordCommand(arguments, verbose);
    }

    /**
     * Stores one intercepted command invocation and returns the next canned result.
     *
     * @param arguments kubectl arguments
     * @param logStderr whether stderr logging would be enabled for the lookup
     * @return configured fake command result
     */
    private CommandResult recordCommand(List<String> arguments, boolean logStderr) {
      commands.add(List.copyOf(arguments));
      logStderrFlags.add(logStderr);
      capturePatchFileContent(arguments);
      CommandResult next = responses.poll();
      if (next == null) {
        throw new AssertionError("No fake command result configured for arguments: " + arguments);
      }
      return next;
    }

    /**
     * Reads patch or apply manifest content before production code deletes the temporary file.
     *
     * @param arguments kubectl arguments that may contain a patch file reference
     */
    private void capturePatchFileContent(List<String> arguments) {
      var patchFileIndex = arguments.indexOf("--patch-file");
      if (patchFileIndex < 0) {
        patchFileIndex = arguments.indexOf("-f");
      }
      if (patchFileIndex < 0 || patchFileIndex + 1 >= arguments.size()) {
        return;
      }
      try {
        patchFileContents.add(Files.readString(Path.of(arguments.get(patchFileIndex + 1))));
      } catch (IOException e) {
        throw new AssertionError("Could not read generated patch file", e);
      }
    }

    /**
     * Skips the live kubectl availability check for unit tests that operate on canned command results.
     *
     * @param namespace ignored test namespace
     */
    @Override
    public void verifyRequirements(String namespace) {
      // unit tests provide canned kubectl responses and do not require a real cluster toolchain
    }

    /**
     * Avoids real waiting during polling-based unit tests.
     */
    @Override
    protected void sleepForPodStatusCheckInterval() {
      // avoid waiting in polling-based unit tests
    }
  }
}
