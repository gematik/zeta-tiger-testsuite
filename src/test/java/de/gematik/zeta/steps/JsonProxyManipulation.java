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

import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Compiled JSON-object replacement with exact string selectors at unique dotted request-body paths.
 *
 * @param condition Tiger JEXL message selector
 * @param field target RBEL path
 * @param replacement serialized JSON-object replacement
 * @param executions positive maximum replacement count
 */
record JsonProxyManipulation(String condition, String field, String replacement, int executions) {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Compiles a structured description without sending a proxy-control request.
   *
   * @param json description containing when, replace and executions
   * @return validated replacement and correlated request selector
   * @throws IllegalArgumentException if fields, selector types or the count are invalid
   */
  static JsonProxyManipulation parse(String json) {
    return parse(json, UnaryOperator.identity());
  }

  /**
   * Compiles a description, resolving placeholders in string values after JSON parsing.
   *
   * @param json description containing when, replace and executions
   * @param resolve placeholder resolver for selector paths, selector values and replacement strings
   * @return validated replacement and correlated request selector
   * @throws IllegalArgumentException if resolved fields, selector types or the count are invalid
   */
  static JsonProxyManipulation parse(String json, UnaryOperator<String> resolve) {
    var root = JSON.readTree(json);
    requireKeys(root, Set.of("when", "replace", "executions"), "manipulation");
    var when = root.path("when");
    requireKeys(when, Set.of("isResponse", "requestPathMatches", "requestBody"), "when");
    var replace = root.path("replace");
    requireKeys(replace, Set.of("path", "value"), "replace");
    var executions = root.path("executions");
    if (!when.path("isResponse").isBoolean() || !when.path("requestBody").isObject() || !replace.path("value").isObject()
        || !executions.isIntegralNumber() || !executions.canConvertToInt() || executions.intValue() < 1) {
      throw new IllegalArgumentException("Require a boolean isResponse, requestBody and replacement objects, and positive integer executions");
    }
    var requestPath = requireText(resolveStrings(when.path("requestPathMatches"), resolve), "requestPathMatches");
    Pattern.compile(requestPath);
    var condition = new StringBuilder(when.path("isResponse").booleanValue() ? "isResponse" : "isRequest")
        .append(" && request != null && request.path =~ ").append(literal(requestPath));
    var body = when.path("requestBody");
    if (!body.isEmpty()) {
      condition.append(" && request.body != null");
    }
    var resolvedPaths = new HashSet<String>();
    for (var key : body.propertyNames()) {
      var path = resolve.apply(key);
      if (!path.matches("\\$(?:\\.[A-Za-z0-9_-]+)+") || !body.path(key).isTextual() || !resolvedPaths.add(path)) {
        throw new IllegalArgumentException("requestBody requires dotted RBEL paths and exact string values: " + path);
      }
      condition.append(" && request.body.findElement(").append(literal(path))
          .append(").orElse(null)?.rawStringContent == ").append(literal(resolve.apply(body.path(key).asString())));
    }
    return new JsonProxyManipulation(condition.toString(), requireText(resolveStrings(replace.path("path"), resolve), "replace.path"),
        JSON.writeValueAsString(resolveStrings(replace.path("value"), resolve)), executions.intValue());
  }

  private static JsonNode resolveStrings(JsonNode node, UnaryOperator<String> resolve) {
    if (node.isTextual()) {
      return JSON.getNodeFactory().textNode(resolve.apply(node.asString()));
    }
    if (node.isObject()) {
      var resolved = JSON.createObjectNode();
      for (var key : node.propertyNames()) {
        resolved.set(key, resolveStrings(node.path(key), resolve));
      }
      return resolved;
    }
    if (node.isArray()) {
      var resolved = JSON.createArrayNode();
      for (var item : node) {
        resolved.add(resolveStrings(item, resolve));
      }
      return resolved;
    }
    return node;
  }

  private static void requireKeys(JsonNode node, Set<String> keys, String label) {
    if (node == null || !node.isObject() || !new HashSet<>(node.propertyNames()).equals(keys)) {
      throw new IllegalArgumentException(label + " requires exactly these properties: " + keys);
    }
  }

  private static String requireText(JsonNode node, String label) {
    if (!node.isTextual() || node.asString().isBlank()) {
      throw new IllegalArgumentException(label + " must be a nonempty string");
    }
    return node.asString();
  }

  private static String literal(String value) {
    var escaped = value.replace("\\", "\\\\").replace("'", "\\'")
        .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    return "('" + escaped.replace("$.", "$' + '.").replace("@.", "@' + '.") + "')";
  }
}
