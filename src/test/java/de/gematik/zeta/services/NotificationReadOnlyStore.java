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
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only observations of the scenario-owned notification processing records.
 */
public final class NotificationReadOnlyStore {
  private static final ObjectMapper JSON = new ObjectMapper();

  private NotificationReadOnlyStore() {}

  /**
   * Reads storage IDs and all indexed columns in one read-only snapshot without payloads or secrets.
   *
   * @return storage IDs and index column names of the configured notification database
   */
  public static JsonNode indexSnapshot() {
    var service = ZetaDeploymentConfiguration.getServiceInstance();
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    var pods = service.executeKubectlCommand(false, "-n", namespace, "get", "pods", "-l",
        config("testdata.ccNotification.databasePodSelector"), "-o", "name");
    var names = pods.stdout().lines().filter(line -> !line.isBlank()).toList();
    require(pods.exitCode() == 0 && names.size() == 1, "Expected one notification database");
    var sql = """
        BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
        SET LOCAL statement_timeout='3s';
        SELECT json_build_object(
          'ids', (SELECT coalesce(json_agg(id ORDER BY id), '[]') FROM storage_record),
          'indexes', (SELECT json_agg(json_build_object('primary', i.indisprimary,
            'valid', i.indisvalid AND i.indisready, 'columns',
            (SELECT json_agg(a.attname ORDER BY k.position)
             FROM unnest(i.indkey) WITH ORDINALITY AS k(number, position)
             LEFT JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.number)))
            FROM pg_index i WHERE i.indrelid='storage_record'::regclass));
        COMMIT;
        """;
    var result = service.executeKubectlCommand(false, "-n", namespace, "exec", names.getFirst(),
        "-c", "postgres", "--", "psql", "-X", "-d", config("testdata.ccNotification.databaseName"),
        "-qAt", "-v", "ON_ERROR_STOP=1", "-c", sql);
    require(result.exitCode() == 0, "Notification index observation failed: " + result.stderr());
    var rows = result.stdout().lines().filter(line -> !line.isBlank()).toList();
    require(rows.size() == 1, "Expected one notification index snapshot");
    return JSON.readTree(rows.getFirst());
  }

