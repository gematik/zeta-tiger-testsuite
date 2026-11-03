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

package de.gematik.zeta.testdriver;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/**
 * Runs a separate SDK context using the unmodified JARs already installed in the JVM testdriver.
 *
 * <p>Only public testdriver/SDK entry points are invoked. No SDK state is injected or cleared by
 * this adapter. Reflection keeps the testsuite independent of a copied or repackaged SDK binary.
 * The process emits only a small allowlisted protocol; SDK logs and exception bodies are
 * suppressed. An optional private RAM file holds a separate copy of this fresh fixture's software
 * keys for independent grant verification after logout; the calling adapter reads and removes it.
 */
public final class SdkLogoutProbe {
  private static final String MANAGER = "de.gematik.zeta.driver.TestDriverManager";
  private static final String CLIENT = "de.gematik.zeta.sdk.ZetaSdkClient";
  private static final String EXTENSION = "de.gematik.zeta.sdk.ZetaSdkClientExtension";

  private final Map<String, String> report = new LinkedHashMap<>();
  private final String identity;
  private final String email;
  private final boolean copyKeys;
  private final Path directory;
  private final Class<?> managerClass;
  private final Class<?> clientClass;
  private final Class<?> extension;
  private Object sdk;
  private Object storage;
  private Map<String, String> before;
  private Recording controlTraffic;
  private String phase = "new";

