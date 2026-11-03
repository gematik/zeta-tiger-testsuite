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

import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Write-ahead recovery of scenario-owned Kubernetes fields across JVM termination. */
public final class DeploymentStateRecovery {

  private static final ObjectMapper JSON = new ObjectMapper();
  static final String RESOURCE_OWNER = "testsuite.gematik.de/resource-owner";
  private static final ThreadLocal<DeploymentStateRecovery> CURRENT = new ThreadLocal<>();
  private final ZetaDeploymentModificationService service;
  private final PerformanceRoutingJournal journal;
  private final String namespace;
  private final ObjectNode snapshot;
  private boolean started;

  /**
   * Creates a namespace-scoped recovery session without changing Kubernetes.
   *
   * @param service command executor
   * @param namespace target namespace
   */
  public DeploymentStateRecovery(final ZetaDeploymentModificationService service, final String namespace) {
    this.service = service;
    this.namespace = namespace;
    journal = new PerformanceRoutingJournal(service, "testsuite-deployment-state");
    snapshot = JSON.createObjectNode().put("version", 1).put("namespace", namespace);
    snapshot.putArray("fields");
    snapshot.putArray("created");
  }

  /**
   * Returns the current scenario context, shared explicitly with asynchronous mutations.
   *
   * @return active context or null
   */
  public static DeploymentStateRecovery current() {
    return CURRENT.get();
  }

  /**
   * Prevents a mutation argument from silently targeting the session's different namespace.
   *
   * @param namespace requested namespace
   * @return matching context or null
   */
  public static DeploymentStateRecovery currentFor(final String namespace) {
    var context = CURRENT.get();
    if (context != null && !context.namespace.equals(namespace)) {
      throw new AssertionError("Deployment mutation targets a different namespace than its recovery session");
    }
    return context;
  }

  /** Attaches this recovery session to the scenario thread. */
  public void attach() {
    if (CURRENT.get() != null) {
      throw new IllegalStateException("Deployment recovery session already active");
    }
    CURRENT.set(this);
  }

