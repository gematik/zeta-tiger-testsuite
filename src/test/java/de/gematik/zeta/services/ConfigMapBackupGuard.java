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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds guarded ConfigMap operations while preserving unrelated fields and concurrent changes. */
final class ConfigMapBackupGuard {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PREFIX = "zeta-tests.gematik.de/";
  private static final String OWNER = PREFIX + "owner";
  private static final String SOURCE_UID = PREFIX + "source-uid";
  private static final String ORIGINAL = PREFIX + "original-data-sha256";
  private static final String EXPECTED = PREFIX + "expected-data-sha256";
  private static final String PENDING = PREFIX + "pending-data-sha256";

  private ConfigMapBackupGuard() {}

  static ObjectNode backup(JsonNode current, String owner) {
    require(owner != null && !owner.isBlank(), "Missing backup owner");
    final var name = required(current, "/metadata/name");
    final var namespace = required(current, "/metadata/namespace");
    final var uid = required(current, "/metadata/uid");
    required(current, "/metadata/resourceVersion");
    var data = data(current);
    final var hash = hash(data);
    var result = JSON.createObjectNode();
    result.put("apiVersion", "v1");
    result.put("kind", "ConfigMap");
    var metadata = result.putObject("metadata");
    metadata.put("name", backupName(name));
    metadata.put("namespace", namespace);
    metadata.putObject("labels").put("app.kubernetes.io/managed-by", "zeta-testsuite");
    var annotations = metadata.putObject("annotations");
    annotations.put(OWNER, owner);
    annotations.put(SOURCE_UID, uid);
    annotations.put(ORIGINAL, hash);
    annotations.put(EXPECTED, hash);
    annotations.put(PENDING, hash);
    result.set("data", data.deepCopy());
    return result;
  }

  static void requireOwned(JsonNode backup, JsonNode current, String owner) {
    require(backupName(required(current, "/metadata/name"))
            .equals(required(backup, "/metadata/name")), "Backup belongs to another ConfigMap");
    require(required(current, "/metadata/namespace")
            .equals(required(backup, "/metadata/namespace")), "Backup belongs to another namespace");
    require(owner != null && owner.equals(annotation(backup, OWNER)), "Backup belongs to another run");
    require(required(current, "/metadata/uid").equals(annotation(backup, SOURCE_UID)),
        "Original ConfigMap identity changed");
    require(hash(data(backup)).equals(annotation(backup, ORIGINAL)), "Backup data changed");
    required(backup, "/metadata/uid");
    required(backup, "/metadata/resourceVersion");
  }

  static ArrayNode pendingPatch(JsonNode backup, JsonNode current, ObjectNode desired, String owner) {
    requireOwned(backup, current, owner);
    requireKnownData(backup, current);
    var patch = identityTests(backup);
    patch.add(JSON.valueToTree(Map.of("op", "replace", "path", annotationPath(EXPECTED),
        "value", hash(data(current)))));
    patch.add(JSON.valueToTree(Map.of("op", "replace", "path", annotationPath(PENDING),
        "value", hash(desired))));
    return patch;
  }

  static ArrayNode dataPatch(JsonNode current, ObjectNode desired) {
    var patch = identityTests(current);
    patch.add(JSON.valueToTree(Map.of("op", "test", "path", "/data", "value", data(current))));
    patch.add(JSON.valueToTree(Map.of("op", "replace", "path", "/data", "value", desired)));
    return patch;
  }

  static ArrayNode restorePatch(JsonNode backup, JsonNode current, String owner) {
    requireOwned(backup, current, owner);
    if (data(current).equals(data(backup))) {
      return JSON.createArrayNode();
    }
    requireKnownData(backup, current);
    return dataPatch(current, data(backup));
  }

  static ObjectNode deleteOptions(JsonNode backup, JsonNode restored, String owner) {
    requireOwned(backup, restored, owner);
    require(data(backup).equals(data(restored)), "ConfigMap data has not been restored");
    var options = JSON.createObjectNode();
    options.put("apiVersion", "v1");
    options.put("kind", "DeleteOptions");
    var preconditions = options.putObject("preconditions");
    preconditions.put("uid", required(backup, "/metadata/uid"));
    preconditions.put("resourceVersion", required(backup, "/metadata/resourceVersion"));
    return options;
  }

  static ObjectNode data(JsonNode configMap) {
    require(configMap.path("data").isObject(), "Missing ConfigMap data object");
    return (ObjectNode) configMap.path("data");
  }

  static String backupName(String original) {
    return original + "-tiger-original-backup";
  }

  private static void requireKnownData(JsonNode backup, JsonNode current) {
    var currentHash = hash(data(current));
    require(List.of(annotation(backup, EXPECTED), annotation(backup, PENDING)).contains(currentHash),
        "ConfigMap data changed outside this run; preserving current data and backup");
  }

  private static ArrayNode identityTests(JsonNode resource) {
    var patch = JSON.createArrayNode();
    for (var path : List.of("/metadata/uid", "/metadata/resourceVersion")) {
      patch.add(JSON.valueToTree(Map.of("op", "test", "path", path, "value", required(resource, path))));
    }
    return patch;
  }

  private static String annotation(JsonNode backup, String key) {
    return required(backup, annotationPath(key));
  }

  private static String annotationPath(String key) {
    return "/metadata/annotations/" + key.replace("~", "~0").replace("/", "~1");
  }

  private static String required(JsonNode node, String path) {
    var value = node.at(path).asString("");
    require(!value.isBlank(), "Missing ConfigMap identity or ownership field: " + path);
    return value;
  }

  private static String hash(ObjectNode data) {
    var canonical = new TreeMap<String, String>();
    data.properties().forEach(entry -> {
      require(entry.getValue().isString(), "ConfigMap data values must be strings");
      canonical.put(entry.getKey(), entry.getValue().asString());
    });
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(JSON.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM has no SHA-256 implementation", e);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}
