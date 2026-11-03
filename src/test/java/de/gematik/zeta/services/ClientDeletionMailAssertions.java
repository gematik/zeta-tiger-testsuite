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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Validates captured mail metadata and deletion text without performing network requests.
 */
public final class ClientDeletionMailAssertions {
  public static final String OTP_SUBJECT = "Your email verification code";

  private ClientDeletionMailAssertions() {}

  /** Validates that the configured text adapter rejects non-deletion controls. */
  public static Pattern noticePattern(String expression) {
    require(
        expression != null && !expression.isBlank() && !expression.contains("${"),
        "Missing completed-deletion mail profile");
    var pattern = Pattern.compile(expression);
    for (var counterexample :
        List.of(
            "",
            "Your email verification code is 123456.",
            "Your client will be deleted.",
            "Your client was not deleted.",
            "Your email address was changed.",
            "Client deletion requested.",
            "Your client was registered.")) {
      require(
          !pattern.matcher(counterexample).matches(),
          "Mail profile accepts a non-deletion control");
    }
    return pattern;
  }

  /** Indexes a complete mail response, rejecting malformed entries and duplicate IDs. */
  public static Map<Integer, JsonNode> index(JsonNode messages) {
    require(messages != null && messages.isArray(), "Mailbox metadata must be an array");
    var result = new HashMap<Integer, JsonNode>();
    for (var message : messages) {
      require(
          message.isObject()
              && message.path("id").isIntegralNumber()
              && message.path("id").canConvertToInt()
              && message.path("id").asInt() > 0
              && message.path("recipients").isArray()
              && !message.path("recipients").isEmpty()
              && message.path("subject").isString(),
          "Malformed mailbox metadata entry");
      for (var recipient : message.path("recipients")) {
        require(
            recipient.isString() && recipient.asString().matches("<[^<>\\s@]+@[^<>\\s@]+>"),
            "Malformed envelope recipient");
      }
      require(
          result.putIfAbsent(message.path("id").asInt(), message.deepCopy()) == null,
          "Duplicate mailbox message ID");
    }
    return Map.copyOf(result);
  }

  /** Selects fresh non-OTP deliveries to F1 while requiring an unchanged baseline. */
  public static List<JsonNode> newDeliveries(
      Map<Integer, JsonNode> before, Map<Integer, JsonNode> after, String expectedEmail) {
    for (var entry : before.entrySet()) {
      require(
          entry.getValue().equals(after.get(entry.getKey())),
          "Mailbox reset, pruning or changed baseline invalidates delivery correlation");
    }
    return after.entrySet().stream()
        .filter(e -> !before.containsKey(e.getKey()))
        .map(Map.Entry::getValue)
        .filter(m -> deliveredTo(m, expectedEmail))
        .filter(m -> !OTP_SUBJECT.equals(m.path("subject").asString()))
        .toList();
  }

  /** Matches the envelope recipient against the independently verified F1 address. */
  public static boolean deliveredTo(JsonNode message, String expectedEmail) {
    for (var recipient : message.path("recipients")) {
      if (recipient.asString().equalsIgnoreCase("<" + expectedEmail + ">")) {
        return true;
      }
    }
    return false;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