  /**
   * Executes asynchronous work with the originating scenario's recovery context.
   *
   * @param context captured context
   * @param action mutation to execute
   * @return mutation result
   */
  public static CommandResult withContext(
      final DeploymentStateRecovery context, final Supplier<CommandResult> action) {
    var previous = CURRENT.get();
    CURRENT.set(context);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /** Repairs an abandoned session before routing recovery and before scenario preparation. */
  public void recoverAbandoned() {
    journal.recover(namespace, this::restore);
  }

  /** Restores the current session and keeps durable evidence if any cleanup fails. */
  public void finish() {
    CURRENT.remove();
    if (!started) {
      return;
    }
    try {
      journal.assertOwned(namespace);
      restore(snapshot);
      journal.complete(namespace);
    } finally {
      journal.release();
    }
  }

  /**
   * Reads a resource with command failure distinguished from an absent field.
   *
   * @param target resource/name
   * @return Kubernetes object
   */
  public JsonNode read(final String target) {
    var result = service.executeKubectlCommand(false, List.of("-n", namespace, "get", target, "-o", "json"));
    requireSuccess(result);
    return JSON.readTree(result.stdout());
  }

  /**
   * Creates a new namespaced fixture with durable ownership recorded before the request.
   *
   * @param manifest new core Service, Secret or ConfigMap without server metadata
   */
  public synchronized void create(final ObjectNode manifest) {
    var kind = manifest.path("kind").asString();
    if (!"v1".equals(manifest.path("apiVersion").asString())
        || !List.of("Service", "Secret", "ConfigMap").contains(kind)) {
      throw new AssertionError("Only core namespaced fixture resources are supported");
    }
    var resource = manifest.deepCopy();
    var metadata = resource.path("metadata");
    var name = metadata.path("name").asString();
    if (!metadata.isObject() || !name.matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")
        || metadata.has("uid") || metadata.has("resourceVersion")
        || (metadata.has("namespace") && !namespace.equals(metadata.path("namespace").asString()))) {
      throw new AssertionError("Invalid new fixture identity");
    }
    var target = kind.toLowerCase(java.util.Locale.ROOT) + "/" + name;
    if (readOptional(target) != null) {
      throw new AssertionError("Fixture already exists; refusing to overwrite " + target);
    }
    var owner = UUID.randomUUID().toString();
    ((ObjectNode) metadata).put("namespace", namespace).withObject("/annotations").put(RESOURCE_OWNER, owner);
    var entry = ((ArrayNode) snapshot.path("created")).addObject();
    entry.put("target", target).put("owner", owner);
    persist();
    var result = fileCommand(resource, List.of("-n", namespace, "create", "-f"), List.of("-o", "json"));
    requireSuccess(result);
    var created = JSON.readTree(result.stdout());
    var uid = created.path("metadata").path("uid").asString();
    if (uid.isBlank() || !owner.equals(created.path("metadata").path("annotations").path(RESOURCE_OWNER).asString())) {
      throw new AssertionError("Created fixture identity was not confirmed: " + target);
    }
    entry.put("uid", uid);
    persist();
  }

  /**
   * Reads an optional object, distinguishing absence from a failed Kubernetes request.
   *
   * @param target resource/name
   * @return object or null when absent
   */
  private JsonNode readOptional(final String target) {
    var result = service.executeKubectlCommand(false,
        List.of("-n", namespace, "get", target, "--ignore-not-found=true", "-o", "json"));
    requireSuccess(result);
    return result.stdout().isBlank() ? null : JSON.readTree(result.stdout());
  }

  /**
   * Confirms the observed immutable identity and ownership of a fixture created by this session.
   *
   * @param target resource/name
   * @param resource observed Kubernetes object
   * @return whether the creation journal owns this exact resource
   */
  boolean ownsCreated(final String target, final JsonNode resource) {
    var metadata = resource.path("metadata");
    return snapshot.path("created").valueStream().anyMatch(entry ->
        target.equals(entry.path("target").asString()) && entry.has("uid")
            && entry.path("uid").equals(metadata.path("uid"))
            && entry.path("owner").equals(metadata.path("annotations").path(RESOURCE_OWNER)));
  }

  /**
   * Saves the original and intended field values before an atomic, version-checked mutation.
   *
   * @param target resource/name
   * @param pointer field JSON pointer
   * @param value intended field value
   * @param restart deployment to restart when restoring a ConfigMap, otherwise empty
   * @return patch result
   */
  public synchronized CommandResult change(
      final String target, final String pointer, final JsonNode value, final String restart) {
    var resource = read(target);
    return changeObserved(target, pointer, value, restart, resource);
  }

  /**
   * Resolves container identity and image pointer from the same version used for the patch.
   *
   * @param target deployment/name
   * @param containerName container identity
   * @param image new image reference
   * @return patch result
   */
  public synchronized CommandResult changeImage(final String target, final String containerName, final String image) {
    var resource = read(target);
    var containers = resource.path("spec").path("template").path("spec").path("containers");
    for (int index = 0; index < containers.size(); index++) {
      if (containerName.equals(containers.get(index).path("name").asString())) {
        return changeObserved(target, "/spec/template/spec/containers/" + index + "/image",
            JSON.valueToTree(image), "", resource);
      }
    }
    throw new AssertionError("Container not found: " + containerName);
  }

  /**
   * Derives an object mutation from the resource version captured for conditional patching.
   *
   * @param target resource/name
   * @param pointer object field
   * @param transform mutation of an independent copy
   * @return patch result
   */
  public synchronized CommandResult changeObject(final String target, final String pointer,
      final UnaryOperator<JsonNode> transform) {
    var resource = read(target);
    var original = resource.at(pointer);
    if (!original.isObject()) {
      throw new AssertionError("Cannot modify absent or non-object configuration: " + target + pointer);
    }
    var desired = transform.apply(original.deepCopy());
    if (desired == null || !desired.isObject()) {
      throw new AssertionError("Configuration transformation must return an object");
    }
    return changeObserved(target, pointer, desired, "", resource);
  }

  /**
   * Pins a changed pod template to its running image and captures its original configuration.
   *
   * @param deployment deployment name
   * @param container container whose running image is preserved
   * @param digest observed running image digest
   * @param transform transformation of an independent template copy
   * @return patch result
   */
  public synchronized CommandResult changeTemplate(final String deployment, final String container,
      final String digest, final UnaryOperator<JsonNode> transform) {
    if (!digest.matches("sha256:[a-f0-9]{64}")) {
      throw new AssertionError("Invalid running image digest");
    }
    var target = "deployment/" + deployment;
    var pointer = "/spec/template";
    var resource = read(target);
    var original = resource.at(pointer);
    var desired = transform.apply(original.deepCopy());
    if (desired == null || !desired.isObject()) {
      throw new AssertionError("Configuration transformation must return an object");
    }
    var reference = DeploymentEnvironment.container(original, container).path("image").asString();
    if (reference.isBlank()) {
      throw new AssertionError("Missing container image: " + container);
    }
    var field = captureField(target, pointer, "", resource);
    var images = field.has("images") ? (ArrayNode) field.path("images") : field.putArray("images");
    var captured = images.valueStream().filter(image -> container.equals(image.path("container").asString())).findFirst();
    if (captured.isPresent()) {
      if (!digest.equals(captured.orElseThrow().path("digest").asString())) {
        throw new AssertionError("Running image differs from the recovery snapshot: " + deployment);
      }
    } else {
      images.addObject().put("container", container).put("digest", digest)
          .put("reference", reference.split("@", 2)[0] + "@" + digest);
    }
    return changeObserved(target, pointer, pinImages(desired, images), "", resource);
  }

  /**
   * Pins captured containers in an independent pod template to their immutable image references.
   *
   * @param template mutable template
   * @param images captured container images
   * @return pinned template
   */
  private JsonNode pinImages(final JsonNode template, final JsonNode images) {
    for (var image : images) {
      DeploymentEnvironment.container(template, image.path("container").asString())
          .put("image", image.path("reference").asString());
    }
    return template;
  }

  /**
   * Reconstructs the original field with captured pod images pinned to their observed digests.
   *
   * @param field saved mutation
   * @return independent recovery value
   */
  private JsonNode recoveryValue(final JsonNode field) {
    return pinImages(field.path("original").deepCopy(), field.path("images"));
  }

  /**
   * Derives a text mutation from the same observed resource used for backup and conditional patching.
   *
   * @param target resource/name
   * @param pointer configuration field
   * @param transform requested text modification
   * @param restart deployment requiring a restart on restoration
   * @return patch result and original text
   */
  public synchronized KubectlPatchCommandResult changeText(final String target, final String pointer,
      final UnaryOperator<String> transform, final String restart) {
    var resource = read(target);
    var original = resource.at(pointer).asString();
    if (original.isBlank()) {
      throw new AssertionError("Cannot modify empty configuration: " + target + pointer);
    }
    var result = changeObserved(target, pointer, JSON.valueToTree(transform.apply(original)), restart, resource);
    return new KubectlPatchCommandResult(result, original);
  }

  /**
   * Saves a field change and conditionally applies it to the observed resource version.
   *
   * @param target resource/name
   * @param pointer field pointer
   * @param value intended value
   * @param restart associated deployment
   * @param resource observed object
   * @return patch result
   */
  private CommandResult changeObserved(final String target, final String pointer, final JsonNode value,
      final String restart, final JsonNode resource) {
    var field = captureField(target, pointer, restart, resource);
    field.put("previousPresent", !resource.at(pointer).isMissingNode());
    field.set("previous", resource.at(pointer).isMissingNode() ? JSON.nullNode() : resource.at(pointer).deepCopy());
    field.set("expected", value.deepCopy());
    persist();
    return patch(target, resource, pointer, value, false);
  }

  /**
   * Captures an original field once and validates subsequent observations against its journal.
   *
   * @param target resource/name
   * @param pointer field pointer
   * @param restart associated deployment
   * @param resource observed resource
   * @return captured field
   */
  private ObjectNode captureField(final String target, final String pointer, final String restart, final JsonNode resource) {
    var uid = resource.path("metadata").path("uid").asString();
    if (uid.isBlank()) {
      throw new AssertionError("Missing resource UID for " + target);
    }
    ObjectNode field = null;
    for (var candidate : snapshot.path("fields")) {
      if (target.equals(candidate.path("target").asString()) && pointer.equals(candidate.path("pointer").asString())) {
        field = (ObjectNode) candidate;
        validate(resource, field);
        break;
      }
    }
    if (field == null) {
      field = ((ArrayNode) snapshot.path("fields")).addObject();
      field.put("target", target).put("pointer", pointer).put("uid", uid).put("restart", restart);
      field.put("originalPresent", !resource.at(pointer).isMissingNode());
      if (pointer.startsWith("/spec/template/spec/containers/") && pointer.endsWith("/image")) {
        var identity = pointer.substring(0, pointer.length() - "image".length()) + "name";
        field.put("identityPointer", identity);
        field.set("identity", resource.at(identity).deepCopy());
      }
      field.set("original", resource.at(pointer).isMissingNode() ? JSON.nullNode() : resource.at(pointer).deepCopy());
    }
    return field;
  }

  /** Persists the current recovery intent before a Kubernetes mutation. */
  private void persist() {
    if (started) {
      journal.update(namespace, snapshot);
    } else {
      journal.begin(namespace, snapshot);
      started = true;
    }
  }

  /**
   * Restores independently owned fields, then activates and verifies the recovered resources.
   *
   * @param saved durable snapshot
   */
  private void restore(final JsonNode saved) {
    if (saved.path("version").asInt() != 1 || !namespace.equals(saved.path("namespace").asString())
        || !saved.path("fields").isArray()) {
      throw new AssertionError("Invalid deployment recovery snapshot");
    }
    var failure = new AssertionError("Deployment cleanup failed; recovery backup retained");
    Set<String> deployments = new LinkedHashSet<>();
    Set<String> restarts = new LinkedHashSet<>();
    for (var field : saved.path("fields")) {
      try {
        var target = field.path("target").asString();
        var resource = readCapturedResource(saved, target);
        if (resource == null) {
          continue;
        }
        validate(resource, field);
        var pointer = field.path("pointer").asString();
        var restored = recoveryValue(field);
        if (!matches(resource.at(pointer), restored, field.path("originalPresent").asBoolean(), pointer)) {
          requireSuccess(patch(target, resource, pointer, restored,
              !field.path("originalPresent").asBoolean()));
        }
        if (target.startsWith("deployment/")) {
          deployments.add(target.substring("deployment/".length()));
        }
        if (!field.path("restart").asString().isBlank()) {
          restarts.add(field.path("restart").asString());
        }
      } catch (RuntimeException | AssertionError exception) {
        failure.addSuppressed(exception);
      }
    }
    for (var deployment : restarts) {
      try {
        requireSuccess(service.executeKubectlCommand(false, List.of("-n", namespace, "rollout", "restart",
            "deployment/" + deployment, "--field-manager=helm")));
        deployments.add(deployment);
      } catch (RuntimeException | AssertionError exception) {
        failure.addSuppressed(exception);
      }
    }
    for (var deployment : deployments) {
      try {
        requireSuccess(service.waitForDeploymentRollout(namespace, deployment, service.getPodReadyTimeoutSeconds()));
      } catch (RuntimeException | AssertionError exception) {
        failure.addSuppressed(exception);
      }
    }
    for (var field : saved.path("fields")) {
      try {
        var resource = readCapturedResource(saved, field.path("target").asString());
        if (resource == null) {
          continue;
        }
        if (!field.path("uid").asString().equals(resource.path("metadata").path("uid").asString())
            || !matches(resource.at(field.path("pointer").asString()),
                recoveryValue(field),
                field.path("originalPresent").asBoolean(), field.path("pointer").asString())) {
          throw new AssertionError("Original field not restored: " + field.path("target") + field.path("pointer"));
        }
        for (var image : field.path("images")) {
          var deployment = field.path("target").asString().substring("deployment/".length());
          var container = image.path("container").asString();
          var actual = DeploymentEnvironment.image(service, namespace, deployment, container);
          if (!image.path("digest").asString().equals(actual)) {
            var reference = DeploymentEnvironment.container(resource.at(field.path("pointer").asString()), container)
                .path("image").asString();
            throw new AssertionError("Original running image not restored: " + deployment + "/" + container
                + " using " + reference + "; expected " + image.path("digest").asString() + ", observed " + actual);
          }
        }
      } catch (RuntimeException | AssertionError exception) {
        failure.addSuppressed(exception);
      }
    }
    if (failure.getSuppressed().length == 0) {
      var created = saved.path("created");
      for (int index = created.size() - 1; index >= 0; index--) {
        try {
          deleteCreated(created.get(index));
        } catch (RuntimeException | AssertionError exception) {
          failure.addSuppressed(exception);
        }
      }
    }
    if (failure.getSuppressed().length > 0) {
      throw failure;
    }
  }

  /**
   * Allows a previously deleted owned fixture to remain absent during repeated recovery.
   *
   * @param saved recovery snapshot
   * @param target captured resource/name
   * @return captured resource, or null for an already absent created fixture
   */
  private JsonNode readCapturedResource(final JsonNode saved, final String target) {
    return saved.path("created").valueStream().anyMatch(entry -> target.equals(entry.path("target").asString()))
        ? readOptional(target) : read(target);
  }

  /**
   * Deletes only a fixture carrying this session's ownership and observed immutable identity.
   *
   * @param entry durable creation intent without Secret contents
   */
  private void deleteCreated(final JsonNode entry) {
    var target = entry.path("target").asString();
    if (!target.matches("(?:service|secret|configmap)/[a-z0-9][-a-z0-9]*")) {
      throw new AssertionError("Invalid fixture recovery target");
    }
    var current = readOptional(target);
    if (current == null) {
      return;
    }
    var metadata = current.path("metadata");
    var uid = metadata.path("uid").asString();
    var version = metadata.path("resourceVersion").asString();
    if (uid.isBlank() || version.isBlank() || entry.path("owner").asString().isBlank()
        || !entry.path("owner").equals(metadata.path("annotations").path(RESOURCE_OWNER))
        || (entry.has("uid") && !entry.path("uid").asString().equals(uid))) {
      throw new AssertionError("Fixture ownership changed; refusing deletion: " + target);
    }
    var options = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "DeleteOptions");
    options.putObject("preconditions").put("uid", uid).put("resourceVersion", version);
    var parts = target.split("/");
    var plural = "configmap".equals(parts[0]) ? "configmaps" : parts[0] + "s";
    requireSuccess(fileCommand(options, List.of("delete", "--raw",
        "/api/v1/namespaces/" + namespace + "/" + plural + "/" + parts[1], "-f"), List.of()));
    requireSuccess(service.executeKubectlCommand(false, List.of("-n", namespace, "wait", "--for=delete",
        target, "--timeout=" + service.getPodReadyTimeoutSeconds() + "s")));
    if (readOptional(target) != null) {
      throw new AssertionError("Fixture deletion was not observed: " + target);
    }
  }

