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
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Three isolated real SDK contexts; only the original-factor controls receive correlated OTPs.
 */
public class SdkIdpOnlyProbe {
  private static final String SDK = "de.gematik.zeta.sdk.";
  private static final String DRIVER = "de.gematik.zeta.driver.";
  private static final Map<String, String> REPORT = new LinkedHashMap<>();

  private static Class<?> type(String n) throws Exception {
    return Class.forName(n);
  }

  private static Object construct(String n, Object... args) throws Exception {
    var constructors =
        Arrays.stream(type(n).getConstructors())
            .filter(x -> x.getParameterCount() == args.length)
            .toList();
    if (constructors.size() != 1) {
      throw new AssertionError("ambiguous public constructor");
    }
    return constructors.getFirst().newInstance(args);
  }

  static Object get(Object x, String name) throws Exception {
    return x.getClass().getMethod(name).invoke(x);
  }

  static Object operation(Object sdk, String name) throws Exception {
    return type(SDK + "ZetaSdkClientExtension")
        .getMethod(name, type(SDK + "ZetaSdkClient"))
        .invoke(null, sdk);
  }

  private static void yes(Object value) {
    if (!Boolean.TRUE.equals(value)) {
      throw new AssertionError("public operation unsuccessful");
    }
  }

  private static Map<?, ?> snapshot(Object store) throws Exception {
    return Map.copyOf((Map<?, ?>) get(store, "getMap"));
  }

  static String client(Map<?, ?> map) throws Exception {
    var ids = new HashSet<String>();
    collectClients(map, ids);
    if (ids.size() != 1) {
      throw new AssertionError("nonunique registered client");
    }
    return ids.iterator().next();
  }

  private static Object parse(String value) throws Exception {
    var json = type("kotlinx.serialization.json.Json").getField("Default").get(null);
    return type("kotlinx.serialization.json.Json")
        .getMethod("parseToJsonElement", String.class)
        .invoke(json, value);
  }