  /**
   * Reads the exact processing row and the database clock in one read-only transaction.
   */
  public static JsonNode processingRecord(String id) {
    require(id != null && id.matches("[a-f0-9-]{36}"), "Unsafe notification selector");
    var sql =
        """
        BEGIN TRANSACTION READ ONLY;
        SET LOCAL statement_timeout = '3s';
        SELECT json_build_object('observed_at_ms', (extract(epoch from clock_timestamp())*1000)::bigint,
          'records', coalesce((SELECT json_agg(row_to_json(v)) FROM (
            SELECT expires_at, convert_from(payload,'UTF8')::jsonb->>'id' AS notification_id,
              convert_from(payload,'UTF8')::jsonb->>'status' AS status,
              convert_from(payload,'UTF8')::jsonb->>'acceptedAt' AS accepted_at,
              convert_from(payload,'UTF8')::jsonb->'payload'->>'correlation_id' AS correlation
            FROM storage_record WHERE id='%s_notification') v), '[]'::json));
        COMMIT;
        """
            .formatted(id);
    var service = ZetaDeploymentConfiguration.getServiceInstance();
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    var pods =
        service.executeKubectlCommand(
            false,
            "-n",
            namespace,
            "get",
            "pods",
            "-l",
            config("testdata.ccNotification.databasePodSelector"),
            "-o",
            "name");
    var names = pods.stdout().lines().filter(s -> !s.isBlank()).toList();
    require(
        pods.exitCode() == 0 && names.size() == 1, "Expected one primary notification database");
    var result =
        service.executeKubectlCommand(
            false,
            "-n",
            namespace,
            "exec",
            names.getFirst(),
            "-c",
            "postgres",
            "--",
            "psql",
            "-X",
            "-d",
            config("testdata.ccNotification.databaseName"),
            "-qAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            sql);
    require(result.exitCode() == 0, "Read-only own-notification metadata query failed");
    var rows = result.stdout().lines().filter(s -> !s.isBlank()).toList();
    require(rows.size() == 1, "Ambiguous notification metadata result");
    return JSON.readTree(rows.getFirst());
  }

  /**
   * Uses database observation times to wait for expiry and stop at the absolute storage deadline.
   *
   * @param id exact notification ID
   * @param expires individual logical expiry in epoch milliseconds
   * @param deadline maximum physical retention in epoch milliseconds
   * @return last database observation
   * @throws InterruptedException if polling is interrupted
   */
  public static JsonNode awaitPurge(String id, long expires, long deadline)
      throws InterruptedException {
    return awaitPurge(expires, deadline, () -> processingRecord(id),
        System::currentTimeMillis, Thread::sleep);
  }

  /** Polls supplied database snapshots using their timestamps for expiry and deadline checks. */
  static JsonNode awaitPurge(
      long expires, long deadline, Supplier<JsonNode> read, LongSupplier localClock,
      BoundedObservation.Sleeper sleep) throws InterruptedException {
    require(expires < deadline, "Logical expiry must precede the physical storage deadline");
    long previous = Long.MIN_VALUE;
    while (true) {
      var last = read.get();
      var timestamp = last.path("observed_at_ms");
      require(timestamp.isIntegralNumber() && timestamp.canConvertToLong()
              && timestamp.asLong() > 0,
          "Missing or invalid database observation time");
      long observed = timestamp.asLong();
      require(
          Math.abs(observed - localClock.getAsLong()) <= 5000,
          "Database and verifier clocks diverged");
      require(observed >= previous, "Database observation time moved backwards");
      require(last.path("records").isArray(), "Missing processing record observation");
      if (observed >= expires && (last.path("records").isEmpty() || observed >= deadline)) {
        return last;
      }
      previous = observed;
      long remaining = (observed < expires ? expires : deadline) - observed;
      sleep.sleep(Math.min(observed < expires ? 1000 : 200, remaining));
    }
  }

  /**
   * Reads the exact committed pusher collection for the synthetic scenario identity.
   */
  public static JsonNode pusherCollection(String identity) {
    require(identity.matches("[A-Z][0-9]{9}"), "Unsafe pusher selector");
    var service = ZetaDeploymentConfiguration.getServiceInstance();
    var ns = ZetaDeploymentConfiguration.getNamespace();
    var sql =
        """
        BEGIN TRANSACTION READ ONLY;
        SET LOCAL statement_timeout='3s';
        SELECT json_build_object('identity', convert_from(payload,'UTF8')::jsonb->>'userId',
          'pushers', convert_from(payload,'UTF8')::jsonb->'pushers')
        FROM storage_record WHERE id IN ('kvnr:%s_pushers','%s_pushers');
        COMMIT;
        """
            .formatted(identity, identity);
    var pods =
        service.executeKubectlCommand(
            false,
            "-n",
            ns,
            "get",
            "pods",
            "-l",
            config("testdata.ccNotification.databasePodSelector"),
            "-o",
            "name");
    var names = pods.stdout().lines().filter(l -> !l.isBlank()).toList();
    require(pods.exitCode() == 0 && names.size() == 1, "Expected one notification database");
    var result =
        service.executeKubectlCommand(
            false,
            "-n",
            ns,
            "exec",
            names.getFirst(),
            "-c",
            "postgres",
            "--",
            "psql",
            "-X",
            "-d",
            config("testdata.ccNotification.databaseName"),
            "-qAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            sql);
    require(result.exitCode() == 0, "Own-pusher storage observation failed");
    var rows = result.stdout().lines().filter(l -> !l.isBlank()).toList();
    require(rows.size() == 1, "Expected the exact existing identity's pusher collection");
    var row = JSON.readTree(rows.getFirst());
    require(
        identity.equals(row.path("identity").asString()),
        "Pusher collection belongs to another identity");
    return row.path("pushers");
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