  /**
   * Executes a manifest command without logging its potentially confidential contents.
   *
   * @param payload command document
   * @param prefix arguments before the file path
   * @param suffix remaining arguments
   * @return command result
   */
  private CommandResult fileCommand(final JsonNode payload, final List<String> prefix, final List<String> suffix) {
    try {
      var directory = Files.createDirectories(Path.of("target"));
      var file = Files.createTempFile(directory, "zeta-owned-resource-", ".json");
      try {
        Files.writeString(file, payload.toString());
        var arguments = new java.util.ArrayList<>(prefix);
        arguments.add(file.toString().replace('\\', '/'));
        arguments.addAll(suffix);
        return service.executeKubectlCommand(false, arguments);
      } finally {
        Files.deleteIfExists(file);
      }
    } catch (IOException exception) {
      throw new AssertionError("Cannot write fixture request", exception);
    }
  }

  /**
   * Rejects replaced resources and values not attributable to this session.
   *
   * @param resource current resource
   * @param field saved mutation
   */
  private void validate(final JsonNode resource, final JsonNode field) {
    var actual = resource.at(field.path("pointer").asString());
    if (field.has("identityPointer") && !field.path("identity").equals(resource.at(field.path("identityPointer").asString()))) {
      throw new AssertionError("Deployment container ordering changed; refusing image restoration");
    }
    if (!field.path("uid").asString().equals(resource.path("metadata").path("uid").asString())
        || !(matches(actual, field.path("original"), field.path("originalPresent").asBoolean(), field.path("pointer").asString())
            || matches(actual, field.path("previous"), field.path("previousPresent").asBoolean(), field.path("pointer").asString())
            || matches(actual, recoveryValue(field), field.path("originalPresent").asBoolean(), field.path("pointer").asString())
            || (field.has("restored") && matches(actual, field.path("restored"), true, field.path("pointer").asString()))
            || matches(actual, field.path("expected"), true, field.path("pointer").asString()))) {
      throw new AssertionError("External change detected: " + field.path("target") + field.path("pointer"));
    }
  }

