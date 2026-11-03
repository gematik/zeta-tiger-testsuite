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

import de.gematik.zeta.testdriver.SdkLogoutProbe;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Separately controls public SDK operations while retaining the same original in-memory context.
 */
public final class SdkLogoutRuntime implements AutoCloseable {
  private final SdkProbeRuntime runtime =
      new SdkProbeRuntime("testdata.ccSdkLogout", "cc-sdk-logout", 100);
  private String identity;

  /**
   * Prepares only the bounded adapter JVM; it does not authenticate or log out.
   */
  public void start(String identity, String emailDomain, boolean copyKeys) throws Exception {
    require(KvnrGenerator.isValid(identity), "Invalid generated KVNR");
    require(emailDomain.matches("[A-Za-z0-9.-]+"), "Invalid test email domain");
    this.identity = identity;
    var arguments = new ArrayList<>(List.of(identity, identity + "@" + emailDomain));
    if (copyKeys) {
      arguments.add("proof-copy");
    }
    runtime.start(SdkLogoutProbe.class, List.of(), arguments);
  }

  /**
   * Executes one operation and binds its safe report to this fixture identity.
   */
  public Map<String, String> operation(String command) throws Exception {
    var report = runtime.operation(command, SdkLogoutRuntime::parseReport);
    require(identity.equals(report.get("identity")), "The probe used another identity");
    return report;
  }

  /**
   * Returns only a copy of the original synthetic keys for independent grant verification.
   */
  public Map<String, String> verificationKeys() {
    return runtime.verificationKeys();
  }

  @Override
  public void close() throws Exception {
    runtime.close();
  }

  static void verifyUnchanged(JsonNode before, JsonNode after, String container) {
    require(
        before.path("metadata").path("uid").equals(after.path("metadata").path("uid")),
        "The testdriver pod was replaced during the probe");
    require(
        before.path("spec").equals(after.path("spec")),
        "The testdriver pod configuration changed during the probe");
    var first = status(before, container);
    var last = status(after, container);
    for (var key : List.of("containerID", "imageID", "restartCount")) {
      require(
          first.has(key) && first.get(key).equals(last.get(key)),
          "Testdriver runtime changed: " + key);
    }
    require(
        last.path("ready").isBoolean() && last.path("ready").booleanValue(),
        "Testdriver is no longer ready after the isolated probe");
  }

  static Map<String, String> parseReport(String output) {
    var allowed =
        List.of(
            "protocol",
            "driver_sha256",
            "sdk_sha256",
            "identity",
            "authentication_started_ms",
            "access_sha256",
            "refresh_sha256",
            "status_before",
            "logout_started_ms",
            "logout_completed_ms",
            "logout_success",
            "access_entries_after",
            "refresh_entries_after",
            "access_value_retained",
            "refresh_value_retained",
            "control_socket_writes",
            "logout_socket_writes",
            "adapter_error",
            "adapter_error_type",
            "proof_keys_copied");
    var result = new LinkedHashMap<String, String>();
    for (var line : output.lines().toList()) {
      require(line.matches("[a-z_0-9]+=[A-Za-z0-9_.:-]+"), "Unexpected SDK adapter output");
      var pair = line.split("=", 2);
      require(allowed.contains(pair[0]), "Unexpected SDK adapter field");
      require(result.putIfAbsent(pair[0], pair[1]) == null, "Duplicate SDK adapter field");
    }
    require("sdk-logout-v1".equals(result.get("protocol")), "Unexpected SDK adapter protocol");
    return result;
  }

  static Map<String, String> decodeKeyCopy(String encoded) {
    var roles =
        List.of("client_public_key", "client_private_key", "dpop_public_key", "dpop_private_key");
    var result = new LinkedHashMap<String, String>();
    require(encoded.length() < 16384, "Oversized software verification-key copy");
    for (var line : encoded.lines().toList()) {
      var pair = line.split("=", 2);
      require(
          pair.length == 2 && roles.contains(pair[0]) && !result.containsKey(pair[0]),
          "Unexpected verification-key role");
      try {
        var value = new String(Base64.getDecoder().decode(pair[1]), StandardCharsets.UTF_8);
        require(!value.isBlank(), "Empty verification-key copy");
        result.put(pair[0], value);
      } catch (IllegalArgumentException failure) {
        throw new AssertionError("Malformed verification-key encoding");
      }
    }
    require(result.keySet().containsAll(roles), "Incomplete verification-key copy");
    return Map.copyOf(result);
  }

  private static JsonNode status(JsonNode pod, String container) {
    var statuses =
        pod.path("status")
            .path("containerStatuses")
            .valueStream()
            .filter(value -> container.equals(value.path("name").asText()))
            .toList();
    require(statuses.size() == 1, "Expected one testdriver container status");
    return statuses.getFirst();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
