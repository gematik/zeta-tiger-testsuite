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

package de.gematik.zeta.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.perf.CsvUtils;
import de.gematik.zeta.perf.CsvUtils.CsvData;
import de.gematik.zeta.perf.CsvUtils.CsvRow;
import de.gematik.zeta.perf.JMeterRunner;
import de.gematik.zeta.perf.JMeterTestConfig;
import de.gematik.zeta.perf.JtlSummarizer;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber step definitions for running JMeter plans and asserting summary metrics.
 */
@Slf4j
public class JMeterSteps {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DEFAULT_GUARD_TOKEN_PATH =
      "/auth/realms/zeta-guard/protocol/openid-connect/token";
  private static final String DEFAULT_LOAD_PATH_TEMPLATE = "/load/{id}{proxyPath}";
  private static final String LOAD_CREATE_BODY_INLINE = "LOAD_CREATE_BODY_INLINE";
  private static final String LOAD_ONE_INSTANCE_PER_THREAD = "LOAD_ONE_INSTANCE_PER_THREAD";
  private static final String LOAD_SMCB_KEYSTORE_MANIFEST = "LOAD_SMCB_KEYSTORE_MANIFEST";
  private static final String LOAD_SMCB_KEYSTORE_POOL_DIR = "LOAD_SMCB_KEYSTORE_POOL_DIR";
  private static final String LOAD_SMCB_KEYSTORE_PASSWORD = "LOAD_SMCB_KEYSTORE_PASSWORD";

  private final JMeterRunner jmeterRunner;
  private final JtlSummarizer jtlSummarizer;

  /**
   * Initializes JMeter runner and summarizer.
   */
  public JMeterSteps() {
    this.jmeterRunner = new JMeterRunner();
    this.jtlSummarizer = new JtlSummarizer();
  }

  /**
   * Runs a JMeter plan with optional CLI-like arguments from a DataTable.
   *
   * @param planPath  path to JMeter plan (.jmx)
   * @param argsTable optional flags/values (e.g., -l, -o, -q, -f, -Jkey=val)
   */
  @Wenn("ich JMeter mit dem Plan {tigerResolvedString} starte")
  @When("I start JMeter with plan {tigerResolvedString}")
  public void runJMeterWithPlan(String planPath, DataTable argsTable) throws Exception {
    log.info("Starting JMeter with plan: {}", planPath);

    Map<String, String> parameters = new HashMap<>(); // non -J flags
    Map<String, String> jmeterProps = new HashMap<>(); // -J properties
    Path jtlOutput = null;
    Path htmlOutput = null;
    Path propertiesFile = null;

    // Parse Cucumber table into flags/values.
    if (argsTable != null) {
      for (List<String> row : argsTable.asLists()) {
        if (row == null || row.isEmpty()) {
          continue;
        }

        String flag = row.get(0) != null ? row.get(0).trim() : "";
        String value = row.size() > 1 && row.get(1) != null ? row.get(1).trim() : "";
        value = resolveTigerPlaceholders(value);

        if (flag.isEmpty()) {
          continue;
        }

        switch (flag) {
          case "-l" -> { // JTL result file
            if (!value.isEmpty()) {
              jtlOutput = Path.of(value);
            }
          }
          case "-o" -> { // HTML report dir
            if (!value.isEmpty()) {
              htmlOutput = Path.of(value);
            }
          }
          case "-q" -> { // properties file
            if (!value.isEmpty()) {
              propertiesFile = Path.of(value);
            }
          }
          case "-e" -> {
          } // ignore (report handled elsewhere)
          case "-f" -> // overwrite results
              parameters.put(flag, "");
          default -> {
            if (flag.startsWith("-J")) {
              // Accept "-Jk=v" or "-Jk v"
              String propertySpec = flag.contains("=") ? flag : (flag + "=" + value);
              String propertyPart = propertySpec.substring(2);

              String[] parts = propertyPart.split("=", 2);
              if (parts.length == 2) {
                String key = parts[0];
                String val = parts[1];

                jmeterProps.put(key, val);
                log.debug("Added JMeter property: {}={}", key, val);
              }
            } else {
              // General parameter
              parameters.put(flag, value.isEmpty() ? "" : value);
            }
          }
        }
      }
    }

    // Pass plan/JTL to prepareLoadDriver so wave mode can run JMeter per wave
    jmeterProps.put("__PLAN_PATH__", planPath);
    if (jtlOutput != null) {
      jmeterProps.put("__JTL_OUTPUT__", jtlOutput.toString());
    }
    final Map<String, String> finalParameters = parameters;
    final Path finalHtmlOutput = htmlOutput;
    final Path finalPropertiesFile = propertiesFile;

    LoadDriverContext loadDriverContext = jmeterProps.containsKey("LOAD_DRIVER_BASE_URL")
        ? prepareLoadDriver(jmeterProps)
        : null;

    AtomicBoolean backgroundResetStop = new AtomicBoolean(false);
    Thread backgroundResetThread = null;
    if (loadDriverContext != null && loadDriverContext.backgroundResetIds() != null) {
      List<Integer> resetIds = loadDriverContext.backgroundResetIds();
      String resetPathTemplate = loadDriverContext.backgroundResetPathTemplate();
      Duration resetStepTimeout = loadDriverContext.backgroundResetStepTimeout();
      HttpClient resetClient = loadDriverContext.client();
      String resetBaseUrl = loadDriverContext.baseUrl();
      backgroundResetThread = Thread.ofVirtual().name("background-reset").start(() -> {
        log.info("Background reset thread started: instances={}", resetIds.size());
        long totalResets = 0;
        while (!backgroundResetStop.get()) {
          for (int instanceId : resetIds) {
            if (backgroundResetStop.get()) {
              break;
            }
            String path = buildLoadInstancePath(instanceId, "/reset", resetPathTemplate);
            URI uri = URI.create(resetBaseUrl + path);
            try {
              HttpRequest request = HttpRequest.newBuilder(uri).timeout(resetStepTimeout).GET().build();
              resetClient.send(request, HttpResponse.BodyHandlers.discarding());
              totalResets++;
            } catch (Exception e) {
              log.warn("Background reset failed for instanceId={}: {}", instanceId, e.getMessage());
            }
          }
          if (!backgroundResetStop.get()) {
            log.info("Background reset cycle complete: totalResets={}", totalResets);
          }
        }
        log.info("Background reset thread stopped: totalResets={}", totalResets);
      });
    }

    try {
      // Defaults for properties
      int targetRps = Integer.parseInt(jmeterProps.getOrDefault("TARGET_RPS", "300"));
      String baseUrl = requireProperty(jmeterProps, "BASE_URL");

      JMeterTestConfig config = JMeterTestConfig.builder()
          .planTemplate(Path.of(planPath))
          .targetRps(targetRps)
          .baseUrl(baseUrl)
          .parameters(parameters)
          .jmeterProperties(jmeterProps)
          .jtlOutput(jtlOutput)
          .htmlOutput(htmlOutput)
          .propertiesFile(propertiesFile)
          .build();

      log.info("JMeter config: targetRps={}, baseUrl={}", targetRps, baseUrl);
      jmeterRunner.runTest(config);
    } finally {
      if (backgroundResetThread != null) {
        backgroundResetStop.set(true);
        try {
          backgroundResetThread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Interrupted while waiting for background reset thread to stop");
        }
      }
      if (loadDriverContext != null) {
        if (loadDriverContext.cleanupAfterTest()) {
          deleteAllLoadDriverInstances(loadDriverContext.client(), loadDriverContext.baseUrl());
        }
        deleteInstancePathsFileQuietly(loadDriverContext.instancePathsFile());
      }
    }
  }

