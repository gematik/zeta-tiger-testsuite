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
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Delays the original public outer PAR callback.
 */
public final class SdkCodeExpiryProbe {
  private static final Map<String, String> REPORT = new ConcurrentHashMap<>();

  @FunctionalInterface
  interface Waiter {
    void sleep(long milliseconds) throws InterruptedException;
  }

  /** Delays the original outer callback, preserving its exact arguments and continuation. */
  static Object invokeParCallback(Object delegate, Method method, Object[] args,
      Map<String, String> evidence, Waiter waiter) throws Exception {
    if (method.getName().equals("authenticationCb")) {
      if (args == null || args.length != 3 || !(args[0] instanceof String client)
          || !client.equals(evidence.get("delayed_client")) || !(args[1] instanceof String uri)
          || !URI.create(uri).isAbsolute()) {
        throw new AssertionError("Missing original registered PAR callback context");
      }
      var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(uri.getBytes(StandardCharsets.UTF_8)));
      if (evidence.putIfAbsent("par_callback_uri_sha", hash) != null) {
        throw new AssertionError("Repeated outer PAR callback");
      }
      evidence.put("delay_started_ms", Long.toString(System.currentTimeMillis()));
      waiter.sleep(6000);
      evidence.put("delay_completed_ms", Long.toString(System.currentTimeMillis()));
    }
    return method.invoke(delegate, args);
  }

  private static Object delayedPar(Object delegate) {
    try {
      return Proxy.newProxyInstance(delegate.getClass().getClassLoader(), new Class<?>[] {
          Class.forName("de.gematik.zeta.sdk.authentication.oidc.AuthenticationCallback"),
          Class.forName("de.gematik.zeta.sdk.OidcEndpointAware"),
          Class.forName("de.gematik.zeta.sdk.HttpClientBuilderAware")},
          (p, method, args) -> invokeParCallback(delegate, method, args, REPORT, Thread::sleep));
    } catch (ClassNotFoundException e) {
      throw new AssertionError("Public PAR authenticator interfaces unavailable", e);
    }
  }

  /**
   * Accepts only non-secret protocol fields from the independently running SDK adapter.
   */
  public static Map<String, String> parseParUriReport(String output) {
    var allowed =
        Set.of(
            "protocol",
            "identity",
            "delayed_started_ms",
            "delayed_completed_ms",
            "delay_started_ms",
            "delay_completed_ms",
            "delayed_outcome",
            "error_phase",
            "error_type");
    var fields = new java.util.HashSet<>(allowed);
    fields.addAll(Set.of("delayed_client", "par_callback_uri_sha", "par_auth_completed",
        "par_at_count", "par_rt_count"));
    var result = new LinkedHashMap<String, String>();
    for (var line : output.lines().toList()) {
      if (!line.matches("[a-z_0-9]+=[A-Za-z0-9_.-]+")) {
        throw new AssertionError("Unsafe code-expiry evidence");
      }
      var pair = line.split("=", 2);
      if (!fields.contains(pair[0]) || result.putIfAbsent(pair[0], pair[1]) != null) {
        throw new AssertionError("Unexpected code-expiry field");
      }
    }
    if (!"sdk-par-uri-expiry-v1".equals(result.get("protocol"))) {
      throw new AssertionError("Unexpected code-expiry protocol");
    }
    return result;
  }

  private final SdkIdpOnlyProbe shared;
  private SdkIdpOnlyProbe.Context delayedContext;

  SdkCodeExpiryProbe(String[] args) throws Exception {
    if (args.length != 4 || !"outer-par".equals(args[3])) {
      throw new AssertionError("Unknown expiry profile");
    }
    shared = new SdkIdpOnlyProbe(Arrays.copyOf(args, 3));
    REPORT.clear();
    REPORT.put("protocol", "sdk-par-uri-expiry-v1");
    REPORT.put("identity", args[0]);
  }

  Map<String, String> execute(String command) throws Exception {
    if (command.equals("delayed_create")) {
      delayedContext = shared.createContext("delayed",
          SdkCodeExpiryProbe::delayedPar);
    } else if (command.equals("delayed_discover") || command.equals("delayed_register")) {
      delayedContext.action(command.substring("delayed_".length()));
      if (command.equals("delayed_register")) {
        REPORT.put("delayed_client", SdkIdpOnlyProbe.client(
            (Map<?, ?>) SdkIdpOnlyProbe.get(delayedContext.values[1], "getMap")));
      }
    } else if (command.equals("delayed_authenticate")) {
      delayedContext.requirePhase("authenticate");
      if (REPORT.containsKey("delayed_started_ms")) {
        throw new AssertionError("Delayed authentication already started");
      }
      REPORT.put("delayed_started_ms", Long.toString(System.currentTimeMillis()));
      var pending =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return String.valueOf(
                      SdkIdpOnlyProbe.operation(delayedContext.values[0], "authenticate"));
                } catch (Exception failure) {
                  return "exception";
                }
              });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
      while (!pending.isDone()
          && !"otp".equals(SdkIdpOnlyProbe.get(delayedContext.values[2], "currentlyAwaiting"))
          && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      REPORT.put(
          "delayed_outcome",
          pending.isDone()
              ? pending.get()
              : "otp".equals(SdkIdpOnlyProbe.get(delayedContext.values[2], "currentlyAwaiting"))
                  ? "otp_pending"
                  : "timeout");
      REPORT.put("delayed_completed_ms", Long.toString(System.currentTimeMillis()));
      REPORT.put("par_auth_completed", Boolean.toString(pending.isDone()));
      var stored = (Map<?, ?>) SdkIdpOnlyProbe.get(delayedContext.values[1], "getMap");
      for (var token : Set.of("at", "rt")) {
        REPORT.put("par_" + token + "_count", Long.toString(stored.keySet().stream()
            .filter(k -> k.toString().startsWith(token + ":")).count()));
      }
    } else {
      throw new AssertionError("Unknown code-expiry command");
    }
    return Map.copyOf(REPORT);
  }

  /**
   * Delays only the explicitly selected context; no other SDK action runs implicitly.
   */
  public static void main(String[] args) throws Exception {
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    int exit = 2;
    try {
      var probe = new SdkCodeExpiryProbe(args);
      SdkProbeControl.serve(SdkIdpOnlyProbe.directory(), probe::execute);
      exit = 0;
    } finally {
      System.exit(exit);
    }
  }
}