  private SdkLogoutProbe(String[] args) throws Exception {
    require(
        (args.length == 2 || args.length == 3 && "proof-copy".equals(args[2]))
            && args[0].matches("[A-Z][0-9]{9}")
            && args[1].matches("[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+"),
        "Invalid synthetic identity");
    require("OIDC".equals(System.getenv("AUTH_MODE")), "OIDC profile required");
    identity = args[0];
    email = args[1];
    copyKeys = args.length == 3;
    managerClass = Class.forName(MANAGER);
    clientClass = Class.forName(CLIENT);
    extension = Class.forName(EXTENSION);
    directory =
        Path.of(SdkLogoutProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .getParent();
    require(
        directory.toString().startsWith("/dev/shm/cc-sdk-logout-"),
        "Unexpected probe staging directory");
    System.setProperty("java.io.tmpdir", directory.toString());
    report.put("protocol", "sdk-logout-v1");
    report.put("driver_sha256", classArchiveHash(managerClass));
    report.put("sdk_sha256", classArchiveHash(clientClass));
    report.put("identity", identity);
  }

  /**
   * Executes exactly the selected public operation on the original in-memory SDK context.
   */
  private Map<String, String> execute(String command) throws Exception {
    switch (command) {
      case "create" -> create();
      case "discover" -> {
        require("created".equals(phase), "SDK discovery is out of order");
        controlTraffic = startSocketRecording();
        require(
            Boolean.TRUE.equals(extension.getMethod("discover", clientClass).invoke(null, sdk)),
            "SDK discovery failed");
        phase = "discovered";
      }
      case "register" -> {
        require("discovered".equals(phase), "SDK registration is out of order");
        require(
            Boolean.TRUE.equals(extension.getMethod("register", clientClass).invoke(null, sdk)),
            "SDK registration failed");
        phase = "registered";
      }
      case "authenticate" -> authenticate();
      case "logout" -> logout();
      default -> throw new AssertionError("Unknown SDK operation");
    }
    return Map.copyOf(report);
  }

  private void create() throws Exception {
    require("new".equals(phase), "SDK context already exists");
    var manager = managerClass.getConstructor().newInstance();
    managerClass
        .getMethod("setKvnrEmail", String.class, String.class)
        .invoke(manager, identity, email);
    sdk = managerClass.getMethod("getSdk").invoke(manager);
    storage = managerClass.getMethod("getStorage").invoke(manager);
    require(
        storage.getClass().getName().equals("de.gematik.zeta.sdk.storage.InMemoryStorage"),
        "Only the explicit in-memory testdriver profile is supported");
    phase = "created";
  }

  private void authenticate() throws Exception {
    require("registered".equals(phase), "SDK authentication is out of order");
    report.put("authentication_started_ms", Long.toString(System.currentTimeMillis()));
    require(
        Boolean.TRUE.equals(extension.getMethod("authenticate", clientClass).invoke(null, sdk)),
        "SDK authentication failed");
    report.put("control_socket_writes", Long.toString(socketWrites(controlTraffic, directory)));
    controlTraffic.close();
    controlTraffic = null;
    report.put(
        "status_before", extension.getMethod("status", clientClass).invoke(null, sdk).toString());
    before = snapshot(storage);
    report.put("access_sha256", hash(uniqueToken(before, "at:")));
    report.put("refresh_sha256", hash(uniqueToken(before, "rt:")));
    if (copyKeys) {
      var file = directory.resolve("verification-keys.txt");
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(file, encodedKeyCopy(before), StandardCharsets.UTF_8);
      report.put("proof_keys_copied", "true");
    }
    phase = "authenticated";
  }

  private void logout() throws Exception {
    require("authenticated".equals(phase), "SDK logout is out of order");
    try (var logoutTraffic = startSocketRecording()) {
      report.put("logout_started_ms", Long.toString(System.currentTimeMillis()));
      var result = extension.getMethod("logout", clientClass).invoke(null, sdk);
      report.put("logout_completed_ms", Long.toString(System.currentTimeMillis()));
      report.put("logout_success", Boolean.toString(Boolean.TRUE.equals(result)));
      // Snapshot before status/close or another SDK operation can clear or refresh state.
      report.putAll(storageEvidence(before, snapshot(storage)));
      Thread.sleep(2000);
      report.put("logout_socket_writes", Long.toString(socketWrites(logoutTraffic, directory)));
    }
    phase = "logged_out";
  }

  /**
   * Serves explicit operations; process termination releases SDK threads without another SDK call.
   */
  public static void main(String[] args) {
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    int exitCode = 2;
    SdkLogoutProbe probe = null;
    try {
      probe = new SdkLogoutProbe(args);
      SdkProbeControl.serve(probe.directory, probe::execute);
      exitCode = 0;
    } catch (Exception | AssertionError failure) {
      // The control protocol emits only sanitized operation errors; never dump SDK exceptions.
    } finally {
      if (probe != null && probe.controlTraffic != null) {
        probe.controlTraffic.close();
      }
    }
    System.exit(exitCode);
  }

  private static Recording startSocketRecording() {
    var recording = new Recording();
    recording.setToDisk(false);
    recording.enable("jdk.SocketWrite").withThreshold(java.time.Duration.ZERO).withoutStackTrace();
    recording.start();
    return recording;
  }

  private static long socketWrites(Recording recording, Path directory) throws Exception {
    var path = directory.resolve("socket-count.jfr");
    try {
      // An in-memory recording must be dumped while running; stopping discards its buffers.
      recording.dump(path);
      recording.stop();
      return RecordingFile.readAllEvents(path).stream()
          .filter(
              event ->
                  "jdk.SocketWrite".equals(event.getEventType().getName())
                      && event.getLong("bytesWritten") > 0)
          .count();
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static Map<String, String> snapshot(Object storage) throws Exception {
    var value = storage.getClass().getMethod("getMap").invoke(storage);
    require(value instanceof Map<?, ?>, "Storage is not a map");
    var copy = new LinkedHashMap<String, String>();
    for (var entry : ((Map<?, ?>) value).entrySet()) {
      require(
          entry.getKey() instanceof String && entry.getValue() instanceof String,
          "Unexpected storage entry");
      copy.put((String) entry.getKey(), (String) entry.getValue());
    }
    return Map.copyOf(copy);
  }

  static String encodedKeyCopy(Map<String, String> storage) {
    var result = new StringBuilder();
    for (var prefix :
        List.of("client_public_key", "client_private_key", "dpop_public_key", "dpop_private_key")) {
      var matches =
          storage.entrySet().stream()
              .filter(e -> e.getKey().startsWith(prefix))
              .map(Map.Entry::getValue)
              .toList();
      require(
          matches.size() == 1 && !matches.getFirst().isBlank(),
          "Expected one original software test key per role");
      result
          .append(prefix)
          .append('=')
          .append(
              Base64.getEncoder()
                  .encodeToString(matches.getFirst().getBytes(StandardCharsets.UTF_8)))
          .append('\n');
    }
    return result.toString();
  }

  static Map<String, String> storageEvidence(
      Map<String, String> before, Map<String, String> after) {
    var access = uniqueToken(before, "at:");
    var refresh = uniqueToken(before, "rt:");
    var result = new LinkedHashMap<String, String>();
    result.put(
        "access_entries_after",
        Long.toString(after.keySet().stream().filter(key -> key.startsWith("at:")).count()));
    result.put(
        "refresh_entries_after",
        Long.toString(after.keySet().stream().filter(key -> key.startsWith("rt:")).count()));
    result.put(
        "access_value_retained",
        Boolean.toString(after.values().stream().anyMatch(value -> value.contains(access))));
    result.put(
        "refresh_value_retained",
        Boolean.toString(after.values().stream().anyMatch(value -> value.contains(refresh))));
    return result;
  }

  static String uniqueToken(Map<String, String> storage, String prefix) {
    var values =
        storage.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .toList();
    require(
        values.size() == 1 && values.getFirst() != null && !values.getFirst().isBlank(),
        "Expected exactly one stored token in the fresh SDK context");
    return values.getFirst();
  }

  private static String classArchiveHash(Class<?> type) throws Exception {
    var location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    require(
        Files.isRegularFile(location) && location.getFileName().toString().endsWith(".jar"),
        "Expected an installed runtime JAR");
    var digest = MessageDigest.getInstance("SHA-256");
    try (var stream = Files.newInputStream(location)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = stream.read(buffer)) != -1) {
        digest.update(buffer, 0, count);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