  private LoadDriverContext prepareLoadDriver(Map<String, String> jmeterProps) throws Exception {
    String loadDriverBaseUrl = normalizeBaseUrl(
        requireProperty(jmeterProps, "LOAD_DRIVER_BASE_URL"));
    int configuredInstanceCount = parsePositiveInt(
        jmeterProps.get("LOAD_INSTANCE_COUNT"), 1, "LOAD_INSTANCE_COUNT");
    boolean autoInit = parseBoolean(jmeterProps.get("LOAD_AUTO_INIT"), true);
    boolean cleanupBeforeCreate = parseBoolean(
        jmeterProps.get("LOAD_DELETE_BEFORE_CREATE"), true);
    boolean waitReady = parseBoolean(jmeterProps.get("LOAD_WAIT_READY"), autoInit);
    int readyTimeoutSeconds = parsePositiveInt(
        jmeterProps.get("LOAD_READY_TIMEOUT_S"), 180, "LOAD_READY_TIMEOUT_S");
    int readyPollMs = parsePositiveInt(
        jmeterProps.get("LOAD_READY_POLL_MS"), 1000, "LOAD_READY_POLL_MS");
    String createBody = generateLoadCreateBodyIfNeeded(
        jmeterProps, configuredInstanceCount, autoInit);
    int instanceCount = resolveExpectedInstanceCount(
        jmeterProps, createBody, configuredInstanceCount);
    int createBatchSize = parsePositiveInt(
        jmeterProps.get("LOAD_CREATE_BATCH_SIZE"), instanceCount, "LOAD_CREATE_BATCH_SIZE");
    int totalClients = parsePositiveInt(
        jmeterProps.get("LOAD_TOTAL_CLIENTS"), instanceCount, "LOAD_TOTAL_CLIENTS");
    final int waveDurationS = parsePositiveInt(
        jmeterProps.get("LOAD_WAVE_DURATION_S"),
        parsePositiveInt(jmeterProps.get("DURATION_S"), 30, "DURATION_S"),
        "LOAD_WAVE_DURATION_S");

    final boolean resetBetweenWaves = parseBoolean(
        jmeterProps.get("LOAD_RESET_BETWEEN_WAVES"), false);
    final boolean backgroundReset = parseBoolean(jmeterProps.get("LOAD_BACKGROUND_RESET"), false);

    if (totalClients % instanceCount != 0) {
      throw new AssertionError(
          "LOAD_TOTAL_CLIENTS (" + totalClients + ") must be divisible by "
              + "LOAD_INSTANCE_COUNT (" + instanceCount + ").");
    }
    final int waves = totalClients / instanceCount;

    HttpClient client = createLoadDriverHttpClient();
    if (cleanupBeforeCreate) {
      deleteAllLoadDriverInstances(client, loadDriverBaseUrl);
    }

    List<Integer> createdIds = createLoadDriverInstances(
        client, loadDriverBaseUrl, instanceCount, autoInit, createBody, createBatchSize);
    if (waitReady) {
      Set<String> targetStates = autoInit ? Set.of("READY") : Set.of("CREATED");
      waitForInstancesState(
          client,
          loadDriverBaseUrl,
          createdIds,
          targetStates,
          Duration.ofSeconds(readyTimeoutSeconds),
          Duration.ofMillis(readyPollMs));
    }
    String pathTemplate = resolveLoadPathTemplate(jmeterProps);
    String initPathTemplate = resolveLoadInitPathTemplate(jmeterProps, pathTemplate);
    List<String> initFlow = parseLoadInitFlow(jmeterProps.get("LOAD_INIT_FLOW"));
    int initStepTimeoutSeconds = parsePositiveInt(
        jmeterProps.get("LOAD_INIT_STEP_TIMEOUT_S"), 30, "LOAD_INIT_STEP_TIMEOUT_S");
    if (!initFlow.isEmpty()) {
      runLoadDriverInitFlow(
          client,
          loadDriverBaseUrl,
          createdIds,
          initPathTemplate,
          initFlow,
          Duration.ofSeconds(initStepTimeoutSeconds),
          jmeterProps);
    }
    String proxyPath = resolveLoadProxyPath(jmeterProps);
    boolean precheckEnabled = parseBoolean(
        jmeterProps.get("LOAD_PRECHECK_ENABLED"), true);
    int precheckSampleSize = parsePositiveInt(
        jmeterProps.get("LOAD_PRECHECK_SAMPLE_SIZE"), 1, "LOAD_PRECHECK_SAMPLE_SIZE");
    String precheckMethod = resolveLoadPrecheckMethod(jmeterProps);
    if (precheckEnabled) {
      runLoadDriverPrecheck(
          client,
          loadDriverBaseUrl,
          createdIds,
          proxyPath,
          pathTemplate,
          precheckMethod,
          precheckSampleSize,
          jmeterProps);
    }

    boolean oneInstancePerClient = parseBoolean(
        jmeterProps.get(LOAD_ONE_INSTANCE_PER_THREAD), false);
    int loadThreads = parsePositiveInt(jmeterProps.get("THREADS"), 1, "THREADS");
    int warmupThreads = parseNonNegativeInt(
        jmeterProps.get("WARMUP_THREADS"), 5, "WARMUP_THREADS");
    Path instancePathsFile = prepareInstancePathBinding(
        createdIds,
        proxyPath,
        pathTemplate,
        oneInstancePerClient,
        loadThreads,
        warmupThreads,
        jmeterProps);
    jmeterProps.put("BASE_URL", loadDriverBaseUrl);

    final String smcbManifest = trimToNull(jmeterProps.get(LOAD_SMCB_KEYSTORE_MANIFEST));

    log.info(
        "Load-driver setup done: baseUrl={}, instances={}, autoInit={}, waitReady={}, oneInstancePerClient={}, proxyPath={}, pathTemplate={}, initPathTemplate={}, pathsFile={}",
        loadDriverBaseUrl,
        createdIds.size(),
        autoInit,
        waitReady,
        oneInstancePerClient,
        proxyPath,
        pathTemplate,
        initPathTemplate,
        instancePathsFile);

    // Stash fachdienstUrl before removing LOAD_ props
    final String fachdienstUrlForWaves = trimToNull(jmeterProps.get("LOAD_FACHDIENST_URL"));

    jmeterProps.put("BASE_URL", loadDriverBaseUrl);
    removeLoadDriverControlProperties(jmeterProps);
    // Signal to runJMeterWithPlan that waves already ran JMeter internally
    jmeterProps.put("__WAVES_DONE__", "true");

    // In reset mode: create all instances once upfront, then reset between waves
    List<Integer> resetModeIds = null;
    if (resetBetweenWaves) {
      log.info("Reset mode: creating {} instances once upfront", instanceCount);
      String initialCreateBody = smcbManifest != null
          ? generateLoadCreateBodyFromSmcbKeystoreManifest(
              smcbManifest, fachdienstUrlForWaves, instanceCount, autoInit, 0)
          : createBody;
      resetModeIds = createLoadDriverInstances(
          client, loadDriverBaseUrl, instanceCount, autoInit, initialCreateBody, createBatchSize);
      log.info("Reset mode: {} instances created, paths will be reused across all {} waves",
          resetModeIds.size(), waves);
    }

    // Run waves sequentially
    Path waveInstancePathsFile = null;
    Path mergedJtlOutput = jmeterProps.containsKey("__JTL_OUTPUT__")
        ? Path.of(jmeterProps.get("__JTL_OUTPUT__"))
        : null;
    List<Path> waveJtlOutputs = new ArrayList<>();
    for (int wave = 0; wave < waves; wave++) {
      final long waveStartedNanos = System.nanoTime();
      List<Integer> waveIds;
      long deleteMs = 0L;
      long createMs = 0L;

      if (resetBetweenWaves) {
        // Reset all instances so next /hellozeta triggers full re-auth (nonce + register + token)
        waveIds = resetModeIds;
        if (wave > 0) {
          long resetStartedNanos = System.nanoTime();
          resetLoadDriverInstances(client, loadDriverBaseUrl, waveIds, initPathTemplate,
              Duration.ofSeconds(initStepTimeoutSeconds));
          deleteMs = Duration.ofNanos(System.nanoTime() - resetStartedNanos).toMillis();
          log.info("Wave {}/{}: reset {} instances in {}ms", wave + 1, waves, waveIds.size(), deleteMs);
        } else {
          log.info("Wave {}/{}: first wave, skipping reset (instances freshly created)", wave + 1, waves);
        }
      } else {
        int manifestOffset = wave * instanceCount;
        log.info("Wave {}/{}: creating {} instances (manifest offset={})",
            wave + 1, waves, instanceCount, manifestOffset);

        long deleteStartedNanos = System.nanoTime();
        deleteAllLoadDriverInstances(client, loadDriverBaseUrl);
        deleteMs = Duration.ofNanos(System.nanoTime() - deleteStartedNanos).toMillis();

        String waveCreateBody = smcbManifest != null
            ? generateLoadCreateBodyFromSmcbKeystoreManifest(
                smcbManifest,
                fachdienstUrlForWaves,
                instanceCount,
                autoInit,
                manifestOffset)
            : createBody;

        long createStartedNanos = System.nanoTime();
        waveIds = createLoadDriverInstances(
            client, loadDriverBaseUrl, instanceCount, autoInit, waveCreateBody, createBatchSize);
        createMs = Duration.ofNanos(System.nanoTime() - createStartedNanos).toMillis();
      }

      long waitReadyMs = 0L;
      if (waitReady) {
        Set<String> targetStates = autoInit ? Set.of("READY") : Set.of("CREATED");
        long waitStartedNanos = System.nanoTime();
        waitForInstancesState(client, loadDriverBaseUrl, waveIds, targetStates,
            Duration.ofSeconds(readyTimeoutSeconds), Duration.ofMillis(readyPollMs));
        waitReadyMs = Duration.ofNanos(System.nanoTime() - waitStartedNanos).toMillis();
      }
      long initFlowMs = 0L;
      if (!initFlow.isEmpty()) {
        long initStartedNanos = System.nanoTime();
        runLoadDriverInitFlow(client, loadDriverBaseUrl, waveIds, initPathTemplate,
            initFlow, Duration.ofSeconds(initStepTimeoutSeconds), new HashMap<>(jmeterProps));
        initFlowMs = Duration.ofNanos(System.nanoTime() - initStartedNanos).toMillis();
      }

      // Update instance paths file for this wave (or reuse in reset mode)
      if (!resetBetweenWaves || waveInstancePathsFile == null) {
        if (waveInstancePathsFile != null) {
          deleteInstancePathsFileQuietly(waveInstancePathsFile);
        }
        List<String> wavePaths = buildInstancePaths(waveIds, proxyPath, pathTemplate);
        waveInstancePathsFile = writeInstancePathsFile(wavePaths);
      }
      jmeterProps.put("INSTANCE_PATHS_FILE", waveInstancePathsFile.toString());
      jmeterProps.put("INSTANCE_PATHS_SHARE_MODE", "shareMode.all");
      if (oneInstancePerClient) {
        jmeterProps.put("INSTANCE_PATH_BINDING_MODE", "one_instance_per_thread");
      }

      // Run JMeter for this wave's duration
      final int waveTargetRps = Integer.parseInt(jmeterProps.getOrDefault("TARGET_RPS", "300"));
      Map<String, String> waveProps = new HashMap<>(jmeterProps);
      waveProps.put("DURATION_S", Integer.toString(waveDurationS));
      waveProps.put("WARMUP_S", wave == 0
          ? jmeterProps.getOrDefault("WARMUP_S", "0")
          : "0");
      waveProps.put("WARMUP_THREADS", wave == 0
          ? jmeterProps.getOrDefault("WARMUP_THREADS", "0")
          : "0");

      String planPath = jmeterProps.get("__PLAN_PATH__");
      Path jtlOutput = jmeterProps.containsKey("__JTL_OUTPUT__")
          ? Path.of(jmeterProps.get("__JTL_OUTPUT__").replace(".jtl", "-wave" + (wave + 1) + ".jtl"))
          : null;

      log.info("Wave {}/{}: running JMeter for {}s", wave + 1, waves, waveDurationS);
      long jmeterStartedNanos = System.nanoTime();
      JMeterTestConfig waveConfig = JMeterTestConfig.builder()
          .planTemplate(Path.of(planPath))
          .targetRps(waveTargetRps)
          .baseUrl(loadDriverBaseUrl)
          .parameters(Map.of("-f", ""))
          .jmeterProperties(waveProps)
          .jtlOutput(jtlOutput)
          .htmlOutput(null)
          .propertiesFile(null)
          .build();
      jmeterRunner.runTest(waveConfig);
      long jmeterMs = Duration.ofNanos(System.nanoTime() - jmeterStartedNanos).toMillis();
      if (jtlOutput != null) {
        waveJtlOutputs.add(jtlOutput);
      }

      long waveTotalMs = Duration.ofNanos(System.nanoTime() - waveStartedNanos).toMillis();
      log.info(
          "Wave {}/{} complete: ids={}, deleteMs={}, createMs={}, waitReadyMs={}, initMs={}, jmeterMs={}, totalMs={}",
          wave + 1, waves, waveIds.size(), deleteMs, createMs, waitReadyMs, initFlowMs,
          jmeterMs, waveTotalMs);
    }

    if (mergedJtlOutput != null) {
      mergeWaveJtlOutputs(waveJtlOutputs, mergedJtlOutput);
    }

    boolean cleanupAfterTest = parseBoolean(
        jmeterProps.get("LOAD_DELETE_AFTER_TEST"), true);
    return new LoadDriverContext(
        client,
        loadDriverBaseUrl,
        cleanupAfterTest,
        waveInstancePathsFile,
        backgroundReset ? List.copyOf(createdIds) : null,
        initPathTemplate,
        Duration.ofSeconds(initStepTimeoutSeconds));
  }