  private static void collectClients(Object value, Set<String> ids) throws Exception {
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        if ("client_id".equals(entry.getKey())) {
          var id = scalar(entry.getValue());
          if (!id.matches("[A-Za-z0-9-]+")) {
            throw new AssertionError("Invalid registered client ID");
          }
          ids.add(id);
        } else {
          collectClients(entry.getValue(), ids);
        }
      }
    } else if (value instanceof List<?> list) {
      for (var item : list) {
        collectClients(item, ids);
      }
    } else {
      var content = scalar(value);
      if (content.startsWith("{")) {
        collectClients(parse(content), ids);
      }
    }
  }

  private static String scalar(Object value) throws Exception {
    return value instanceof String string ? string : literal(value);
  }

  private static void state(String prefix, Object store) throws Exception {
    var s = snapshot(store);
    REPORT.put(prefix + "_client", client(s));
    var publicKeys =
        s.entrySet().stream()
            .filter(e -> e.getKey().toString().startsWith("client_public_key"))
            .map(e -> e.getValue().toString())
            .toList();
    if (publicKeys.size() != 1) {
      throw new AssertionError("nonunique public instance key");
    }
    REPORT.put(
        prefix + "_public_sha",
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(publicKeys.getFirst().getBytes(StandardCharsets.UTF_8))));
    REPORT.put(prefix + "_public_jkt", publicKeyThumbprint(publicKeys.getFirst()));
    REPORT.put(
        prefix + "_at_count",
        Long.toString(s.keySet().stream().filter(x -> x.toString().startsWith("at:")).count()));
    REPORT.put(
        prefix + "_rt_count",
        Long.toString(s.keySet().stream().filter(x -> x.toString().startsWith("rt:")).count()));
  }

  static String publicKeyThumbprint(String pem) throws Exception {
    if (pem == null
        || !pem.trim().startsWith("-----BEGIN PUBLIC KEY-----")
        || !pem.trim().endsWith("-----END PUBLIC KEY-----")) {
      throw new AssertionError("Expected only the SDK public SPKI key");
    }
    var bytes =
        java.util.Base64.getDecoder()
            .decode(
                pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", ""));
    var key =
        (java.security.interfaces.ECPublicKey)
            java.security.KeyFactory.getInstance("EC")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(bytes));
    var parameters = java.security.AlgorithmParameters.getInstance("EC");
    parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
    var expected = parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
    var actual = key.getParams();
    if (!actual.getCurve().equals(expected.getCurve())
        || !actual.getGenerator().equals(expected.getGenerator())
        || !actual.getOrder().equals(expected.getOrder())
        || actual.getCofactor() != expected.getCofactor()) {
      throw new AssertionError("Expected the SDK profile's P-256 instance key");
    }
    var canonical =
        "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\""
            + coordinate(key.getW().getAffineX())
            + "\",\"y\":\""
            + coordinate(key.getW().getAffineY())
            + "\"}";
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
  }

  private static String coordinate(java.math.BigInteger value) {
    if (value.signum() < 0 || value.bitLength() > 256) {
      throw new AssertionError("Invalid P-256 public coordinate");
    }
    var source = value.toByteArray();
    var result = new byte[32];
    int count = Math.min(source.length, result.length);
    System.arraycopy(source, source.length - count, result, result.length - count, count);
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(result);
  }

  static Object[] create(
      Object config,
      String identity,
      String email,
      java.util.function.UnaryOperator<Object> decorator)
      throws Exception {
    var store = construct(SDK + "storage.InMemoryStorage");
    var otp = construct(DRIVER + "oidc.TestDriverOtpCallback", email);
    var authenticator =
        construct(DRIVER + "oidc.TestDriverAuthenticator", get(config, "getOidcBaseUri"), identity);
    var oidc =
        construct(
            SDK + "authentication.oidc.OidcConfig",
            get(config, "getOidcIdpIss"),
            get(config, "getOidcIdpAlias"),
            get(config, "getOidcBaseUri"),
            decorator.apply(authenticator),
            otp);
    var provider = construct(SDK + "authentication.OidcTokenProvider", oidc);
    var attestation =
        type(SDK + "attestation.model.AttestationConfig").getMethod("software").invoke(null);
    var auth =
        construct(
            SDK + "authentication.AuthConfig",
            List.of("zero:audience"),
            30L,
            get(config, "getAslProdEnv"),
            provider,
            attestation,
            get(config, "getRequiredOid"));
    var storage = construct(SDK + "storage.StorageConfig$Custom", store);
    var tpm =
        Proxy.newProxyInstance(
            SdkIdpOnlyProbe.class.getClassLoader(),
            new Class<?>[] {type(SDK + "TpmConfig")},
            (p, m, a) -> null);
    var platform =
        construct(
            SDK + "attestation.model.PlatformProductId$LinuxProductId",
            "linux",
            "",
            "demo-client",
            "0.5.0");
    var http = construct(SDK + "network.http.client.ZetaHttpClientBuilder");
    http.getClass()
        .getMethod("timeouts", Long.class, Long.class, Long.class)
        .invoke(http, 20000L, 20000L, 20000L);
    http.getClass()
        .getMethod("disableServerValidation", boolean.class)
        .invoke(http, get(config, "getDisableTlsVerification"));
    http.getClass().getMethod("contentNegotiation", boolean.class).invoke(http, true);
    var build =
        construct(
            SDK + "BuildConfig",
            "test-proxy",
            "1.3.0",
            "sdk-client",
            storage,
            tpm,
            auth,
            platform,
            http,
            null,
            null,
            null);
    var sdk =
        type(SDK + "ZetaSdk")
            .getMethod("build", String.class, type(SDK + "BuildConfig"))
            .invoke(
                type(SDK + "ZetaSdk").getField("INSTANCE").get(null),
                get(config, "getFachdienstUrl"),
                build);
    return new Object[] {sdk, store, otp};
  }

  private static String mailBase;

  private static Integer withheldMailId;
  private static final HttpClient MAIL =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static String mail(String path) throws Exception {
    if (!path.matches("/messages(?:/[0-9]+\\.plain)?")) {
      throw new AssertionError("invalid mail read path");
    }
    if (withheldMailId != null && path.equals("/messages/" + withheldMailId + ".plain")) {
      throw new AssertionError("Original-factor OTP is forbidden for the attacker");
    }
    var r =
        MAIL.send(
            HttpRequest.newBuilder(URI.create(mailBase + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (r.statusCode() != 200) {
      throw new AssertionError("mail read failed");
    }
    return r.body();
  }

  private static List<?> messages() throws Exception {
    var result = parse(mail("/messages"));
    if (!(result instanceof List<?>)) {
      throw new AssertionError("mail metadata not a list");
    }
    return (List<?>) result;
  }

  private static String literal(Object x) throws Exception {
    return x instanceof String || x instanceof Number
        ? x.toString()
        : get(x, "getContent").toString();
  }

  private static Set<Integer> mailIds() throws Exception {
    var ids = new HashSet<Integer>();
    for (var x : messages()) {
      ids.add(Integer.parseInt(literal(((Map<?, ?>) x).get("id"))));
    }
    return ids;
  }

  private static int newMail(Set<Integer> before, String email) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    do {
      var found = matchingMail(messages(), before, email);
      if (found.size() == 1) {
        return found.getFirst();
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("new correlated OTP email unavailable");
  }

  static List<Integer> matchingMail(List<?> messages, Set<Integer> before, String email)
      throws Exception {
    var found = new ArrayList<Integer>();
    var allIds = new HashSet<Integer>();
    for (var item : messages) {
      var message = (Map<?, ?>) item;
      int id = Integer.parseInt(literal(message.get("id")));
      if (!allIds.add(id)) {
        throw new AssertionError("Duplicate mailbox message ID");
      }
      if (before.contains(id)) {
        continue;
      }
      var recipients = (List<?>) message.get("recipients");
      // A code shared with another recipient is not an exclusive proof of the intended factor.
      boolean recipient =
          recipients.size() == 1
              && literal(recipients.getFirst())
                  .replace("<", "")
                  .replace(">", "")
                  .equalsIgnoreCase(email);
      if (recipient
          && literal(message.get("subject"))
              .toLowerCase(Locale.ROOT)
              .contains("verification code")) {
        found.add(id);
      }
    }
    if (found.size() > 1) {
      throw new AssertionError("Ambiguous new control OTP message");
    }
    return List.copyOf(found);
  }

  private static CompletableFuture<Boolean> awaitChallenge(Object[] context) throws Exception {
    var pending =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return Boolean.TRUE.equals(operation(context[0], "authenticate"));
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!"otp".equals(get(context[2], "currentlyAwaiting"))
        && !pending.isDone()
        && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    if (!"otp".equals(get(context[2], "currentlyAwaiting"))) {
      throw new AssertionError("no pending OTP challenge");
    }
    return pending;
  }

  private static void submitOtp(
      String prefix,
      Object[] context,
      String email,
      Set<Integer> before,
      CompletableFuture<Boolean> pending)
      throws Exception {
    int messageId = newMail(before, email);
    var codes = new HashSet<String>();
    var m = Pattern.compile("\\b[0-9]{6}\\b").matcher(mail("/messages/" + messageId + ".plain"));
    while (m.find()) {
      codes.add(m.group());
    }
    if (codes.size() != 1) {
      throw new AssertionError("ambiguous correlated OTP code");
    }
    yes(
        context[2]
            .getClass()
            .getMethod("provideOtp", String.class)
            .invoke(context[2], codes.iterator().next()));
    yes(pending.get(25, TimeUnit.SECONDS));
    REPORT.put(prefix + "_otp_message_id", Integer.toString(messageId));
    REPORT.put(prefix + "_otp_delivered", "1");
    REPORT.put(prefix + "_status", operation(context[0], "status").toString());
    state(prefix, context[1]);
    var stored = snapshot(context[1]);
    REPORT.put(prefix + "_access_sha", hash(SdkLogoutProbe.uniqueToken(strings(stored), "at:")));
    REPORT.put(prefix + "_refresh_sha", hash(SdkLogoutProbe.uniqueToken(strings(stored), "rt:")));
    REPORT.put(prefix + "_completed_ms", Long.toString(System.currentTimeMillis()));
  }

  private static Map<String, String> strings(Map<?, ?> values) {
    var result = new LinkedHashMap<String, String>();
    for (var entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
        throw new AssertionError("Unexpected software fixture entry");
      }
      result.put(key, value);
    }
    return result;
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private final Object config;
  private final String identity;
  private final String emailDomain;
  private final Map<String, Context> contexts = new LinkedHashMap<>();

  SdkIdpOnlyProbe(String[] args) throws Exception {
    if (args.length != 3
        || !args[0].matches("[A-Z][0-9]{9}")
        || !args[1].matches("[A-Za-z0-9.-]+")) {
      throw new AssertionError("Synthetic identity required");
    }
    identity = args[0];
    emailDomain = args[1];
    mailBase = args[2];
    var uri = URI.create(mailBase);
    if (!List.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new AssertionError("Invalid local mailbox profile");
    }
    var companion = type(DRIVER + "model.SdkInstanceConfig").getField("Companion").get(null);
    config = companion.getClass().getMethod("fromFileOrEnv").invoke(companion);
    if (!"OIDC".equals(get(config, "getAuthMode").toString())) {
      throw new AssertionError("OIDC profile required");
    }
    REPORT.clear();
    withheldMailId = null;
    REPORT.put("protocol", "sdk-idp-only-v1");
    REPORT.put("identity", identity);
  }

  Context createContext(String role, java.util.function.UnaryOperator<Object> decorator)
      throws Exception {
    if (contexts.containsKey(role)) {
      throw new AssertionError("SDK context already created");
    }
    var email = identity + ("attacker".equals(role) ? "-new" : "") + "@" + emailDomain;
    var context = new Context(role, email, create(config, identity, email, decorator));
    contexts.put(role, context);
    return context;
  }

  /**
   * Preserves one SDK, store, authenticator and pending callback across separately issued actions.
   */
  static final class Context {
    final String role;
    final String email;
    final Object[] values;
    Set<Integer> baseline;
    CompletableFuture<Boolean> pending;
    private String next = "discover";

    Context(String role, String email, Object[] values) {
      this.role = role;
      this.email = email;
      this.values = values;
    }

    void action(String action) throws Exception {
      if (!next.equals(action)) {
        throw new AssertionError("SDK action out of order");
      }
      next = "failed";
      switch (action) {
        case "discover" -> {
          yes(operation(values[0], "discover"));
          next = "register";
        }
        case "register" -> {
          yes(operation(values[0], "register"));
          if (role.equals("attacker")) {
            state(role, values[1]);
          }
          next = "authenticate";
        }
        case "authenticate" -> {
          REPORT.put(role + "_started_ms", Long.toString(System.currentTimeMillis()));
          baseline = mailIds();
          pending = awaitChallenge(values);
          if (role.equals("attacker")) {
            REPORT.put("attacker_pending_ms", Long.toString(System.currentTimeMillis()));
          }
          next = role.equals("attacker") ? "withhold" : "otp";
        }
        case "otp" -> {
          submitOtp(role, values, email, baseline, pending);
          next = "complete";
        }
        default -> throw new AssertionError("Unknown SDK context action");
      }
    }

    void requirePhase(String phase) {
      if (!next.equals(phase)) {
        throw new AssertionError("Unexpected SDK context phase");
      }
    }
  }

  Map<String, String> execute(String command) throws Exception {
    if (command.equals("attacker_withhold")) {
      var attacker = contexts.get("attacker");
      attacker.requirePhase("withhold");
      // Metadata only: never read or fulfill this original-factor OTP.
      withheldMailId = newMail(attacker.baseline, identity + "@" + emailDomain);
      REPORT.put("attacker_withheld_message_id", withheldMailId.toString());
      REPORT.put("attacker_otp_delivered", "0");
      REPORT.put("attacker_awaiting", get(attacker.values[2], "currentlyAwaiting").toString());
      REPORT.put("attacker_auth_completed", Boolean.toString(attacker.pending.isDone()));
      state("attacker_pending", attacker.values[1]);
      attacker.next = "observe";
    } else if (command.equals("attacker_observe")) {
      var attacker = contexts.get("attacker");
      attacker.requirePhase("observe");
      contexts.get("control").requirePhase("complete");
      REPORT.put(
          "attacker_pending_after_control",
          Boolean.toString(
              "otp".equals(get(attacker.values[2], "currentlyAwaiting"))
                  && !attacker.pending.isDone()));
      attacker.next = "copy_keys";
    } else if (command.equals("attacker_copy_keys")) {
      var attacker = contexts.get("attacker");
      attacker.requirePhase("copy_keys");
      var directory = directory();
      var keyFile = directory.resolve("verification-keys.txt");
      Files.createFile(
          keyFile,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(
          keyFile,
          SdkLogoutProbe.encodedKeyCopy(strings(snapshot(attacker.values[1]))),
          StandardCharsets.UTF_8);
      REPORT.put("proof_keys_copied", "true");
      attacker.next = "complete";
    } else {
      var parts = command.split("_", 2);
      if (parts.length != 2 || !Set.of("owner", "attacker", "control").contains(parts[0])) {
        throw new AssertionError("Unknown IDP context command");
      }
      if (parts[1].equals("create")) {
        createContext(parts[0], java.util.function.UnaryOperator.identity());
      } else {
        contexts.get(parts[0]).action(parts[1]);
      }
    }
    return Map.copyOf(REPORT);
  }

  static Path directory() throws Exception {
    var directory =
        Path.of(SdkIdpOnlyProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .getParent();
    if (!directory.toString().startsWith("/dev/shm/cc-idp-only-")) {
      throw new AssertionError("Unexpected private probe directory");
    }
    return directory;
  }

  /**
   * Serves individual public SDK operations; the runtime bounds and owns the entire process.
   */
  public static void main(String[] args) throws Exception {
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    int exit = 2;
    try {
      var probe = new SdkIdpOnlyProbe(args);
      SdkProbeControl.serve(directory(), probe::execute);
      exit = 0;
    } finally {
      System.exit(exit);
    }
  }
}
