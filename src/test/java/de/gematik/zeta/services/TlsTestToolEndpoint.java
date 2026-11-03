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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exclusive sequential reuse of the installed TLS tool, with durable route cleanup and reset. */
public final class TlsTestToolEndpoint implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService kubernetes;
  private final String namespace;
  private final String deployment;
  private TlsTestToolSession session;
  private JsonNode source;
  private JsonNode pod;
  private String name;
  private Process forwarding;
  private TlsTestToolService api;
  private Map<String, String> servicePods = Map.of();
  private final Map<String, JsonNode> services = new LinkedHashMap<>();

  /** Selects the installed tool and the namespace-scoped exclusive ownership journal. */
  public TlsTestToolEndpoint(ZetaDeploymentModificationService kubernetes, String namespace,
      String deployment) {
    this.kubernetes = kubernetes;
    this.namespace = namespace;
    this.deployment = deployment;
  }

  TlsTestToolEndpoint(ZetaDeploymentModificationService kubernetes, String namespace,
      String deployment, TlsTestToolService api) {
    this(kubernetes, namespace, deployment);
    this.api = api;
  }

  /** Claims the shared tool, recovers abandoned routes, and fully resets the previous runtime. */
  public void start(Map<String, String> servicePods, String route) throws Exception {
    source = read("deployment", deployment);
    String selected = String.join(",", selector().properties().stream()
        .map(e -> e.getKey() + "=" + e.getValue().asString()).toList());
    require(!selected.isBlank(), "Missing TLS service selector");
    var pods = JSON.readTree(execute("get", "pods", "-l", selected, "-o", "json"))
        .path("items").valueStream()
        .filter(p -> !p.path("metadata").has("deletionTimestamp")).toList();
    require(pods.size() == 1, "Shared TLS service requires exactly one pod");
    pod = pods.getFirst();
    name = pod.at("/metadata/name").asString();
    require("Running".equals(pod.at("/status/phase").asString())
        && pod.at("/status/containerStatuses").size() == 1
        && pod.at("/status/containerStatuses/0/ready").asBoolean(), "Shared TLS tool is not Ready");
    if (api == null) {
      forward();
    }
    session = new TlsTestToolSession(kubernetes, namespace, api, this::verify);
    session.start(name, route);
    verify();
    for (var entry : servicePods.entrySet()) {
      String name = entry.getKey().split("\\.")[0];
      var installed = read("service", name);
      require(installed.at("/metadata/uid").isString()
              && !installed.at("/metadata/uid").asString().isBlank(),
          "Missing RS service identity");
      String address = installed.at("/spec/clusterIP").asString();
      require("None".equals(address) || address.matches("[0-9.]+"),
          "RS service requires an IPv4 ClusterIP or a headless endpoint");
      BoundedObservation.await(() -> serviceEndpointMatches(name, entry.getValue()),
          Boolean::booleanValue, Duration.ofSeconds(kubernetes.getPodReadyTimeoutSeconds()),
          Duration.ofMillis(100), "RS service did not converge to the observed NS pod");
      services.put(entry.getKey(), installed);
    }
    this.servicePods = Map.copyOf(servicePods);
    session.reset();
  }

  /** Expected TCP destination, independently observed from the service and its sole NS endpoint. */
  public String serviceAddress(String host) {
    require(services.containsKey(host), "RS service was not observed: " + host);
    verify();
    String address = services.get(host).at("/spec/clusterIP").asString();
    return "None".equals(address) ? servicePods.get(host) : address;
  }

  /** Persists route restoration before attaching a gateway to the shared tool. */
  void reserveRoute(JsonNode original, JsonNode selected) {
    session.reserveRoute(original, selected);
  }

  /**
   * Attaches a dedicated gateway Service to this endpoint and observes its exact pod destination.
   *
   * @param serviceName dedicated gateway Service name
   * @throws InterruptedException if endpoint convergence is interrupted
   */
  public void attachGateway(String serviceName) throws InterruptedException {
    verify();
    new NotificationGatewayTlsRoute(kubernetes, namespace, serviceName).start(this);
  }

  /** Restores and observes all reserved routes before resetting the owned TLS runtime. */
  public void reset() {
    verify();
    session.reset();
  }

  /** Selector of the installed, exclusively owned tool; no test pods or pod labels are created. */
  JsonNode selector() {
    return source.at("/spec/selector/matchLabels").deepCopy();
  }

  private void verifyServiceEndpoint(String service, String expectedAddress) {
    require(serviceEndpointMatches(service, expectedAddress),
        "RS service does not resolve exclusively to the observed NS pod");
  }

  private boolean serviceEndpointMatches(String service, String expectedAddress) {
    var slices = JSON.readTree(execute("get", "endpointslices", "-l",
        "kubernetes.io/service-name=" + service, "-o", "json"));
    var endpoints = slices.path("items").valueStream()
        .flatMap(s -> s.path("endpoints").valueStream()).toList();
    return endpoints.size() == 1 && endpoints.getFirst().at("/conditions/ready").asBoolean()
        && endpoints.getFirst().path("addresses").size() == 1
        && expectedAddress.equals(endpoints.getFirst().at("/addresses/0").asString());
  }

  private void forward() throws Exception {
    String host = "127.0.0.1";
    boolean windows =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    if (windows) {
      try (var commands = new SystemCommandService(5)) {
        var address = commands.executeCommand(List.of("hostname", "-I"), false);
        host = address.stdout().strip().split("\\s+")[0];
        require(
            address.exitCode() == 0 && host.matches("[0-9.]+"),
            "Missing reachable WSL IPv4 address for TLS service forwarding");
      }
    }
    final String forwardingHost = host;
    List<String> command =
        List.of(
            "kubectl",
            "-n",
            namespace,
            "port-forward",
            "pod/" + name,
            ":8080",
            "--address",
            forwardingHost);
    if (windows) {
      command = SystemCommandService.prepareWindowsCommand(command, "");
    }
    forwarding = new ProcessBuilder(command).redirectErrorStream(true).start();
    var ready = new CompletableFuture<Integer>();
    var process = forwarding;
    Thread.ofPlatform()
        .daemon()
        .start(
            () -> {
              try (var input =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                  var match =
                      Pattern.compile(
                              "Forwarding from "
                                  + Pattern.quote(forwardingHost)
                                  + ":([0-9]+) -> 8080")
                          .matcher(line);
                  if (match.find()) {
                    ready.complete(Integer.parseInt(match.group(1)));
                  }
                }
                ready.completeExceptionally(
                    new IllegalStateException("TLS service forwarding stopped"));
              } catch (java.io.IOException e) {
                ready.completeExceptionally(e);
              }
            });
    api =
        new TlsTestToolService("http://" + forwardingHost + ":" + ready.get(15, TimeUnit.SECONDS));
  }

  /** Uploads fresh configuration, certificate and CA under exclusive ownership. */
  public void configure(
      String config, Map<String, byte[]> material, String certificate, String key, String ca)
      throws Exception {
    reset();
    var files = new LinkedHashMap<String, Path>();
    try {
      for (var entry : material.entrySet()) {
        var file = Files.createTempFile(Path.of("target"), "cc-tls-upload-", ".pem");
        files.put(entry.getKey(), file);
        Files.write(file, entry.getValue());
      }
      var configuration = Files.createTempFile(Path.of("target"), "cc-tls-config-", ".conf");
      files.put("config", configuration);
      Files.writeString(configuration, config);
      api.updateConfig(configuration);
      if (certificate != null) {
        api.updateCertificate(files.get(certificate), files.get(key));
      }
      api.updateCaCertificate(files.get(ca));
    } finally {
      for (var file : files.values()) {
        Files.deleteIfExists(file);
      }
    }
  }

  /** Returns the API of the installed tool under exclusive ownership. */
  public TlsTestToolService api() {
    return session.api();
  }

  /** Actual installed TLS peer address for endpoint and handshake correlation. */
  public String address() {
    return pod.at("/status/podIP").asString();
  }

  /** Actual installed pod name. */
  public String name() {
    return name;
  }

  /** Detects replacement, restart or configuration changes during observation. */
  public void verify() {
    session.assertOwned();
    var live = read("pod", name);
    require(pod.at("/metadata/uid").equals(live.at("/metadata/uid"))
        && pod.path("spec").equals(live.path("spec"))
        && pod.at("/status/containerStatuses").equals(live.at("/status/containerStatuses"))
        && source.at("/spec/template").equals(read("deployment", deployment).at("/spec/template")),
        "Shared TLS service changed during observation");
    for (var entry : servicePods.entrySet()) {
      String name = entry.getKey().split("\\.")[0];
      var original = services.get(entry.getKey());
      var current = read("service", name);
      require(original.at("/metadata/uid").equals(current.at("/metadata/uid"))
              && original.path("spec").equals(current.path("spec")),
          "RS service changed during TLS observation");
      verifyServiceEndpoint(name, entry.getValue());
    }
  }

  /** Detaches routes before reset; clears the journal only after successful cleanup. */
  @Override
  public void close() {
    var failures = new ArrayList<Throwable>();
    try {
      if (session != null) {
        session.close();
      }
    } catch (RuntimeException | Error exception) {
      failures.add(exception);
    }
    if (forwarding != null) {
      try {
        forwarding.destroy();
        if (!forwarding.waitFor(2, TimeUnit.SECONDS)) {
          forwarding.destroyForcibly();
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        failures.add(new AssertionError("Interrupted while closing TLS forwarding", exception));
        try {
          forwarding.destroyForcibly();
        } catch (RuntimeException | Error failure) {
          failures.add(failure);
        }
      } catch (RuntimeException | Error exception) {
        failures.add(exception);
      }
    }
    if (!failures.isEmpty()) {
      var failure = failures.getFirst();
      failures.stream().skip(1).forEach(failure::addSuppressed);
      if (failure instanceof RuntimeException exception) {
        throw exception;
      }
      throw (Error) failure;
    }
  }

  private JsonNode read(String kind, String resource) {
    return JSON.readTree(execute("get", kind, resource, "-o", "json"));
  }

  private String execute(String... args) {
    var command = new ArrayList<>(List.of("-n", namespace));
    command.addAll(List.of(args));
    var result = kubernetes.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "TLS service operation failed: " + args[0]);
    return result.stdout();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
