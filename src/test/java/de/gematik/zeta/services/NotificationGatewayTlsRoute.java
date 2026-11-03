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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** Reversible routing of an explicitly dedicated gateway test-peer service; no NS changes. */
final class NotificationGatewayTlsRoute implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final String name;
  private final JsonNode original;
  private JsonNode selector;
  private boolean changed;

  NotificationGatewayTlsRoute(
      ZetaDeploymentModificationService service, String namespace, String name) {
    this.service = service;
    this.namespace = namespace;
    this.name = name;
    original = read();
    require("true".equals(original.at(
            "/metadata/annotations/testsuite.gematik.de~1tls-peer").asString()),
        "Gateway service must be explicitly dedicated to TLS test peers");
    var ports = original.at("/spec/ports");
    require(ports.size() == 1 && ports.get(0).path("port").asInt() == 443
            && ports.get(0).path("targetPort").asInt() == 4433
            && "TCP".equals(ports.get(0).path("protocol").asString()),
        "Gateway TLS fixture requires direct TCP 443 -> 4433 without TLS termination");
    require(original.at("/spec/selector").isObject(), "Missing TLS fixture selector");
  }

  void start(TlsTestToolEndpoint tool) throws InterruptedException {
    selector = tool.selector();
    require(selector != null && selector.isObject() && !selector.isEmpty(), "Missing shared TLS peer selector");
    tool.reserveRoute(original, selector);
    changed = true; // A successful API patch may have a lost reply.
    patch(original, original.at("/spec/selector"), selector);
    var deadline = System.nanoTime()
        + java.util.concurrent.TimeUnit.SECONDS.toNanos(service.getPodReadyTimeoutSeconds());
    do {
      var slices = JSON.readTree(execute("get", "endpointslices", "-l",
          "kubernetes.io/service-name=" + name, "-o", "json"));
      var endpoints = slices.path("items").valueStream()
          .flatMap(s -> s.path("endpoints").valueStream()).toList();
      if (endpoints.size() == 1) {
        var endpoint = endpoints.getFirst();
        if (endpoint.at("/conditions/ready").asBoolean()
            && endpoint.path("addresses").size() == 1
            && tool.address().equals(endpoint.at("/addresses/0").asString())
            && tool.name().equals(endpoint.at("/targetRef/name").asString())) {
          return;
        }
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Gateway fixture does not route exclusively to the current TLS peer");
  }

  void verify() {
    var live = read();
    require(original.at("/metadata/uid").equals(live.at("/metadata/uid"))
            && selector.equals(live.at("/spec/selector")),
        "Gateway TLS fixture routing changed during observation");
  }

  @Override
  public void close() {
    if (!changed) {
      return;
    }
    var live = read();
    require(original.at("/metadata/uid").equals(live.at("/metadata/uid")),
        "Gateway TLS fixture service was replaced; preserve the replacement");
    if (!original.at("/spec/selector").equals(live.at("/spec/selector"))) {
      patch(live, selector, original.at("/spec/selector"));
    }
    var restored = read();
    require(original.at("/metadata/uid").equals(restored.at("/metadata/uid"))
            && original.at("/spec/selector").equals(restored.at("/spec/selector")),
        "Gateway TLS fixture selector restoration was not observed");
    awaitDetached(service, namespace, name);
    changed = false;
  }

  /** Restores a route from the tool's durable ownership journal after an interrupted test. */
  static void restore(ZetaDeploymentModificationService service, String namespace, JsonNode saved) {
    String name = saved.path("name").asString();
    var result = service.executeKubectlCommand(false,
        List.of("-n", namespace, "get", "service", name, "--ignore-not-found=true", "-o", "json"));
    require(result.exitCode() == 0, "Cannot read saved TLS gateway route");
    if (result.stdout().isBlank()) {
      require(saved.path("fixtureOwner").asString().matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
              && !saved.path("uid").asString().isBlank(),
          "Saved TLS gateway service disappeared without owned fixture provenance");
      awaitDetached(service, namespace, name);
      return;
    }
    var live = JSON.readTree(result.stdout());
    require(saved.path("uid").equals(live.at("/metadata/uid")), "Saved TLS gateway service was replaced");
    if (saved.has("fixtureOwner")) {
      require(saved.path("fixtureOwner").equals(live.path("metadata").path("annotations")
          .path(DeploymentStateRecovery.RESOURCE_OWNER)), "Saved TLS fixture ownership changed");
    }
    if (!saved.path("original").equals(live.at("/spec/selector"))) {
      require(saved.path("expected").equals(live.at("/spec/selector")), "Saved TLS route was externally changed");
      try {
        var file = Files.createTempFile(Path.of("target"), "cc-tls-route-recovery-", ".json");
        try {
          Files.writeString(file, patchOperations(live, saved.path("expected"), saved.path("original")).toString());
          var patched = service.executeKubectlCommand(false, List.of("-n", namespace, "patch", "service", name,
              "--type=json", "--patch-file", file.toAbsolutePath().toString()));
          require(patched.exitCode() == 0, "Saved TLS gateway route restoration failed");
        } finally {
          Files.deleteIfExists(file);
        }
      } catch (IOException exception) {
        throw new AssertionError("Cannot restore saved TLS route", exception);
      }
    }
    var observed = service.executeKubectlCommand(false,
        List.of("-n", namespace, "get", "service", name, "-o", "json"));
    require(observed.exitCode() == 0, "Cannot verify saved TLS route restoration");
    var restored = JSON.readTree(observed.stdout());
    require(saved.path("uid").equals(restored.at("/metadata/uid"))
        && saved.path("original").equals(restored.at("/spec/selector")),
        "Saved TLS route restoration was not observed");
    awaitDetached(service, namespace, name);
  }

  private static void awaitDetached(ZetaDeploymentModificationService service, String namespace, String name) {
    long deadline = System.nanoTime()
        + java.util.concurrent.TimeUnit.SECONDS.toNanos(service.getPodReadyTimeoutSeconds());
    do {
      var result = service.executeKubectlCommand(false, List.of("-n", namespace, "get", "endpointslices", "-l",
          "kubernetes.io/service-name=" + name, "-o", "json"));
      require(result.exitCode() == 0, "Cannot observe TLS route detachment");
      boolean connected = JSON.readTree(result.stdout()).path("items").valueStream()
          .flatMap(s -> s.path("endpoints").valueStream())
          .anyMatch(e -> !e.has("conditions") || !e.path("conditions").has("ready")
              || e.at("/conditions/ready").asBoolean());
      if (!connected) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while detaching TLS gateway", exception);
      }
    } while (System.nanoTime() < deadline);
    throw new AssertionError("TLS gateway still has ready endpoints; reset refused");
  }

  private void patch(JsonNode live, JsonNode expected, JsonNode replacement) {
    // A file preserves JSON quotes across the Windows ProcessBuilder/WSL boundary.
    try {
      var file = Files.createTempFile(Path.of("target"), "cc-tls-route-", ".json");
      try {
        Files.writeString(file, patchOperations(live, expected, replacement).toString());
        execute("patch", "service", name, "--type=json", "--patch-file",
            file.toAbsolutePath().toString());
      } finally {
        Files.deleteIfExists(file);
      }
    } catch (IOException e) {
      throw new AssertionError("Gateway TLS fixture patch file operation failed", e);
    }
  }

  static ArrayNode patchOperations(JsonNode live, JsonNode expected, JsonNode replacement) {
    var operations = JSON.createArrayNode();
    for (var field : List.of("uid", "resourceVersion")) {
      var value = live.at("/metadata/" + field);
      require(value.isString() && !value.asString().isBlank(), "Missing TLS service identity");
      operations.addObject().put("op", "test").put("path", "/metadata/" + field)
          .set("value", value);
    }
    operations.addObject().put("op", "test").put("path", "/spec/selector")
        .set("value", expected);
    operations.addObject().put("op", "replace").put("path", "/spec/selector")
        .set("value", replacement);
    return operations;
  }

  private JsonNode read() {
    return JSON.readTree(execute("get", "service", name, "-o", "json"));
  }

  private String execute(String... args) {
    var command = new ArrayList<>(List.of("-n", namespace));
    command.addAll(List.of(args));
    var result = service.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "Gateway TLS fixture routing operation failed: "
        + args[0] + " (exit " + result.exitCode() + "): " + result.stderr());
    return result.stdout();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
