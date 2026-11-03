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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks JSON data for literal, Base64, hexadecimal, and percent-encoded message text. */
public final class PlaintextPayloadInspection {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_DECODE_DEPTH = 4;

  private PlaintextPayloadInspection() {}

  /**
   * Rejects message strings found anywhere in the supplied JSON data, including encoded copies.
   *
   * @param observed JSON data to inspect
   * @param expected message payload containing non-empty text values
   * @throws AssertionError if a message value occurs or there are no text values to compare
   */
  public static void assertAbsent(String observed, String expected) {
    if (!JSON.readTree(observed).isObject()) {
      throw new AssertionError("Observed gateway payload must be a JSON object");
    }
    assertAbsentInText(observed, expected);
  }

  /**
   * Rejects literal and supported encoded message strings in arbitrary captured text.
   *
   * @param observed captured text, which may also contain JSON
   * @param expected original JSON payload containing non-empty text values
   * @throws AssertionError if message text occurs or there are no text values to compare
   */
  public static void assertAbsentInText(String observed, String expected) {
    var needles = new ArrayList<String>();
    collectText(JSON.readTree(expected), needles, false);
    if (needles.isEmpty()) {
      throw new AssertionError("Expected payload contains no non-empty message text");
    }
    var pending = new ArrayDeque<Layer>();
    pending.add(new Layer(observed, 0));
    var seen = new HashSet<String>();
    while (!pending.isEmpty()) {
      var layer = pending.removeFirst();
      if (!seen.add(layer.value())) {
        continue;
      }
      for (var needle : needles) {
        if (layer.value().contains(needle)) {
          throw new AssertionError("Gateway payload contains message text at decoding depth "
              + layer.depth());
        }
      }
      if (layer.depth() >= MAX_DECODE_DEPTH) {
        continue;
      }
      var nextDepth = layer.depth() + 1;
      var values = new ArrayList<String>();
      try {
        collectText(JSON.readTree(layer.value()), values, true);
      } catch (RuntimeException ignored) {
        // Non-JSON values are still checked as text and as supported encodings.
      }
      values.forEach(value -> pending.addLast(new Layer(value, nextDepth)));
      decode(layer.value(), nextDepth, pending);
    }
  }

  private static void collectText(JsonNode node, List<String> values, boolean includeNames) {
    if (node == null) {
      return;
    }
    if (node.isString() && !node.asString().isEmpty()) {
      values.add(node.asString());
    } else if (node.isObject()) {
      node.properties().forEach(entry -> {
        if (includeNames) {
          values.add(entry.getKey());
        }
        collectText(entry.getValue(), values, includeNames);
      });
    } else if (node.isArray()) {
      node.forEach(child -> collectText(child, values, includeNames));
    }
  }

  private static void decode(String value, int depth, ArrayDeque<Layer> pending) {
    for (var decoder : List.of(Base64.getDecoder(), Base64.getUrlDecoder())) {
      try {
        pending.addLast(new Layer(new String(decoder.decode(value), StandardCharsets.UTF_8), depth));
      } catch (IllegalArgumentException ignored) {
        // Values without this encoding need no additional layer.
      }
    }
    if (value.matches("(?:[a-fA-F0-9]{2})+")) {
      pending.addLast(new Layer(new String(HexFormat.of().parseHex(value),
          StandardCharsets.UTF_8), depth));
    }
    if (value.contains("%")) {
      try {
        pending.addLast(new Layer(URLDecoder.decode(value, StandardCharsets.UTF_8), depth));
      } catch (IllegalArgumentException ignored) {
        // An invalid percent escape cannot be decoded.
      }
    }
  }

  private record Layer(String value, int depth) {}
}