  private void resetLoadDriverInstances(
      HttpClient client,
      String loadDriverBaseUrl,
      List<Integer> instanceIds,
      String pathTemplate,
      Duration stepTimeout) throws Exception {
    for (int instanceId : instanceIds) {
      String path = buildLoadInstancePath(instanceId, "/reset", pathTemplate);
      HttpRequest request = HttpRequest.newBuilder(URI.create(loadDriverBaseUrl + path))
          .timeout(stepTimeout)
          .GET()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() > 299) {
        throw new AssertionError(
            "Load-driver reset failed for instanceId=" + instanceId
                + ", path=" + path
                + ", status=" + response.statusCode()
                + ", body=" + sanitizeLogBody(response.body()));
      }
    }
  }

  private void mergeWaveJtlOutputs(List<Path> waveJtlOutputs, Path mergedJtlOutput) throws IOException {
    if (waveJtlOutputs.isEmpty()) {
      return;
    }
    Files.createDirectories(mergedJtlOutput.toAbsolutePath().normalize().getParent());
    try (var writer = Files.newBufferedWriter(mergedJtlOutput, StandardCharsets.UTF_8)) {
      boolean wroteHeader = false;
      for (Path waveJtlOutput : waveJtlOutputs) {
        if (waveJtlOutput == null || !Files.exists(waveJtlOutput)) {
          continue;
        }
        List<String> lines = Files.readAllLines(waveJtlOutput, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
          continue;
        }
        int startIndex = 0;
        if (wroteHeader) {
          startIndex = 1;
        }
        for (int i = startIndex; i < lines.size(); i++) {
          writer.write(lines.get(i));
          writer.newLine();
        }
        wroteHeader = true;
      }
    }
  }

  private HttpClient createLoadDriverHttpClient() throws Exception {
    TrustManager[] trustAll = new TrustManager[] {
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) {
          }

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {
          }

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        }
    };

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustAll, new SecureRandom());
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm(null);

    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .sslContext(sslContext)
        .sslParameters(sslParameters)
        .build();
  }

  private List<Integer> createLoadDriverInstances(
      HttpClient client,
      String loadDriverBaseUrl,
      int instanceCount,
      boolean autoInit,
      String createBody,
      int createBatchSize) throws Exception {
    List<Integer> ids = new ArrayList<>();
    int effectiveBatchSize = Math.max(1, Math.min(createBatchSize, instanceCount));
    JsonNode createBodyRoot = trimToNull(createBody) == null ? null : JSON.readTree(createBody);

    for (int batchStart = 0; batchStart < instanceCount; batchStart += effectiveBatchSize) {
      final long batchStartedNanos = System.nanoTime();
      int batchCount = Math.min(effectiveBatchSize, instanceCount - batchStart);
      String batchBody = buildCreateBodyBatch(createBodyRoot, createBody, batchStart, batchCount, autoInit);

      URI uri = URI.create(loadDriverBaseUrl + "/load/create_instances?count=" + batchCount
          + "&autoInit=" + autoInit);
      HttpRequest request = HttpRequest.newBuilder(uri)
          .header("Content-Type", "application/json")
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(batchBody, StandardCharsets.UTF_8))
          .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() > 299) {
        throw new AssertionError("Load-driver create_instances failed: HTTP "
            + response.statusCode() + " body=" + sanitizeLogBody(response.body()));
      }

      JsonNode root = JSON.readTree(response.body());
      JsonNode idsNode = root.path("ids");
      if (!idsNode.isArray()) {
        throw new AssertionError(
            "Load-driver create_instances response has no ids-array: " + sanitizeLogBody(
                response.body()));
      }

      List<Integer> batchIds = new ArrayList<>();
      for (JsonNode idNode : idsNode) {
        if (idNode.canConvertToInt()) {
          batchIds.add(idNode.asInt());
        }
      }

      int created = root.path("created").asInt(batchIds.size());
      if (batchIds.size() != batchCount || created != batchIds.size()) {
        throw new AssertionError("Load-driver created ids mismatch: expected=" + batchCount
            + ", created=" + created + ", ids=" + batchIds.size());
      }

      ids.addAll(batchIds);
    }

    return ids;
  }

  private String buildCreateBodyBatch(
      JsonNode createBodyRoot,
      String originalBody,
      int batchStart,
      int batchCount,
      boolean autoInit) throws Exception {
    if (createBodyRoot == null) {
      return "";
    }

    JsonNode instancesNode = createBodyRoot.path("instances");
    if (!instancesNode.isArray()) {
      if (batchStart == 0) {
        return originalBody;
      }
      throw new AssertionError(
          "Batched load-driver create requires instances-array in create body.");
    }

    ObjectNode batchRoot = JSON.createObjectNode();
    batchRoot.put("count", batchCount);
    batchRoot.put("autoInit", autoInit);
    ArrayNode batchInstances = batchRoot.putArray("instances");

    for (int i = batchStart; i < batchStart + batchCount; i++) {
      JsonNode instanceNode = instancesNode.get(i);
      if (instanceNode == null) {
        throw new AssertionError(
            "Create body has fewer instance configs than expected. batchStart=" + batchStart
                + ", batchCount=" + batchCount + ", index=" + i);
      }
      batchInstances.add(instanceNode);
    }

    return JSON.writeValueAsString(batchRoot);
  }

  private void waitForInstancesState(
      HttpClient client,
      String loadDriverBaseUrl,
      List<Integer> expectedIds,
      Set<String> targetStates,
      Duration timeout,
      Duration pollInterval) throws Exception {
    Set<String> normalizedTargets = targetStates.stream()
        .map(state -> state.toUpperCase(Locale.ROOT))
        .collect(Collectors.toSet());
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    Set<Integer> expectedSet = new HashSet<>(expectedIds);
    while (System.nanoTime() < deadlineNanos) {
      Map<Integer, String> states = getLoadDriverInstanceStates(client, loadDriverBaseUrl);
      Set<Integer> matching = expectedSet.stream()
          .filter(id -> normalizedTargets.contains(states.getOrDefault(id, "").toUpperCase(Locale.ROOT)))
          .collect(Collectors.toSet());
      Set<Integer> failed = expectedSet.stream()
          .filter(id -> "FAILED".equalsIgnoreCase(states.get(id)))
          .collect(Collectors.toSet());

      if (!failed.isEmpty()) {
        throw new AssertionError(
            "Load-driver instances failed while waiting for states " + normalizedTargets
                + ". failedIds=" + failed + ", states="
                + states);
      }
      if (matching.size() == expectedSet.size()) {
        return;
      }

      Thread.sleep(Math.max(100, pollInterval.toMillis()));
    }
    Map<Integer, String> finalStates = getLoadDriverInstanceStates(client, loadDriverBaseUrl);
    throw new AssertionError(
        "Timeout while waiting for instances in states " + normalizedTargets + ". expected="
            + expectedSet + ", states=" + finalStates);
  }

  private Map<Integer, String> getLoadDriverInstanceStates(
      HttpClient client, String loadDriverBaseUrl) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(loadDriverBaseUrl + "/load/list_instances"))
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() > 299) {
      throw new AssertionError("Load-driver list_instances failed: HTTP " + response.statusCode());
    }

    JsonNode root = JSON.readTree(response.body());
    Map<Integer, String> states = new HashMap<>();
    if (root.isArray()) {
      for (JsonNode node : root) {
        readInstanceState(node, null, states);
      }
      return states;
    }

    JsonNode instancesNode = root.path("instances");
    if (instancesNode.isArray()) {
      for (JsonNode node : instancesNode) {
        readInstanceState(node, null, states);
      }
      return states;
    }

    if (root.isObject()) {
      root.fields().forEachRemaining(entry -> readInstanceState(entry.getValue(), entry.getKey(), states));
    }
    return states;
  }

  private void readInstanceState(JsonNode node, String fallbackId, Map<Integer, String> states) {
    if (node == null || node.isNull()) {
      return;
    }
    int id = node.path("id").asInt(Integer.MIN_VALUE);
    if (id == Integer.MIN_VALUE) {
      id = node.path("instanceIndex").asInt(Integer.MIN_VALUE);
    }
    if (id == Integer.MIN_VALUE && fallbackId != null) {
      try {
        id = Integer.parseInt(fallbackId);
      } catch (NumberFormatException ignored) {
        return;
      }
    }
    if (id == Integer.MIN_VALUE) {
      return;
    }

    String state = node.path("state").asText("");
    if (state.isBlank() && node.isTextual()) {
      state = node.asText("");
    }
    states.put(id, state);
  }

  private void deleteAllLoadDriverInstances(HttpClient client, String loadDriverBaseUrl) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(loadDriverBaseUrl + "/load/delete_instances"))
          .timeout(Duration.ofSeconds(30))
          .DELETE()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      log.info("Load-driver cleanup status={}", response.statusCode());
    } catch (Exception e) {
      log.warn("Load-driver cleanup failed: {}", e.getMessage());
    }
  }

  private String resolveLoadProxyPath(Map<String, String> jmeterProps) {
    String explicitPath = trimToNull(jmeterProps.get("LOAD_PROXY_PATH"));
    if (explicitPath != null) {
      return ensureStartsWithSlash(explicitPath);
    }
    return DEFAULT_GUARD_TOKEN_PATH;
  }

  private String resolveLoadPathTemplate(Map<String, String> jmeterProps) {
    String template = trimToNull(jmeterProps.get("LOAD_PATH_TEMPLATE"));
    if (template == null) {
      return DEFAULT_LOAD_PATH_TEMPLATE;
    }
    validateLoadPathTemplate(template, "LOAD_PATH_TEMPLATE");
    return template;
  }

  private String resolveLoadInitPathTemplate(
      Map<String, String> jmeterProps, String defaultPathTemplate) {
    String template = trimToNull(jmeterProps.get("LOAD_INIT_PATH_TEMPLATE"));
    if (template == null) {
      return defaultPathTemplate;
    }
    validateLoadPathTemplate(template, "LOAD_INIT_PATH_TEMPLATE");
    return template;
  }

  private void validateLoadPathTemplate(String template, String propertyName) {
    if (!template.contains("{id}")) {
      throw new AssertionError(
          propertyName + " must contain '{id}', e.g. /load/{id}{proxyPath}");
    }
    if (!template.contains("{proxyPath}")) {
      throw new AssertionError(
          propertyName + " must contain '{proxyPath}', e.g. /load/{id}{proxyPath}");
    }
  }

  private String resolveLoadPrecheckMethod(Map<String, String> jmeterProps) {
    String explicit = trimToNull(jmeterProps.get("LOAD_PRECHECK_METHOD"));
    if (explicit != null) {
      return explicit.toUpperCase(Locale.ROOT);
    }
    String httpMethod = trimToNull(jmeterProps.get("HTTP_METHOD"));
    return httpMethod == null ? "GET" : httpMethod.toUpperCase(Locale.ROOT);
  }

  private List<String> parseLoadInitFlow(String rawFlow) {
    String normalized = trimToNull(rawFlow);
    if (normalized == null) {
      return List.of();
    }
    return Stream.of(normalized.split(","))
        .map(this::trimToNull)
        .filter(step -> step != null)
        .map(this::ensureStartsWithSlash)
        .collect(Collectors.toList());
  }

  private void runLoadDriverInitFlow(
      HttpClient client,
      String loadDriverBaseUrl,
      List<Integer> createdIds,
      String pathTemplate,
      List<String> initFlow,
      Duration stepTimeout,
      Map<String, String> jmeterProps) throws Exception {
    log.info("Executing load-driver init flow {} for {} instances", initFlow, createdIds.size());
    for (int instanceId : createdIds) {
      for (String initPath : initFlow) {
        String path = buildLoadInstancePath(instanceId, initPath, pathTemplate);
        URI uri = URI.create(loadDriverBaseUrl + path);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).timeout(stepTimeout);
        applyOptionalHeaders(requestBuilder, jmeterProps);

        HttpResponse<String> response = client.send(
            requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
          throw new AssertionError(
              "Load-driver init flow failed for instanceId=" + instanceId
                  + ", path=" + path
                  + ", status=" + response.statusCode()
                  + ", body=" + sanitizeLogBody(response.body()));
        }
      }
    }
  }

  private void runLoadDriverPrecheck(
      HttpClient client,
      String loadDriverBaseUrl,
      List<Integer> createdIds,
      String proxyPath,
      String pathTemplate,
      String method,
      int sampleSize,
      Map<String, String> jmeterProps) throws Exception {
    Map<Integer, String> initialStates = safeLoadDriverStates(client, loadDriverBaseUrl);
    if (!initialStates.isEmpty()) {
      log.info(
          "Load-driver precheck list_instances: {}",
          summarizeLoadDriverStates(createdIds, initialStates));
    }

    int checks = Math.min(sampleSize, createdIds.size());
    for (int i = 0; i < checks; i++) {
      int instanceId = createdIds.get(i);
      String path = buildLoadInstancePath(instanceId, proxyPath, pathTemplate);
      URI uri = URI.create(loadDriverBaseUrl + path);

      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(30));
      applyOptionalHeaders(requestBuilder, jmeterProps);
      HttpRequest request = buildPrecheckRequest(requestBuilder, method);

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() > 299) {
        String hint = buildLoadDriverPrecheckHint(
            response.statusCode(), loadDriverBaseUrl, path, jmeterProps);
        Map<Integer, String> currentStates = safeLoadDriverStates(client, loadDriverBaseUrl);
        String stateInfo = currentStates.isEmpty()
            ? "unavailable"
            : summarizeLoadDriverStates(createdIds, currentStates);
        throw new AssertionError(
            "Load-driver precheck failed for instanceId=" + instanceId
                + ", method=" + method
                + ", path=" + path
                + ", status=" + response.statusCode()
                + ", body=" + sanitizeLogBody(response.body())
                + ", list_instances=" + stateInfo
                + hint);
      }
    }
  }

  private Map<Integer, String> safeLoadDriverStates(HttpClient client, String loadDriverBaseUrl) {
    try {
      return getLoadDriverInstanceStates(client, loadDriverBaseUrl);
    } catch (Exception e) {
      log.warn("Load-driver list_instances during precheck failed: {}", e.getMessage());
      return Map.of();
    }
  }

  private String summarizeLoadDriverStates(List<Integer> createdIds, Map<Integer, String> states) {
    return createdIds.stream()
        .map(id -> id + ":" + states.getOrDefault(id, "MISSING"))
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private String buildLoadDriverPrecheckHint(
      int statusCode,
      String loadDriverBaseUrl,
      String path,
      Map<String, String> jmeterProps) {
    if (statusCode != 403 || !path.startsWith("/loaddriver-api/")) {
      return "";
    }
    boolean likelyExternalIngress =
        loadDriverBaseUrl.startsWith("https://")
            || loadDriverBaseUrl.contains("zeta-kind.local");
    if (likelyExternalIngress) {
      return " (hint: '/loaddriver-api/*' returned 403 via ingress; "
          + "verify load-driver route/security config and instance state)";
    }
    return " (hint: '/loaddriver-api/*' returned 403; "
        + "verify load-driver route/security config and instance state)";
  }

  private HttpRequest buildPrecheckRequest(HttpRequest.Builder requestBuilder, String method) {
    if (!"GET".equals(method)) {
      throw new AssertionError(
          "Unsupported LOAD_PRECHECK_METHOD/HTTP_METHOD for precheck: " + method
              + ". Supported: GET");
    }
    return requestBuilder.GET().build();
  }

  private void applyOptionalHeaders(HttpRequest.Builder requestBuilder, Map<String, String> jmeterProps) {
    String contentType = trimToNull(jmeterProps.get("CONTENT_TYPE"));
    if (contentType != null) {
      requestBuilder.header("Content-Type", contentType);
    }
    addOptionalHeaderPair(requestBuilder, jmeterProps, "HEADER1_NAME", "HEADER1_VALUE");
    addOptionalHeaderPair(requestBuilder, jmeterProps, "HEADER2_NAME", "HEADER2_VALUE");
    addOptionalHeaderPair(requestBuilder, jmeterProps, "HEADER3_NAME", "HEADER3_VALUE");
  }

  private void addOptionalHeaderPair(
      HttpRequest.Builder requestBuilder,
      Map<String, String> jmeterProps,
      String nameKey,
      String valueKey) {
    String headerName = trimToNull(jmeterProps.get(nameKey));
    if (headerName == null) {
      return;
    }
    String headerValue = trimToNull(jmeterProps.get(valueKey));
    requestBuilder.header(headerName, headerValue == null ? "" : headerValue);
  }

  private String generateLoadCreateBodyIfNeeded(
      Map<String, String> jmeterProps, int instanceCount, boolean autoInit)
      throws IOException {
    String deprecatedBody = trimToNull(jmeterProps.get("LOAD_CREATE_BODY"));
    if (deprecatedBody != null) {
      throw new AssertionError(
          "LOAD_CREATE_BODY is not supported anymore. "
              + "Use -JLOAD_CREATE_BODY_INLINE=true together with "
              + "-JLOAD_FACHDIENST_URL, -JLOAD_COMMON_KEYSTORE_B64 (or "
              + "-JLOAD_COMMON_KEYSTORE_B64_FILE), -JLOAD_COMMON_KEY_ALIAS "
              + "and -JLOAD_COMMON_KEY_PASSWORD.");
    }

    String deprecatedBodyFile = trimToNull(jmeterProps.get("LOAD_CREATE_BODY_FILE"));
    if (deprecatedBodyFile != null) {
      throw new AssertionError(
          "LOAD_CREATE_BODY_FILE is not supported anymore. "
              + "Use -JLOAD_CREATE_BODY_INLINE=true together with "
              + "-JLOAD_FACHDIENST_URL, -JLOAD_COMMON_KEYSTORE_B64 (or "
              + "-JLOAD_COMMON_KEYSTORE_B64_FILE), -JLOAD_COMMON_KEY_ALIAS "
              + "and -JLOAD_COMMON_KEY_PASSWORD.");
    }

    boolean inlineEnabled = parseBoolean(jmeterProps.get(LOAD_CREATE_BODY_INLINE), false);
    if (!inlineEnabled) {
      // Default behavior: send create_instances without body unless explicitly configured.
      return "";
    }

    String fachdienstUrl = trimToNull(jmeterProps.get("LOAD_FACHDIENST_URL"));
    String smcbManifestPath = trimToNull(jmeterProps.get(LOAD_SMCB_KEYSTORE_MANIFEST));
    if (smcbManifestPath != null) {
      return generateLoadCreateBodyFromSmcbKeystoreManifest(
          smcbManifestPath,
          fachdienstUrl,
          instanceCount,
          autoInit,
          0);
    }
    String smcbPoolDir = trimToNull(jmeterProps.get(LOAD_SMCB_KEYSTORE_POOL_DIR));
    if (smcbPoolDir != null) {
      return generateLoadCreateBodyFromSmcbKeystorePool(
          smcbPoolDir,
          fachdienstUrl,
          resolveSmcbKeystorePoolPassword(jmeterProps),
          instanceCount,
          autoInit);
    }

    String keystoreAlias = trimToNull(jmeterProps.get("LOAD_COMMON_KEY_ALIAS"));
    String keystorePassword = trimToNull(jmeterProps.get("LOAD_COMMON_KEY_PASSWORD"));
    String keystoreB64 = resolveLoadCommonKeystoreB64(jmeterProps);

    if (fachdienstUrl == null || keystoreAlias == null || keystorePassword == null
        || keystoreB64 == null) {
      throw new AssertionError(
          "LOAD_CREATE_BODY_INLINE=true requires all of "
              + "-JLOAD_FACHDIENST_URL, -JLOAD_COMMON_KEYSTORE_B64 (or "
              + "-JLOAD_COMMON_KEYSTORE_B64_FILE), -JLOAD_COMMON_KEY_ALIAS, "
              + "-JLOAD_COMMON_KEY_PASSWORD.");
    }

    StringBuilder body = new StringBuilder(1024 + instanceCount * 256);
    body.append("{\"count\":").append(instanceCount)
        .append(",\"autoInit\":").append(autoInit)
        .append(",\"instances\":[");
    for (int i = 0; i < instanceCount; i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append("{")
          .append("\"fachdienstUrl\":\"").append(escapeJson(fachdienstUrl)).append("\",")
          .append("\"smbKeystoreB64\":\"").append(escapeJson(keystoreB64)).append("\",")
          .append("\"smbKeystoreAlias\":\"").append(escapeJson(keystoreAlias)).append("\",")
          .append("\"smbKeystorePassword\":\"").append(escapeJson(keystorePassword)).append("\"")
          .append("}");
    }
    body.append("]}");
    return body.toString();
  }

  private String generateLoadCreateBodyFromSmcbKeystoreManifest(
      String manifestPath,
      String fachdienstUrl,
      int instanceCount,
      boolean autoInit,
      int startIndex) throws IOException {
    if (fachdienstUrl == null) {
      throw new AssertionError(
          "LOAD_CREATE_BODY_INLINE=true with -J" + LOAD_SMCB_KEYSTORE_MANIFEST
              + " requires -JLOAD_FACHDIENST_URL.");
    }

    var resolvedManifestPath = Path.of(manifestPath).toAbsolutePath().normalize();
    if (!Files.exists(resolvedManifestPath)) {
      throw new AssertionError("SMC-B keystore manifest does not exist: " + resolvedManifestPath);
    }

    var entries = loadSmcbKeystoreManifestEntries(resolvedManifestPath, startIndex, instanceCount);
    if (entries.size() < instanceCount) {
      throw new AssertionError(
          "SMC-B keystore manifest " + resolvedManifestPath + " contains only "
              + entries.size() + " usable entries, but " + instanceCount
              + " instances were requested.");
    }

    var body = new StringBuilder(2048 + instanceCount * 4096);
    body.append("{\"count\":").append(instanceCount)
        .append(",\"autoInit\":").append(autoInit)
        .append(",\"instances\":[");
    for (int i = 0; i < instanceCount; i++) {
      if (i > 0) {
        body.append(',');
      }
      var entry = entries.get(i);
      body.append("{")
          .append("\"fachdienstUrl\":\"").append(escapeJson(fachdienstUrl)).append("\",")
          .append("\"smbKeystoreB64\":\"")
          .append(escapeJson(entry.keystoreB64())).append("\",")
          .append("\"smbKeystoreAlias\":\"")
          .append(escapeJson(entry.keystoreAlias())).append("\",")
          .append("\"smbKeystorePassword\":\"")
          .append(escapeJson(entry.keystorePassword())).append("\"")
          .append("}");
    }
    body.append("]}");
    return body.toString();
  }

  private String generateLoadCreateBodyFromSmcbKeystorePool(
      String smcbKeystorePoolDir,
      String fachdienstUrl,
      String keystorePassword,
      int instanceCount,
      boolean autoInit) throws IOException {
    if (fachdienstUrl == null || keystorePassword == null) {
      throw new AssertionError(
          "LOAD_CREATE_BODY_INLINE=true with -J" + LOAD_SMCB_KEYSTORE_POOL_DIR
              + " requires -JLOAD_FACHDIENST_URL and -J" + LOAD_SMCB_KEYSTORE_PASSWORD + ".");
    }

    List<SmcbPoolEntry> poolEntries = loadSmcbKeystorePoolEntries(
        Path.of(smcbKeystorePoolDir), keystorePassword, instanceCount);
    if (poolEntries.size() < instanceCount) {
      throw new AssertionError("SMC-B keystore pool " + smcbKeystorePoolDir + " contains only "
          + poolEntries.size() + " usable entries, but " + instanceCount + " instances were requested.");
    }

    StringBuilder body = new StringBuilder(2048 + instanceCount * 4096);
    body.append("{\"count\":").append(instanceCount)
        .append(",\"autoInit\":").append(autoInit)
        .append(",\"instances\":[");
    for (int i = 0; i < instanceCount; i++) {
      if (i > 0) {
        body.append(',');
      }
      SmcbPoolEntry entry = poolEntries.get(i);
      body.append("{")
          .append("\"fachdienstUrl\":\"").append(escapeJson(fachdienstUrl)).append("\",")
          .append("\"smbKeystoreB64\":\"").append(escapeJson(entry.keystoreB64())).append("\",")
          .append("\"smbKeystoreAlias\":\"").append(escapeJson(entry.alias())).append("\",")
          .append("\"smbKeystorePassword\":\"").append(escapeJson(keystorePassword)).append("\"")
          .append("}");
    }
    body.append("]}");
    return body.toString();
  }

  private List<SmcbManifestEntry> loadSmcbKeystoreManifestEntries(
      Path manifestPath,
      int startIndex,
      int instanceCount) throws IOException {
    var lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      throw new AssertionError("SMC-B keystore manifest is empty: " + manifestPath);
    }

    var header = lines.get(0).split("\t", -1);
    var stemIndex = findManifestColumn(header, "stem", manifestPath);
    var keystoreB64Index = findManifestColumn(header, "keystore_b64", manifestPath);
    var passwordIndex = findManifestColumn(header, "keystore_password", manifestPath);
    var aliasIndex = findManifestColumn(header, "keystore_alias", manifestPath);
    findManifestColumn(header, "store_type", manifestPath);

    var manifestDir = manifestPath.toAbsolutePath().normalize().getParent();
    var repoRoot = manifestDir == null ? null : manifestDir.getParent();
    if (repoRoot == null) {
      throw new AssertionError("Cannot resolve repository root for manifest: " + manifestPath);
    }

    var entries = new ArrayList<SmcbManifestEntry>(instanceCount);
    for (int i = startIndex + 1; i < lines.size() && entries.size() < instanceCount; i++) {
      var line = lines.get(i).trim();
      if (line.isEmpty()) {
        continue;
      }
      var values = line.split("\t", -1);
      var keystorePath = repoRoot.resolve(values[keystoreB64Index].trim())
          .toAbsolutePath()
          .normalize();
      if (!Files.exists(keystorePath)) {
        throw new AssertionError("SMC-B keystore payload does not exist: " + keystorePath);
      }
      entries.add(new SmcbManifestEntry(
          values[stemIndex].trim(),
          Files.readString(keystorePath, StandardCharsets.UTF_8).trim(),
          values[passwordIndex].trim(),
          values[aliasIndex].trim()));
    }
    return entries;
  }

  private int findManifestColumn(String[] header, String requiredColumn, Path manifestPath) {
    for (int i = 0; i < header.length; i++) {
      if (requiredColumn.equalsIgnoreCase(header[i].trim())) {
        return i;
      }
    }
    throw new AssertionError(
        "SMC-B keystore manifest " + manifestPath + " is missing required column: "
            + requiredColumn);
  }

  private String resolveSmcbKeystorePoolPassword(Map<String, String> jmeterProps) {
    String password = trimToNull(jmeterProps.get(LOAD_SMCB_KEYSTORE_PASSWORD));
    if (password != null) {
      return password;
    }
    return trimToNull(jmeterProps.get("LOAD_COMMON_KEY_PASSWORD"));
  }

  private List<SmcbPoolEntry> loadSmcbKeystorePoolEntries(
      Path poolDir, String keystorePassword, int requiredCount) throws IOException {
    if (!Files.isDirectory(poolDir)) {
      throw new AssertionError("SMC-B keystore pool directory does not exist: " + poolDir);
    }

    List<Path> keystoreFiles;
    try (Stream<Path> stream = Files.list(poolDir)) {
      keystoreFiles = stream
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".p12"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }

    List<SmcbPoolEntry> entries = new ArrayList<>(Math.min(keystoreFiles.size(), requiredCount));
    for (Path keystoreFile : keystoreFiles) {
      String fileName = keystoreFile.getFileName().toString();
      String stem = fileName.substring(0, fileName.length() - 4);
      String alias = stem.toLowerCase(Locale.ROOT);
      entries.add(new SmcbPoolEntry(
          stem,
          alias,
          Base64.getEncoder().encodeToString(Files.readAllBytes(keystoreFile))));
      if (entries.size() >= requiredCount) {
        break;
      }
    }
    return entries;
  }

  private record SmcbPoolEntry(String stem, String alias, String keystoreB64) {
  }

  private record SmcbManifestEntry(
      String stem,
      String keystoreB64,
      String keystorePassword,
      String keystoreAlias) {
  }

  private String resolveLoadCommonKeystoreB64(Map<String, String> jmeterProps) throws IOException {
    String inlineB64 = trimToNull(jmeterProps.get("LOAD_COMMON_KEYSTORE_B64"));
    if (inlineB64 != null) {
      return inlineB64;
    }

    String b64File = trimToNull(jmeterProps.get("LOAD_COMMON_KEYSTORE_B64_FILE"));
    if (b64File == null) {
      return null;
    }

    Path path = Path.of(b64File);
    if (!Files.exists(path)) {
      throw new AssertionError("LOAD_COMMON_KEYSTORE_B64_FILE does not exist: " + path);
    }

    String content = Files.readString(path, StandardCharsets.UTF_8).trim();
    if (content.isEmpty()) {
      throw new AssertionError("LOAD_COMMON_KEYSTORE_B64_FILE is empty: " + path);
    }
    return content;
  }

  private int resolveExpectedInstanceCount(
      Map<String, String> jmeterProps, String createBody, int configuredCount) throws IOException {
    if (jmeterProps.containsKey("LOAD_INSTANCE_COUNT")) {
      return configuredCount;
    }
    try {
      JsonNode node = JSON.readTree(createBody);
      int bodyCount = node.path("count").asInt(configuredCount);
      return bodyCount > 0 ? bodyCount : configuredCount;
    } catch (Exception ignored) {
      return configuredCount;
    }
  }

  private void removeLoadDriverControlProperties(Map<String, String> jmeterProps) {
    Set<String> keys = new HashSet<>(jmeterProps.keySet());
    for (String key : keys) {
      if (key.startsWith("LOAD_")) {
        jmeterProps.remove(key);
      }
    }
  }

  private String buildLoadInstancePath(int instanceId, String proxyPath, String pathTemplate) {
    String path = pathTemplate
        .replace("{id}", Integer.toString(instanceId))
        .replace("{proxyPath}", ensureStartsWithSlash(proxyPath));
    return path.startsWith("/") ? path : "/" + path;
  }

  private Path prepareInstancePathBinding(
      List<Integer> createdIds,
      String proxyPath,
      String pathTemplate,
      boolean oneInstancePerClient,
      int loadThreads,
      int warmupThreads,
      Map<String, String> jmeterProps) throws IOException {
    List<String> paths = buildInstancePaths(createdIds, proxyPath, pathTemplate);
    int requiredThreads = Math.max(loadThreads, warmupThreads);
    if (oneInstancePerClient && requiredThreads > paths.size()) {
      throw new AssertionError(
          "LOAD_ONE_INSTANCE_PER_THREAD=true requires THREADS and WARMUP_THREADS <= "
              + "LOAD_INSTANCE_COUNT. threads=" + loadThreads
              + ", warmupThreads=" + warmupThreads
              + ", instances=" + paths.size());
    }

    Path file = writeInstancePathsFile(paths);
    jmeterProps.put("INSTANCE_PATHS_FILE", file.toString());
    jmeterProps.put("INSTANCE_PATHS_SHARE_MODE", "shareMode.all");
    if (oneInstancePerClient) {
      jmeterProps.put("INSTANCE_PATH_BINDING_MODE", "one_instance_per_thread");
    }
    return file;
  }

  private List<String> buildInstancePaths(
      List<Integer> createdIds, String proxyPath, String pathTemplate) {
    if (createdIds.isEmpty()) {
      throw new AssertionError("No load-driver instance IDs available to build request paths.");
    }
    return createdIds.stream()
        .map(id -> buildLoadInstancePath(id, proxyPath, pathTemplate))
        .collect(Collectors.toList());
  }

  private Path writeInstancePathsFile(List<String> paths) throws IOException {
    Path file = Files.createTempFile("jmeter-instance-paths-", ".csv");
    Files.write(file, paths, StandardCharsets.UTF_8);
    return file;
  }

  private void deleteInstancePathsFileQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      if (Files.isDirectory(path)) {
        try (Stream<Path> walk = Files.walk(path)) {
          walk.sorted(Comparator.reverseOrder()).forEach(current -> {
            try {
              Files.deleteIfExists(current);
            } catch (IOException e) {
              log.warn("Could not delete temporary instance paths entry '{}': {}",
                  current, e.getMessage());
            }
          });
        }
        return;
      }
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete temporary instance paths file '{}': {}", path, e.getMessage());
    }
  }

  private String ensureStartsWithSlash(String value) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      return "/";
    }
    return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
  }

  private String normalizeBaseUrl(String baseUrl) {
    String trimmed = trimToNull(baseUrl);
    if (trimmed == null) {
      throw new AssertionError("LOAD_DRIVER_BASE_URL is required but missing");
    }
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  private String requireProperty(Map<String, String> jmeterProps, String key) {
    String value = trimToNull(jmeterProps.get(key));
    if (value == null) {
      throw new AssertionError(key + " is required but missing.");
    }
    return value;
  }

  private int parsePositiveInt(String value, int fallback, String propertyName) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(normalized);
      if (parsed < 1) {
        throw new AssertionError(propertyName + " must be >= 1 but was " + normalized);
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new AssertionError(propertyName + " must be integer but was " + normalized);
    }
  }

  private int parseNonNegativeInt(String value, int fallback, String propertyName) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(normalized);
      if (parsed < 0) {
        throw new AssertionError(propertyName + " must be >= 0 but was " + normalized);
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new AssertionError(propertyName + " must be integer but was " + normalized);
    }
  }

  private boolean parseBoolean(String value, boolean fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : Boolean.parseBoolean(normalized);
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String escapeJson(String value) {
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private String sanitizeLogBody(String body) {
    if (body == null) {
      return "";
    }
    String compact = body.replace("\n", " ").replace("\r", " ");
    return compact.length() > 1000 ? compact.substring(0, 1000) + "..." : compact;
  }

  private String resolveTigerPlaceholders(String value) {
    if (value == null || value.isEmpty() || !value.contains("${")) {
      return value;
    }
    try {
      return TigerGlobalConfiguration.resolvePlaceholders(value);
    } catch (Exception e) {
      log.debug("Could not resolve Tiger placeholders for '{}': {}", value, e.getMessage());
      return value;
    }
  }

  /**
   * Summarizes a JTL file into a CSV summary.
   */
  @Dann("erstelle die JMeter-Zusammenfassung aus {string} nach {string}")
  @Then("create the JMeter summary from {string} to {string}")
  public void summarizeJtl(String jtlPath, String summaryPath) throws IOException {
    log.info("Creating JMeter summary: {} -> {}", jtlPath, summaryPath);
    jtlSummarizer.summarize(Path.of(jtlPath), Path.of(summaryPath));
  }

  /**
   * Displays summary metrics from CSV in logs.
   */
  @Und("zeige die Kennzahlen aus {string} an")
  @And("show the metrics from {string}")
  public void showSummaryMetrics(String csvPath) throws IOException {
    log.warn("Displaying metrics from: {}", csvPath);
    try (Stream<String> lines = Files.lines(Path.of(csvPath), StandardCharsets.UTF_8)) {
      lines.forEach(line -> {
        // Console appender in tests is WARN by default (see logback-test.xml).
        log.warn("[SUMMARY] {}", line);
      });
    }
  }

  /**
   * Asserts that p95 latency (ms) of a label is ≤ threshold.
   */
  @Und("stelle sicher, dass in {string} das Label {string} p95 <= {int} ms hat")
  @And("ensure in {string} the label {string} has p95 <= {int} ms")
  public void assertJMeterLabelP95(String summaryPath, String requestedLabel, Integer maxMs)
      throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, "p95_ms", maxMs.doubleValue(), "<=");
  }

  /**
   * Asserts that errorRate (%) of a label is ≤ threshold. If errorRate is 0..1, it is normalized to
   * percent.
   */
  @Und("stelle sicher, dass in {string} das Label {string} eine Fehlerquote <= {double} hat")
  @And("ensure in {string} the label {string} has an error rate <= {double}")
  public void assertJMeterLabelErrorRate(String summaryPath, String requestedLabel,
      Double maxErrorRate) throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, "errorRate", maxErrorRate, "<=");
  }

  /**
   * Asserts that a metric of a label is ≤ threshold.
   */
  @Dann("stelle sicher, dass im JMeter-Summary {string} das Label {string} {jmeterMetric} <= {double}")
  @Then("ensure that in the JMeter summary {string} the label {string} has {jmeterMetric} <= {double}")
  public void assertJMeterSummaryLe(String summaryPath, String requestedLabel, String metric,
      double threshold) throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, metric, threshold, "<=");
  }

  /**
   * Asserts that a metric of a label is ≥ threshold.
   */
  @Dann("stelle sicher, dass im JMeter-Summary {string} das Label {string} {jmeterMetric} >= {double}")
  @Then("ensure that in the JMeter summary {string} the label {string} has {jmeterMetric} >= {double}")
  public void assertJMeterSummaryGe(String summaryPath, String requestedLabel, String metric,
      double threshold) throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, metric, threshold, ">=");
  }

  /**
   * Asserts that a metric of a label is > threshold. The threshold may be a literal number or a
   * Tiger placeholder expression such as {@code ${jtargetRps}}.
   */
  @Dann("^stelle sicher, dass im JMeter-Summary \"([^\"]*)\" das Label \"([^\"]*)\" (errorRate|rps|avg_ms|p\\d{1,3}_ms|max_ms|min_ms) > (.+)$")
  @Then("^ensure that in the JMeter summary \"([^\"]*)\" the label \"([^\"]*)\" has (errorRate|rps|avg_ms|p\\d{1,3}_ms|max_ms|min_ms) > (.+)$")
  public void assertJMeterSummaryGt(String summaryPath, String requestedLabel, String metric,
      String thresholdExpression) throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, metric,
        parseThresholdExpression(thresholdExpression), ">");
  }

  /**
   * Loads CSV, resolves label (fuzzy), reads metric, normalizes errorRate if needed, and applies
   * the comparison.
   *
   * @param summaryPath    path to summary CSV
   * @param requestedLabel label name to check
   * @param metricName     metric column name
   * @param threshold      expected threshold
   * @param operator       comparison operator ("<=", "<", ">=", ">")
   */
  private void assertJMeterSummaryMetric(String summaryPath, String requestedLabel,
      String metricName,
      double threshold, String operator) throws IOException {
    CsvData csv = CsvUtils.readCsv(Path.of(summaryPath));

    // Try to match label exactly or approximately
    String effectiveLabel = CsvUtils.resolveSummaryLabel(csv.getColumn("label"), requestedLabel);

    if (effectiveLabel == null) {
      var ex = new AssertionError(
          "Label not found in summary: '" + requestedLabel + "'. Available: " + csv.getColumn(
              "label"));
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      return;
    }

    // Retrieve row for label
    CsvRow labelRow = csv.findRow("label", effectiveLabel);
    if (labelRow == null) {
      var ex = new AssertionError("Row not found for label: " + effectiveLabel);
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      return;
    }

    // Read metric value
    String rawValue = labelRow.get(metricName);
    if (rawValue == null) {
      var ex = new AssertionError("Metric column '" + metricName + "' not found in summary");
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      return;
    }

    double observed;
    try {
      observed = Double.parseDouble(rawValue.trim());
    } catch (NumberFormatException e) {
      var ex = new AssertionError("Invalid numeric value for " + metricName + ": '" + rawValue + "'");
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      return;
    }

    // Normalize errorRate if expressed as fraction
    if ("errorRate".equals(metricName) && observed <= 1.0) {
      observed *= 100.0;
    }

    boolean passed = switch (operator) {
      case "<=" -> observed <= threshold;
      case "<" -> observed < threshold;
      case ">=" -> observed >= threshold;
      case ">" -> observed > threshold;
      default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
    };

    log.warn("[ASSERT JMETER] label='{}' {} {} {} {} -> {}",
        requestedLabel, metricName, observed, operator, threshold, passed ? "PASS" : "FAIL");

    if (!passed) {
      var ex = new AssertionError(String.format(Locale.ROOT,
          "%s(%s) = %.3f %s %.3f (effective label: %s)",
          metricName, requestedLabel, observed,
          invertComparisonOperator(operator), threshold, effectiveLabel));
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
    }
  }

  /**
   * Returns the inverse comparison symbol for failed-assert messages.
   *
   * @param operator expected comparison operator
   * @return inverse operator that describes the failed relation
   */
  private String invertComparisonOperator(String operator) {
    return switch (operator) {
      case "<=" -> ">";
      case "<" -> ">=";
      case ">=" -> "<";
      case ">" -> "<=";
      default -> operator;
    };
  }

  /**
   * Resolves a threshold expression to a double. Supports Tiger placeholders and decimal commas.
   *
   * @param thresholdExpression literal number or Tiger placeholder expression
   * @return parsed threshold as double
   */
  private double parseThresholdExpression(String thresholdExpression) {
    String resolved = resolveTigerPlaceholders(thresholdExpression);
    return Double.parseDouble(resolved.trim().replace(',', '.'));
  }

  private record LoadDriverContext(
      HttpClient client,
      String baseUrl,
      boolean cleanupAfterTest,
      Path instancePathsFile,
      List<Integer> backgroundResetIds,
      String backgroundResetPathTemplate,
      Duration backgroundResetStepTimeout) {
  }
}
