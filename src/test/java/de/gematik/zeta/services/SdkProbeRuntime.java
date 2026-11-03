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
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.testdriver.SdkProbeControl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns one bounded SDK JVM in the unchanged driver, with separately controlled operations. */
public final class SdkProbeRuntime implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService service =
      ZetaDeploymentConfiguration.getServiceInstance();
  private final String configRoot;
  private final String remote;
  private final int timeoutSeconds;
  private final String authMode;
  private JsonNode original;
  private String pod;
  private String container;
  private Path jar;
  private boolean created;
  private ExecutorService executor;
  private Future<CommandResult> process;
  private int sequence;

  /** Keeps each adapter's original total process limit and its private staging namespace. */
  public SdkProbeRuntime(String configRoot, String prefix, int timeoutSeconds) {
    this(configRoot, prefix, timeoutSeconds, "OIDC");
  }

  /** Selects the required profile without changing the deployed driver. */
  public SdkProbeRuntime(String configRoot, String prefix, int timeoutSeconds, String authMode) {
    require(prefix.matches("cc-[a-z-]+"), "Invalid SDK staging prefix");
    require(timeoutSeconds > 0 && timeoutSeconds <= 180, "Invalid SDK process deadline");
    this.configRoot = configRoot;
    this.remote = "/dev/shm/" + prefix + "-" + UUID.randomUUID();
    this.timeoutSeconds = timeoutSeconds;
    require(List.of("OIDC", "SMB").contains(authMode), "Unsupported SDK authentication profile");
    this.authMode = authMode;
  }

  /** Starts only the command server; SDK actions are issued explicitly by subsequent steps. */
  public void start(Class<?> main, List<Class<?>> dependencies, List<String> arguments)
      throws Exception {
    require(process == null && original == null, "SDK runtime already started");
    container = config("container");
    var deployment = query("get", "deployment", config("deployment"), "-o", "json");
    var labels = deployment.at("/spec/selector/matchLabels");
    require(labels.isObject() && !labels.isEmpty(), "Testdriver has no unique selector");
    var selector =
        String.join(
            ",",
            labels.properties().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().asString())
                .toList());
    var pods = query("get", "pods", "-l", selector, "-o", "json").path("items");
    require(pods.isArray() && pods.size() == 1, "Expected exactly one JVM testdriver pod");
    original = pods.get(0);
    pod = original.at("/metadata/name").asString();
    SdkLogoutRuntime.verifyUnchanged(original, original, container);
    var configured =
        original
            .at("/spec/containers")
            .valueStream()
            .filter(value -> container.equals(value.path("name").asString()))
            .toList();
    require(configured.size() == 1, "Expected exactly one SDK container");
    verifyAuthMode(configured.getFirst().path("env"), authMode);
    jar = adapterJar(main, dependencies);
    exec("mkdir", "-m", "700", remote);
    created = true;
    command(
        "cp", jar.toAbsolutePath().toString(), pod + ":" + remote + "/probe.jar", "-c", container);
    var args =
        new ArrayList<>(
            List.of(
                "-n",
                ZetaDeploymentConfiguration.getNamespace(),
                "exec",
                pod,
                "-c",
                container,
                "--",
                "timeout",
                "--signal=TERM",
                "--kill-after=5s",
                timeoutSeconds + "s",
                "java",
                "-cp",
                remote + "/probe.jar:/app/zeta-testdriver.jar:/app/lib/*",
                main.getName()));
    args.addAll(arguments);
    executor = Executors.newSingleThreadExecutor();
    process = executor.submit(() -> service.executeKubectlCommand(false, args));
    require("ready".equals(awaitFile("ready", 10)), "SDK command server did not become ready");
  }

  /** Executes one adapter command and validates its complete sanitized report. */
  public Map<String, String> operation(String action, Function<String, Map<String, String>> parser)
      throws Exception {
    require(process != null && !process.isDone(), "SDK command server is not running");
    require(action.matches("[a-z][a-z_]{0,63}"), "Invalid SDK operation");
    verifyUnchanged();
    sequence++;
    exec(
        "sh",
        "-c",
        "umask 077; printf '%s' \"$1\" > \"$2.tmp\" && mv \"$2.tmp\" \"$2\"",
        "sh",
        action,
        remote + "/request-" + sequence);
    var response = awaitFile("response-" + sequence, timeoutSeconds);
    require(
        response.startsWith("OK\n"),
        "SDK adapter operation failed: "
            + action
            + (response.matches("ERROR\n[A-Za-z0-9]+") ? " (" + response.substring(6) + ")" : ""));
    var report = new java.util.LinkedHashMap<>(parser.apply(response.substring(3)));
    var status =
        original
            .at("/status/containerStatuses")
            .valueStream()
            .filter(value -> container.equals(value.path("name").asString()))
            .findFirst()
            .orElseThrow();
    report.put("image_id", status.path("imageID").asString());
    verifyUnchanged();
    return Map.copyOf(report);
  }

  /** Reads only the original synthetic key copy; never puts it in RBEL or report variables. */
  public Map<String, String> verificationKeys() {
    require(created && process != null && !process.isDone(), "SDK context is unavailable");
    return SdkLogoutRuntime.decodeKeyCopy(exec("cat", remote + "/verification-keys.txt"));
  }

  private String awaitFile(String name, int seconds) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    do {
      var value =
          exec(
              "sh",
              "-c",
              "if test -f \"$1\"; then head -c 65537 \"$1\"; fi",
              "sh",
              remote + "/" + name);
      require(value.length() <= 65536, "Oversized SDK command response");
      if (!value.isEmpty()) {
        return value;
      }
      require(!process.isDone(), "SDK process ended before completing its command");
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("SDK command deadline exceeded");
  }

  private void verifyUnchanged() {
    SdkLogoutRuntime.verifyUnchanged(original, query("get", "pod", pod, "-o", "json"), container);
  }

  private static Path adapterJar(Class<?> main, List<Class<?>> dependencies) throws Exception {
    Files.createDirectories(Path.of("target", "sdk-controlled-probe"));
    final var path =
        Files.createTempFile(Path.of("target", "sdk-controlled-probe"), "adapter-", ".jar");
    var classes = new LinkedHashSet<Class<?>>();
    collect(main, classes);
    collect(SdkProbeControl.class, classes);
    dependencies.forEach(type -> collect(type, classes));
    try (var output = new JarOutputStream(Files.newOutputStream(path))) {
      for (var type : classes) {
        var name = type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream("/" + name)) {
          require(input != null, "Compiled SDK adapter is unavailable");
          output.putNextEntry(new JarEntry(name));
          input.transferTo(output);
          output.closeEntry();
        }
      }
    } catch (Exception | AssertionError failure) {
      Files.deleteIfExists(path);
      throw failure;
    }
    return path;
  }

  private static void collect(Class<?> type, java.util.Set<Class<?>> classes) {
    if (classes.add(type)) {
      for (var nested : type.getDeclaredClasses()) {
        collect(nested, classes);
      }
    }
  }

  static void verifyAuthMode(JsonNode environment, String required) {
    require(List.of("OIDC", "SMB").contains(required), "Unsupported SDK authentication profile");
    var modes =
        environment
            .valueStream()
            .filter(value -> "AUTH_MODE".equals(value.path("name").asString()))
            .toList();
    require(
        environment.isArray()
            && modes.size() == 1
            && modes.getFirst().path("value").isString()
            && required.equals(modes.getFirst().path("value").asString())
            && !modes.getFirst().has("valueFrom"),
        "SDK authentication profile is missing or ambiguous");
  }

  /** Stops the owned JVM without invoking another SDK action, then removes only its own files. */
  @Override
  public void close() throws Exception {
    var failures = new ArrayList<Throwable>();
    boolean terminated = process == null;
    if (process != null) {
      try {
        if (!process.isDone()) {
          exec("touch", remote + "/stop");
        }
        process.get(timeoutSeconds + 10L, TimeUnit.SECONDS);
        terminated = true;
        process = null;
      } catch (Exception | AssertionError failure) {
        failures.add(failure);
      }
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    if (created && terminated) {
      try {
        // This UUID directory was exclusively created by this instance. No shared files live here.
        require(remote.matches("/dev/shm/cc-[a-z-]+-[a-f0-9-]{36}"), "Invalid owned SDK directory");
        exec(
            "sh",
            "-c",
            "find \"$1\" -mindepth 1 -maxdepth 1 -type f -delete && rmdir \"$1\"",
            "sh",
            remote);
        created = false;
      } catch (Exception | AssertionError failure) {
        failures.add(failure);
      }
    }
    if (jar != null) {
      try {
        Files.deleteIfExists(jar);
        jar = null;
      } catch (Exception failure) {
        failures.add(failure);
      }
    }
    if (original != null) {
      try {
        verifyUnchanged();
      } catch (Exception | AssertionError failure) {
        failures.add(failure);
      }
    }
    if (!failures.isEmpty()) {
      var failure =
          new AssertionError("SDK adapter cleanup or original driver verification failed");
      failures.forEach(failure::addSuppressed);
      throw failure;
    }
  }

  private JsonNode query(String... args) {
    return JSON.readTree(command(args));
  }

  private String exec(String... args) {
    var command = new ArrayList<>(List.of("exec", pod, "-c", container, "--"));
    command.addAll(List.of(args));
    return command(command.toArray(String[]::new));
  }

  private String command(String... args) {
    var command = new ArrayList<>(List.of("-n", ZetaDeploymentConfiguration.getNamespace()));
    command.addAll(List.of(args));
    var result = service.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "SDK runtime Kubernetes operation failed: " + args[0]);
    return result.stdout();
  }

  private String config(String key) {
    var value =
        TigerGlobalConfiguration.readStringOptional(configRoot + "." + key)
            .map(TigerGlobalConfiguration::resolvePlaceholders)
            .orElseThrow();
    require(value.matches("[a-z0-9][a-z0-9-]*"), "Invalid SDK runtime selector");
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
