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
import de.gematik.zeta.testdriver.SdkCodeExpiryProbe;
import de.gematik.zeta.testdriver.SdkIdpOnlyProbe;
import de.gematik.zeta.testdriver.SdkLogoutProbe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the IDP-only public-SDK fixture without modifying the existing testdriver context.
 */
public final class SdkIdpOnlyRuntime implements AutoCloseable {
  private SdkProbeRuntime runtime;
  private String identity;
  private boolean parUri;

  /**
   * Prepares the command JVM without creating or authenticating any SDK context.
   */
  public void start(String identity, String emailDomain) throws Exception {
    start(identity, emailDomain, false);
  }

  private void start(String identity, String emailDomain, boolean parUri)
      throws Exception {
    require(runtime == null, "SDK runtime already prepared");
    require(KvnrGenerator.isValid(identity), "Invalid generated KVNR");
    require(emailDomain.matches("[A-Za-z0-9.-]+"), "Invalid test email domain");
    this.identity = identity;
    this.parUri = parUri;
    runtime = new SdkProbeRuntime("testdata.ccIdpOnly", "cc-idp-only", parUri ? 180 : 120);
    var mailbox =
        TigerGlobalConfiguration.readStringOptional("testdata.ccIdpOnly.mailBaseUrl")
            .map(TigerGlobalConfiguration::resolvePlaceholders)
            .orElseThrow();
    var arguments = new ArrayList<>(List.of(identity, emailDomain, mailbox));
    if (parUri) {
      arguments.add("outer-par");
    }
    runtime.start(
        parUri ? SdkCodeExpiryProbe.class : SdkIdpOnlyProbe.class,
        List.of(SdkIdpOnlyProbe.class, SdkLogoutProbe.class),
        arguments);
  }

  /** Selects only the six-second pause before the original outer authorize callback. */
  public void startParUri(String identity, String emailDomain) throws Exception {
    start(identity, emailDomain, true);
  }

  /**
   * Issues one action, preserving all contexts and the original total process deadline.
   */
  public Map<String, String> operation(String command) throws Exception {
    var report =
        runtime.operation(
            command, parUri ? SdkCodeExpiryProbe::parseParUriReport
                : SdkIdpOnlyRuntime::parseReport);
    require(identity.equals(report.get("identity")), "The probe used another identity");
    return report;
  }

  /**
   * Exports only C2's own synthetic proof keys, and only when explicitly requested.
   */
  public Map<String, String> verificationKeys() throws Exception {
    require(!parUri, "No verification key copy in the PAR expiry test");
    var report = operation("attacker_copy_keys");
    require("true".equals(report.get("proof_keys_copied")), "Missing original key copy");
    return runtime.verificationKeys();
  }

  @Override
  public void close() throws Exception {
    if (runtime != null) {
      runtime.close();
      runtime = null;
    }
  }

  static Map<String, String> parseReport(String output) {
    var allowed =
        new ArrayList<>(
            List.of(
                "protocol",
                "identity",
                "proof_keys_copied",
                "error_phase",
                "error_type",
                "attacker_awaiting",
                "attacker_auth_completed",
                "attacker_withheld_message_id",
                "attacker_otp_delivered",
                "attacker_started_ms",
                "attacker_pending_ms",
                "attacker_pending_after_control"));
    for (var role : List.of("owner", "attacker", "attacker_pending", "control")) {
      for (var field : List.of("client", "public_sha", "public_jkt", "at_count", "rt_count")) {
        allowed.add(role + "_" + field);
      }
    }
    for (var role : List.of("owner", "control")) {
      for (var field :
          List.of(
              "otp_message_id",
              "otp_delivered",
              "status",
              "access_sha",
              "refresh_sha",
              "started_ms",
              "completed_ms")) {
        allowed.add(role + "_" + field);
      }
    }
    var result = new LinkedHashMap<String, String>();
    for (var line : output.lines().toList()) {
      require(line.matches("[a-z_0-9]+=[A-Za-z0-9_.-]+"), "Unexpected IDP-only adapter output");
      var pair = line.split("=", 2);
      require(
          allowed.contains(pair[0]) && result.putIfAbsent(pair[0], pair[1]) == null,
          "Unexpected or duplicate IDP-only adapter field");
    }
    require(
        "sdk-idp-only-v1".equals(result.get("protocol")), "Unexpected IDP-only adapter protocol");
    return result;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