  /**
   * Compares a stored value while preserving the distinction between null and absence.
   *
   * @param actual current node
   * @param expected saved node
   * @param present whether the saved field existed
   * @param pointer captured field pointer
   * @return whether values agree
   */
  private boolean matches(final JsonNode actual, final JsonNode expected, final boolean present, final String pointer) {
    if (!present) {
      return actual.isMissingNode();
    }
    if ("/spec/template".equals(pointer) && actual.isObject() && expected.isObject()) {
      return withVolumeDefaults(actual).equals(withVolumeDefaults(expected));
    }
    return actual.equals(expected);
  }

  /**
   * Accounts for API-server volume defaults without altering captured evidence or explicit modes.
   *
   * @param template pod template
   * @return independent template with omitted Secret and ConfigMap file modes defaulted
   */
  private JsonNode withVolumeDefaults(final JsonNode template) {
    var normalized = template.deepCopy();
    for (var volume : normalized.path("spec").path("volumes")) {
      for (var kind : List.of("secret", "configMap")) {
        var source = volume.path(kind);
        if (source.isObject() && !source.has("defaultMode")) {
          ((ObjectNode) source).put("defaultMode", 420);
        }
      }
    }
    return normalized;
  }

  /**
   * Applies an atomic field patch conditional on the observed resourceVersion.
   *
   * @param target resource/name
   * @param resource observed resource
   * @param pointer target field
   * @param value desired value
   * @param remove whether the original field was absent
   * @return command result
   */
  private CommandResult patch(
      final String target, final JsonNode resource, final String pointer, final JsonNode value, final boolean remove) {
    var version = resource.path("metadata").path("resourceVersion").asString();
    if (version.isBlank()) {
      throw new AssertionError("Missing resourceVersion for " + target);
    }
    var operations = JSON.createArrayNode();
    operations.addObject().put("op", "test").put("path", "/metadata/resourceVersion").put("value", version);
    var operation = operations.addObject().put("op", remove ? "remove" : "add").put("path", pointer);
    if (!remove) {
      operation.set("value", value);
    }
    try {
      var directory = Files.createDirectories(Path.of("target"));
      var file = Files.createTempFile(directory, "zeta-deployment-recovery-", ".json");
      try {
        Files.writeString(file, operations.toString());
        return service.executeKubectlCommand(false, List.of("-n", namespace, "patch", target,
            "--type=json", "--field-manager=helm", "--patch-file", file.toString().replace('\\', '/')));
      } finally {
        Files.deleteIfExists(file);
      }
    } catch (IOException exception) {
      throw new AssertionError("Cannot write deployment recovery patch", exception);
    }
  }

  /**
   * Converts command failure into a visible cleanup failure.
   *
   * @param result command result
   */
  private void requireSuccess(final CommandResult result) {
    if (result.exitCode() != 0) {
      throw new AssertionError("Deployment recovery command failed: " + result.stderr());
    }
  }
}
