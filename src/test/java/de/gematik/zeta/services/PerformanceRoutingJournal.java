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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Durable, exclusively claimed routing backup scoped to a Kubernetes namespace. */
public final class PerformanceRoutingJournal {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NAME = "testsuite-performance-routing";
  private static final Set<String> ACTIVE_OWNERS = ConcurrentHashMap.newKeySet();
  private final ZetaDeploymentModificationService deploymentService;
  private final String owner = UUID.randomUUID().toString();
  private final String host;
  private final String name;
  private ObjectNode claimed;

  /**
   * Creates a journal using the same Kubernetes connection as the routing service.
   *
   * @param deploymentService shared command executor
   */
  public PerformanceRoutingJournal(final ZetaDeploymentModificationService deploymentService) {
    this(deploymentService, NAME);
  }

  /**
   * Creates an independent durable journal using the shared ownership protocol.
   *
   * @param deploymentService command executor
   * @param name namespace-scoped ConfigMap name
   */
  public PerformanceRoutingJournal(final ZetaDeploymentModificationService deploymentService, final String name) {
    this.deploymentService = deploymentService;
    this.name = name;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException exception) {
      throw new IllegalStateException("Cannot identify routing journal owner host", exception);
    }
  }

  /**
   * Atomically claims an idle journal before any routing mutation.
   *
   * @param namespace target namespace
   * @param snapshot original routing state
   */
  public void begin(final String namespace, final JsonNode snapshot) {
    var existing = read(namespace);
    if (existing != null && existing.path("data").has("owner")) {
      throw new AssertionError("Routing journal is occupied; recover it before switching routes.");
    }
    claim(namespace, existing, snapshot);
  }

  /**
   * Recovers only a demonstrably abandoned local process, retaining the backup on failure.
   *
   * @param namespace target namespace
   * @param restore restoration callback
   */
  public void recover(final String namespace, final Consumer<JsonNode> restore) {
    var existing = read(namespace);
    if (existing == null || !existing.path("data").has("owner")) {
      return;
    }
    var data = existing.path("data");
    if (!host.equals(data.path("host").asText())) {
      throw new AssertionError("Routing backup belongs to another host; its owner must recover it.");
    }
    var pid = Long.parseLong(data.path("pid").asText());
    var start = data.path("start").asText();
    var process = ProcessHandle.of(pid);
    var sameProcess = process.isPresent() && process.orElseThrow().isAlive()
        && process.orElseThrow().info().startInstant().map(value -> value.toString().equals(start)).orElse(true);
    var localInactive = pid == ProcessHandle.current().pid()
        && !ACTIVE_OWNERS.contains(data.path("owner").asText());
    if (sameProcess && !localInactive) {
      throw new AssertionError("Another live test process owns the performance routes (PID " + pid + ").");
    }
    var snapshot = JSON.readTree(data.path("snapshot").asText());
    claim(namespace, existing, snapshot);
    try {
      restore.accept(snapshot);
      complete(namespace);
    } finally {
      release();
    }
  }

  /**
   * Clears the backup only after restoration and verification have succeeded.
   *
   * @param namespace target namespace
   */
  public void complete(final String namespace) {
    if (claimed == null) {
      throw new IllegalStateException("No routing journal is owned by this service.");
    }
    var cleared = claimed.deepCopy();
    cleared.putObject("data");
    write(namespace, cleared, "replace");
    claimed = null;
    release();
  }

  /**
   * Confirms that restoration still belongs to this journal claim.
   *
   * @param namespace target namespace
   */
  public void assertOwned(final String namespace) {
    var current = read(namespace);
    if (claimed == null || current == null
        || !owner.equals(current.path("data").path("owner").asText())
        || !claimed.path("metadata").path("resourceVersion")
            .equals(current.path("metadata").path("resourceVersion"))) {
      throw new AssertionError("Performance routing journal ownership changed; refusing restoration.");
    }
  }

  /**
   * Durably records the next mutation before Kubernetes is changed.
   *
   * @param namespace target namespace
   * @param snapshot updated recovery payload
   */
  public void update(final String namespace, final JsonNode snapshot) {
    assertOwned(namespace);
    var next = claimed.deepCopy();
    ((ObjectNode) next.path("data")).put("snapshot", snapshot.toString());
    claimed = write(namespace, next, "replace");
  }

  /** Releases only the in-process ownership marker; the durable backup remains available. */
  public void release() {
    ACTIVE_OWNERS.remove(owner);
  }

  /** Returns the namespace, name and immutable UID of the current journal, or null if absent. */
  String recoveryIdentity(final String namespace) {
    var current = read(namespace);
    if (current == null) {
      return null;
    }
    var uid = current.at("/metadata/uid").asString();
    if (uid.isBlank() || !namespace.equals(current.at("/metadata/namespace").asString())
        || !name.equals(current.at("/metadata/name").asString())) {
      throw new AssertionError("Missing or inconsistent Kubernetes journal identity");
    }
    return namespace + "/" + name + "/" + uid;
  }

  /**
   * Claims a journal with resourceVersion concurrency control or an exclusive initial create.
   *
   * @param namespace target namespace
   * @param existing current resource, or null
   * @param snapshot backup payload
   */
  private void claim(final String namespace, final ObjectNode existing, final JsonNode snapshot) {
    var resource = existing == null ? JSON.createObjectNode() : existing.deepCopy();
    if (existing == null) {
      resource.put("apiVersion", "v1");
      resource.put("kind", "ConfigMap");
      resource.putObject("metadata").put("name", name).put("namespace", namespace);
    }
    var data = resource.putObject("data");
    data.put("owner", owner);
    data.put("host", host);
    data.put("pid", Long.toString(ProcessHandle.current().pid()));
    data.put("start", ProcessHandle.current().info().startInstant().orElseThrow().toString());
    data.put("snapshot", snapshot.toString());
    ACTIVE_OWNERS.add(owner);
    try {
      claimed = write(namespace, resource, existing == null ? "create" : "replace");
    } catch (RuntimeException | AssertionError exception) {
      release();
      throw exception;
    }
  }

  /**
   * Reads the namespace journal without treating permission or connectivity failures as absence.
   *
   * @param namespace target namespace
   * @return journal, or null if absent
   */
  private ObjectNode read(final String namespace) {
    var result = deploymentService.executeKubectlCommand(false,
        List.of("-n", namespace, "get", "configmap", name, "--ignore-not-found", "-o", "json"));
    if (result.exitCode() != 0) {
      throw new AssertionError("Cannot read performance routing journal: " + result.stderr());
    }
    return result.stdout().isBlank() ? null : (ObjectNode) JSON.readTree(result.stdout());
  }

  /**
   * Writes a journal through a temporary manifest and returns the server resourceVersion.
   *
   * @param namespace target namespace
   * @param resource journal manifest
   * @param operation create or replace
   * @return stored manifest
   */
  private ObjectNode write(
      final String namespace, final ObjectNode resource, final String operation) {
    try {
      var directory = Files.createDirectories(Path.of("target"));
      var file = Files.createTempFile(directory, "zeta-routing-journal-", ".json");
      try {
        Files.writeString(file, resource.toString(), UTF_8);
        var result = deploymentService.executeKubectlCommand(false,
            List.of("-n", namespace, operation, "-f", file.toString().replace('\\', '/'), "-o", "json"));
        if (result.exitCode() != 0) {
          throw new AssertionError("Cannot " + operation + " performance routing journal: " + result.stderr());
        }
        return (ObjectNode) JSON.readTree(result.stdout());
      } finally {
        Files.deleteIfExists(file);
      }
    } catch (IOException exception) {
      throw new AssertionError("Cannot write performance routing journal", exception);
    }
  }
}
