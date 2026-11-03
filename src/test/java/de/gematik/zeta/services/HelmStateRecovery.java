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

import de.gematik.zeta.services.model.HelmDeploymentRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Restores the captured installed Helm revision instead of redeploying local default values. */
public final class HelmStateRecovery {

  /** Release label persisted by Helm before applying an owned upgrade. */
  public static final String OWNERSHIP_LABEL = "zeta-testsuite-owner";

  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService service;
  private final PerformanceRoutingJournal journal;
  private final HelmDeploymentRequest request;
  private final String namespace;
  private JsonNode snapshot;

  /**
   * Creates a recovery-only reader without requiring local chart or ASL configuration.
   *
   * @param service command executor
   * @param namespace target namespace
   */
  public HelmStateRecovery(final ZetaDeploymentModificationService service, final String namespace) {
    this(service, new HelmDeploymentRequest(".", "", List.of(), Map.of(), "", ""), namespace);
  }

  /**
   * Creates a durable recovery session for a global ASL change.
   *
   * @param service command executor
   * @param request configured release and execution environment
   * @param namespace target namespace
   */
  public HelmStateRecovery(final ZetaDeploymentModificationService service,
      final HelmDeploymentRequest request, final String namespace) {
    this.service = service;
    this.request = request;
    this.namespace = namespace;
    journal = new PerformanceRoutingJournal(service, "testsuite-global-helm-state");
  }

  /** Recovers a terminated run before any new global deployment modification. */
  public void recoverAbandoned() {
    journal.recover(namespace, this::restore);
  }

  /**
   * Captures the deployed release revision and gives the upgrade a unique ownership marker.
   *
   * @return Helm ownership label and description for the owned upgrade
   */
  public String begin() {
    request.validateMakeReleaseName();
    var latest = latest(request.helmReleaseName());
    if (!"deployed".equals(latest.path("status").asText())) {
      throw new AssertionError("Global modification requires a deployed Helm release");
    }
    var marker = "zeta-testsuite-" + UUID.randomUUID();
    var saved = JSON.createObjectNode().put("version", 1).put("namespace", namespace)
        .put("release", request.helmReleaseName()).put("revision", latest.path("revision").asInt())
        .put("marker", marker);
    journal.begin(namespace, saved);
    snapshot = saved;
    return marker;
  }

  /** Restores only this run's upgrade and retains the backup on conflict or failure. */
  public void finish() {
    if (snapshot == null) {
      return;
    }
    try {
      journal.assertOwned(namespace);
      restore(snapshot);
      journal.complete(namespace);
      snapshot = null;
    } finally {
      journal.release();
    }
  }

  /**
   * Rolls back an owned upgrade or verifies an already completed recovery.
   *
   * @param saved durable release identity and original revision
   */
  private void restore(final JsonNode saved) {
    if (saved.path("version").asInt() != 1 || !namespace.equals(saved.path("namespace").asText())
        || saved.path("revision").asInt() < 1 || saved.path("release").asText().isBlank()
        || !saved.path("marker").asText().matches("zeta-testsuite-[a-zA-Z0-9-]+")) {
      throw new AssertionError("Invalid global Helm recovery snapshot");
    }
    var release = saved.path("release").asText();
    var revision = saved.path("revision").asInt();
    var latest = latest(release);
    if (latest.path("revision").asInt() == revision && "deployed".equals(latest.path("status").asText())) {
      return;
    }
    var upgrade = metadata(release, revision + 1);
    if (!saved.path("marker").asText().equals(upgrade.path("labels").path(OWNERSHIP_LABEL).asText())) {
      throw new AssertionError("Helm upgrade ownership cannot be verified; backup retained for " + release);
    }
    var currentRevision = latest.path("revision").asInt();
    // The chart's automatic rollback may create original + 2 before recovery starts.
    var expectedRevision = saved.path("rollbackRevision").asInt(revision + 2);
    if (currentRevision < revision + 1 || currentRevision > expectedRevision) {
      throw new AssertionError("Helm release changed outside this run; backup retained for " + release);
    }
    if (currentRevision > revision + 1) {
      verifyRollback(release, revision, latest);
      if ("deployed".equals(latest.path("status").asText())) {
        return;
      }
    }
    // Persist before invoking Helm: even a timeout can leave a newly stored revision.
    ((ObjectNode) saved).put("rollbackRevision", currentRevision + 1);
    journal.update(namespace, saved);
    helm(List.of("rollback", release, Integer.toString(revision), "-n", namespace,
        "--wait", "--timeout", "300s", "--history-max", "0"));
    latest = latest(release);
    if (latest.path("revision").asInt() != currentRevision + 1
        || !"deployed".equals(latest.path("status").asText())) {
      throw new AssertionError("Helm rollback did not complete; backup retained for " + release);
    }
    verifyRollback(release, revision, latest);
  }

  /**
   * Checks rollback identity and contents, including pending and failed revisions.
   *
   * @param release release name
   * @param revision original revision
   * @param latest rollback history entry
   */
  private void verifyRollback(final String release, final int revision, final JsonNode latest) {
    var status = latest.path("status").asText();
    var description = latest.path("description").asText();
    var rollbackDescription = ("Rollback to " + revision).equals(description);
    var failedDescription = "failed".equals(status)
        && (description.startsWith("Rollback \"" + release + "\" failed:")
        || description.startsWith("Release \"" + release + "\" failed:"));
    if (!List.of("deployed", "pending-rollback", "failed").contains(status)
        || !(rollbackDescription || failedDescription)
        || !metadata(release, revision).path("labels")
        .equals(metadata(release, latest.path("revision").asInt()).path("labels"))) {
      throw new AssertionError("Unexpected Helm rollback revision; backup retained for " + release);
    }
    for (var kind : List.of("manifest", "values")) {
      var original = helm(List.of("get", kind, release, "-n", namespace, "--revision", Integer.toString(revision)));
      var restored = helm(List.of("get", kind, release, "-n", namespace,
          "--revision", latest.path("revision").asText()));
      if (!original.equals(restored)) {
        throw new AssertionError("Helm rollback did not restore original " + kind + "; backup retained");
      }
    }
  }

  /**
   * Reads labels stored when the revision was created, independently of mutable descriptions.
   *
   * @param release release name
   * @param revision stored revision
   * @return Helm release metadata
   */
  private JsonNode metadata(final String release, final int revision) {
    return JSON.readTree(helm(List.of("get", "metadata", release, "-n", namespace,
        "--revision", Integer.toString(revision), "-o", "json")));
  }

  /**
   * Reads the latest stored Helm revision without inferring it from local values files.
   *
   * @param release release name
   * @return latest history entry
   */
  private JsonNode latest(final String release) {
    var history = JSON.readTree(helm(List.of("history", release, "-n", namespace, "-o", "json")));
    if (!history.isArray() || history.isEmpty()) {
      throw new AssertionError("Helm release history is empty: " + release);
    }
    return history.get(history.size() - 1);
  }

  /**
   * Runs a Helm command with sufficient time for restoration and propagates errors.
   *
   * @param arguments structured Helm arguments
   * @return command output
   */
  private String helm(final List<String> arguments) {
    var result = service.executeHelmCommand(request.helmDeploymentDirectory(), arguments,
        request.environmentVariables(), 330);
    if (result.exitCode() != 0) {
      throw new AssertionError("Helm recovery command failed: " + result.stderr());
    }
    return result.stdout();
  }
}
