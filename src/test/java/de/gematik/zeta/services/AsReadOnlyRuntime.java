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
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns a temporary probe only; neither changes nor restarts the AS deployment. */
public final class AsReadOnlyRuntime {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService service =
      ZetaDeploymentConfiguration.getServiceInstance();
  private final String configRoot;
  private final String container;
  private final JsonNode originalPod;
  private final String podName;

  /** Binds observations to the one ready AS pod before the SDK request starts. */
  public AsReadOnlyRuntime(String configRoot) {
    this.configRoot = configRoot;
    container = config("container");
    var deployment = query("get", "deployment", config("deployment"), "-o", "json");
    var labels = deployment.path("spec").path("selector").path("matchLabels");
    require(labels.isObject() && !labels.isEmpty(), "Missing AS selector");
    var selectors = new ArrayList<String>();
    labels
        .properties()
        .forEach(entry -> selectors.add(entry.getKey() + "=" + entry.getValue().asText()));
    var pods = query("get", "pods", "-l", String.join(",", selectors), "-o", "json").path("items");
    require(
        pods.isArray() && pods.size() == 1,
        "The AS observation profile requires exactly one AS pod");
    originalPod = pods.get(0);
    podName = originalPod.path("metadata").path("name").asText();
    verifyUnchanged(originalPod, originalPod, container);
  }

  /** Reads the bound AS container clock without a request or change to the AS. */
  public Instant currentTime() {
    verifyUnchanged(originalPod, query("get", "pod", podName, "-o", "json"), container);
    try {
      return parseClockObservation(exec("date", "-u", "+%s"));
    } finally {
      verifyUnchanged(originalPod, query("get", "pod", podName, "-o", "json"), container);
    }
  }

  static Instant parseClockObservation(String output) {
    require(output != null && output.matches("[0-9]{10}\\s*"),
        "Missing or malformed AS clock observation");
    return Instant.ofEpochSecond(Long.parseLong(output.strip()));
  }

  /** Runs a temporary probe after verifying the bound AS runtime remains unchanged. */
  public String observe(Class<?> probe, List<String> arguments) throws Exception {
    verifyUnchanged(originalPod, query("get", "pod", podName, "-o", "json"), container);
    var remote = "/dev/shm/cc-as-observer-" + UUID.randomUUID();
    var bytes = adapterJar(probe);
    var encoded = Base64.getEncoder().encodeToString(bytes);
    boolean created = false;
    try {
      exec("mkdir", "-m", "700", remote);
      created = true;
      // The AS image has no tar. Only our own class is uploaded, in bounded positional arguments.
      for (int start = 0; start < encoded.length(); start += 4096) {
        exec(
            "sh",
            "-c",
            "umask 077; printf '%s' \"$1\" >> \"$2\"",
            "sh",
            encoded.substring(start, Math.min(start + 4096, encoded.length())),
            remote + "/probe.b64");
      }
      exec(
          "sh",
          "-c",
          "umask 077; base64 -d \"$1\" > \"$2\"",
          "sh",
          remote + "/probe.b64",
          remote + "/probe.jar");
      require(
          exec("sha256sum", remote + "/probe.jar")
              .startsWith(
                  java.util.HexFormat.of()
                          .formatHex(
                              java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                      + "  "),
          "Probe upload hash mismatch");
      var command =
          new ArrayList<>(
              List.of(
                  "timeout",
                  "--signal=TERM",
                  "--kill-after=5s",
                  "20s",
                  "java",
                  "--add-modules",
                  "jdk.jdi",
                  "-cp",
                  remote + "/probe.jar:/opt/keycloak/providers/*:/opt/keycloak/lib/lib/main/*",
                  probe.getName()));
      command.addAll(arguments);
      return exec(command.toArray(String[]::new));
    } finally {
      try {
        if (created) {
          exec("rm", "-f", remote + "/probe.jar", remote + "/probe.b64");
          exec("rmdir", remote);
        }
      } finally {
        verifyUnchanged(originalPod, query("get", "pod", podName, "-o", "json"), container);
      }
    }
  }

  static void verifyUnchanged(JsonNode before, JsonNode after, String container) {
    require(
        !before.path("metadata").path("uid").asText().isBlank()
            && before.path("metadata").path("uid").equals(after.path("metadata").path("uid")),
        "AS pod identity changed");
    require(
        before.path("spec").isObject() && before.path("spec").equals(after.path("spec")),
        "AS pod configuration changed");
    var first = status(before, container);
    var last = status(after, container);
    for (var name : List.of("containerID", "imageID", "restartCount")) {
      require(
          first.has(name) && first.get(name).equals(last.get(name)), "AS runtime changed: " + name);
    }
    require(
        first.path("ready").isBoolean()
            && last.path("ready").isBoolean()
            && first.path("ready").booleanValue()
            && last.path("ready").booleanValue(),
        "AS must remain ready throughout the observation");
  }

  private static JsonNode status(JsonNode pod, String container) {
    var candidates =
        pod.path("status")
            .path("containerStatuses")
            .valueStream()
            .filter(value -> container.equals(value.path("name").asText()))
            .toList();
    require(candidates.size() == 1, "Missing or ambiguous AS container");
    return candidates.getFirst();
  }

  private static byte[] adapterJar(Class<?> probe) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var entry = probe.getName().replace('.', '/') + ".class";
    try (var input = probe.getClassLoader().getResourceAsStream(entry);
        var output = new JarOutputStream(bytes)) {
      require(input != null, "Compiled AS probe is unavailable");
      output.putNextEntry(new JarEntry(entry));
      input.transferTo(output);
      output.closeEntry();
    }
    return bytes.toByteArray();
  }

  private JsonNode query(String... args) {
    return JSON.readTree(command(args));
  }

  private String command(String... arguments) {
    var args = new ArrayList<>(List.of("-n", ZetaDeploymentConfiguration.getNamespace()));
    args.addAll(List.of(arguments));
    var result = service.executeKubectlCommand(false, args);
    require(result.exitCode() == 0, "AS observation command failed: " + arguments[0]);
    return result.stdout();
  }

  private String exec(String... arguments) {
    var args = new ArrayList<>(List.of("exec", podName, "-c", container, "--"));
    args.addAll(List.of(arguments));
    return command(args.toArray(String[]::new));
  }

  private String config(String key) {
    var value =
        TigerGlobalConfiguration.readStringOptional(configRoot + "." + key)
            .map(TigerGlobalConfiguration::resolvePlaceholders)
            .orElseThrow();
    require(value.matches("[A-Za-z0-9-]{1,128}"), "Unresolved or invalid AS observation profile");
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
