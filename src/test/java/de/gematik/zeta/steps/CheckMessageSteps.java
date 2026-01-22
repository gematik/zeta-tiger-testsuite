/*-
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
import io.cucumber.java.ParameterType;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber step definitions for validating JSON/header content, child-node inclusion, and size
 * constraints within messages.
 */
@Slf4j
public class CheckMessageSteps {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Verifies that a header object/string ({@code nodeToBeChecked}) contains all headers from the
   * reference ({@code parentOfChildren}).
   * Both inputs may be JSON or raw header blocks; they are parsed with header fallback.
   *
   * <p>Ignores missing {@code Connection} and value differences for {@code Host} or
   * {@code accept-encoding} headers.</p>
   *
   * <p>Uses soft assertions instead of throwing an {@link AssertionError} if headers are missing.</p>
   *
   * @param nodeToBeChecked  headers to inspect (JSON or raw header block)
   * @param parentOfChildren expected headers (JSON or raw header block)
   */
  @Dann("prüfe Knoten {tigerResolvedString} enthält mindestens alle Header aus {tigerResolvedString} und nutze soft assert")
  @Then("check node {tigerResolvedString} contains at least all headers of {tigerResolvedString} and use soft assert")
  public void softlyCheckNodeContainsAllHeadersOf(String nodeToBeChecked, String parentOfChildren) {
    nodeContainsAllHeadersOf(nodeToBeChecked, parentOfChildren, true);
  }

  /**
   * Verifies that a header object/string ({@code nodeToBeChecked}) contains all headers from the
   * reference ({@code parentOfChildren}).
   * Both inputs may be JSON or raw header blocks; they are parsed with header fallback.
   *
   * <p>Ignores missing {@code Connection} and value differences for {@code Host} or
   * {@code accept-encoding} headers.</p>
   *
   * <p>Throws an{@link AssertionError} if any header is missing.</p>
   *
   * @param nodeToBeChecked  headers to inspect (JSON or raw header block)
   * @param parentOfChildren expected headers (JSON or raw header block)
   */
  @Dann("prüfe Knoten {tigerResolvedString} enthält mindestens alle Header aus {tigerResolvedString}")
  @Then("check node {tigerResolvedString} contains at least all headers of {tigerResolvedString}")
  public void checkNodeContainsAllHeadersOf(String nodeToBeChecked, String parentOfChildren) {
    nodeContainsAllHeadersOf(nodeToBeChecked, parentOfChildren, false);
  }

  /**
   * Verifies that a header object/string ({@code nodeToBeChecked}) contains all headers from the
   * reference ({@code parentOfChildren}).
   * Both inputs may be JSON or raw header blocks; they are parsed with header fallback.
   *
   * <p>Ignores missing {@code Connection} and value differences for {@code Host} or
   * {@code accept-encoding} headers.</p>
   *
   * <p>With the soft option enabled, errors about missing headers will be logged only
   * and no exception is thrown.</p>
   *
   * <p>If the JSON is empty, cannot be parsed, or a header is missing
   * an {@link AssertionError} is thrown with a detailed error message.</p>
   *
   * @param nodeToBeChecked  headers to inspect (JSON or raw header block)
   * @param parentOfChildren expected headers (JSON or raw header block)
   * @param soft             if true, errors will only be logged.
   * @throws AssertionError if check fails
   */
  private void nodeContainsAllHeadersOf(String nodeToBeChecked, String parentOfChildren, boolean soft) {
    JsonNode node = parseJsonString(nodeToBeChecked, true);
    JsonNode referenceParent = parseJsonString(parentOfChildren, true);

    try {

      if (!node.isObject()) {
        throw new AssertionError("Node to be checked is not a JSON object.");
      }
      if (!referenceParent.isObject()) {
        throw new AssertionError("Reference node is not a JSON object.");
      }

      try {
        referenceParent.fieldNames().forEachRemaining(childName -> {
          if (!node.has(childName)) {
            if (!childName.equals("Connection")) {
              throw new AssertionError("Expected child node '" + childName + "' is missing.");
            }
            return; // Skip value comparison for missing Connection header
          }
          if (!node.get(childName).equals(referenceParent.get(childName))) {
            if (!childName.equals("Host") && !childName.equals("accept-encoding")) {
              throw new AssertionError(
                  "Child node '" + childName + "' differs. Expected " + referenceParent.get(
                      childName) + " but was " + node.get(childName));
            }
          }
        });
      } catch (AssertionError | RuntimeException ex) {
        if (soft) {
          log.warn(ex.getMessage(), nodeToBeChecked, parentOfChildren);
          SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        } else if (ex instanceof AssertionError) {
          throw ex;
        } else {
          throw new AssertionError("Validation failed: " + ex.getMessage(), ex);
        }
      }
    } catch (/*JsonProcessing*/Exception e) {
      throw new AssertionError("JSON could not be parsed.", e);
    }
  }


  /**
   * Asserts that the JSON object {@code nodeToBeChecked} contains every child (name and value) from
   * the JSON object {@code parentOfChildren}. Both inputs must parse into JSON objects; otherwise
   * an {@link AssertionError} is thrown.
   *
   * <p>Uses soft assertions instead of throwing an {@link AssertionError} if nodes are missing.</p>
   *
   * @param nodeToBeChecked  JSON string to inspect for child nodes
   * @param parentOfChildren JSON string providing the expected child nodes
   */
  @Dann("prüfe Knoten {tigerResolvedString} enthält mindestens alle Kindknoten von {tigerResolvedString} und nutze soft assert")
  @Then("check node {tigerResolvedString} contains at least all child nodes of {tigerResolvedString} and use soft assert")
  public void softlyCheckNodeContainsAllChildNodesOf(String nodeToBeChecked, String parentOfChildren) {
    nodeContainsAllChildNodesOf(nodeToBeChecked, parentOfChildren, true);
  }

  /**
   * Asserts that the JSON object {@code nodeToBeChecked} contains every child (name and value) from
   * the JSON object {@code parentOfChildren}. Both inputs must parse into JSON objects; otherwise
   * an {@link AssertionError} is thrown.
   *
   * <p>Throws an{@link AssertionError} if any node is missing.</p>
   *
   * @param nodeToBeChecked  JSON string to inspect for child nodes
   * @param parentOfChildren JSON string providing the expected child nodes
   */
  @Dann("prüfe Knoten {tigerResolvedString} enthält mindestens alle Kindknoten von {tigerResolvedString}")
  @Then("check node {tigerResolvedString} contains at least all child nodes of {tigerResolvedString}")
  public void nodeContainsAllChildNodesOf(String nodeToBeChecked, String parentOfChildren) {
    nodeContainsAllChildNodesOf(nodeToBeChecked, parentOfChildren, false);
  }

  /**
   * Asserts that the JSON object {@code nodeToBeChecked} contains every child (name and value) from
   * the JSON object {@code parentOfChildren}. Both inputs must parse into JSON objects; otherwise
   * an {@link AssertionError} is thrown.
   *
   * <p>With the soft option enabled, errors about missing nodes will be logged only
   * and no exception is thrown.</p>
   *
   * <p>If the JSON is empty, cannot be parsed, or a node is missing
   * an {@link AssertionError} is thrown with a detailed error message.</p>
   *
   * @param nodeToBeChecked  JSON string to inspect for child nodes
   * @param parentOfChildren JSON string providing the expected child nodes
   * @param soft              if true, errors will only be logged.
   * @throws AssertionError if check fails
   */
  public void nodeContainsAllChildNodesOf(String nodeToBeChecked, String parentOfChildren, boolean soft) {
    JsonNode node = parseJsonString(nodeToBeChecked, false);
    JsonNode referenceParent = parseJsonString(parentOfChildren, false);

    try {

      if (!node.isObject()) {
        throw new AssertionError("Node to be checked is not a JSON object.");
      }
      if (!referenceParent.isObject()) {
        throw new AssertionError("Reference node is not a JSON object.");
      }

      try {
        referenceParent.fieldNames().forEachRemaining(childName -> {
          if (!node.has(childName)) {
            throw new AssertionError("Expected child node '" + childName + "' is missing.");
          }
          if (!node.get(childName).equals(referenceParent.get(childName))) {
            throw new AssertionError(
                "Child node '" + childName + "' differs. Expected " + referenceParent.get(
                    childName) + " but was " + node.get(childName));
          }
        });
      } catch (AssertionError | RuntimeException ex) {
        if (soft) {
          log.warn(ex.getMessage());
          SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        } else  if (ex instanceof AssertionError) {
          throw ex;
        } else {
          throw new AssertionError("Validation failed: " + ex.getMessage(), ex);
        }
      }
    } catch (/*JsonProcessing*/Exception e) {
      throw new AssertionError("JSON could not be parsed.", e);
    }
  }

  /**
   * Parses a string into a {@link JsonNode}. When {@code isHeader} is true and JSON parsing fails,
   * the content is treated as CR/LF-delimited {@code key: value} header lines and converted into a
   * JSON object of header names to values.
   *
   * @param jsonString the input string to parse (JSON or header block)
   * @param isHeader   whether to allow header-style parsing as a fallback
   * @return parsed {@link JsonNode}
   * @throws AssertionError if the input is empty or cannot be parsed
   */
  protected static JsonNode parseJsonString(String jsonString, boolean isHeader) {
    if (jsonString == null || jsonString.isBlank()) {
      throw new AssertionError("JSON text to be validated is empty.");
    }
    JsonNode jsonNode;
    try {
      jsonNode = JSON.readTree(jsonString);
    } catch (Exception parse) {
      if (isHeader) {
        var headerNode = parseHeaderLines(jsonString);
        if (headerNode != null) {
          return headerNode;
        }
      }
      // If the input is longer than 300 characters, show only the beginning
      var preview =
          jsonString.length() > 300 ? jsonString.substring(0, 300) + " …" : jsonString;
      throw new AssertionError("JSON could not be parsed:\n" + preview, parse);
    }
    return jsonNode;
  }

  /**
   * Parses CR/LF-delimited header lines of the form {@code key: value} into a JSON object.
   * Lines without a colon are ignored. Returns {@code null} if no valid header lines are found.
   *
   * @param raw raw header block
   * @return {@link JsonNode} representing headers or {@code null} when nothing parseable was found
   */
  private static JsonNode parseHeaderLines(String raw) {
    var headerLines = raw.split("\\r?\\n");
    var objectNode = JSON.createObjectNode();
    boolean found = false;
    for (String line : headerLines) {
      if (line == null || line.isBlank()) {
        continue;
      }
      int idx = line.indexOf(':');
      if (idx <= 0) {
        continue;
      }
      var name = line.substring(0, idx).trim();
      var value = line.substring(idx + 1).trim();
      objectNode.put(name, value);
      found = true;
    }
    return found ? objectNode : null;
  }

  /**
   * Asserts that the length of the given node string meets the provided limit (MAX/MIN/EQUALS) when
   * interpreted as bytes/characters.
   *
   * <p>Uses soft assertions instead of throwing an {@link AssertionError} if limitations are not met.</p>
   *
   * @param node  string content to measure
   * @param limit comparison operator (MAX, MIN, EQUALS)
   * @param size  expected length boundary
   */
  @Dann("prüfe ob der Knoten {tigerResolvedString} {limit} {int} Byte groß ist und nutze soft assert")
  @Then("verify if length of node {tigerResolvedString} is {limit} {int} Byte and use soft assert")
  public void softlyCheckNodeSizeMatches(String node, Limit limit, int size) {
    nodeSizeMatches(node, limit, size, true);
  }

  /**
   * Asserts that the length of the given node string meets the provided limit (MAX/MIN/EQUALS) when
   * interpreted as bytes/characters.
   *
   *  <p>Throws an {@link AssertionError} if the node is null
   *  or the length meets not the provided limitations.</p>
   *
   * @param node  string content to measure
   * @param limit comparison operator (MAX, MIN, EQUALS)
   * @param size  expected length boundary
   * @throws AssertionError if the node is null or does not satisfy the length constraint
   */
  @Dann("prüfe ob der Knoten {tigerResolvedString} {limit} {int} Byte groß ist")
  @Then("verify if length of node {tigerResolvedString} is {limit} {int} Byte")
  public void checkNodeSizeMatches(String node, Limit limit, int size) {

    nodeSizeMatches(node, limit, size, false);
  }

  /**
   * Asserts that the length of the given node string meets the provided limit (MAX/MIN/EQUALS) when
   * interpreted as bytes/characters.
   *
   * <p>With the soft option enabled, errors due to an incorrect length will be logged only
   * and no exception is thrown.</p>
   *
   * <p>If the node is null or the length meets not the provided limitations
   * an {@link AssertionError} is thrown with a detailed error message.
   *
   * @param node  string content to measure
   * @param limit comparison operator (MAX, MIN, EQUALS)
   * @param size  expected length boundary
   * @throws AssertionError if the node is null or does not satisfy the length constraint
   */
  public void nodeSizeMatches(String node, Limit limit, int size, boolean soft) {

    if (node == null) {
      throw new AssertionError("JSON text to be validated is null.");
    }

    int byteLength = node.getBytes(StandardCharsets.UTF_8).length;
    try {
      if (!limit.evalMatchingLimitation(byteLength, size)) {
        throw new AssertionError("The given message node's byte length is " + byteLength
            + " instead of " + limit + " " + size + " bytes.");
      }
    } catch (AssertionError ex) {
      if (soft) {
        log.warn("The given message node's byte length is '{}' instead of '{}' '{}' bytes.", byteLength, limit, size);
        SoftAssertionsContext.recordSoftFailure("The given message node's byte length is " + byteLength
            + " instead of " + limit + " " + size + " bytes.", ex);
      } else {
        throw ex;
      }
    }
  }

  /**
   * Cucumber parameter type mapping for the {@link Limit} enum (supports MAX and MIN tokens).
   *
   * @param limitType textual token from the step definition (MAX or MIN)
   * @return corresponding {@link Limit} value
   */
  @Deprecated
  @ParameterType("MAX|MIN")
  public Limit limit(String limitType) {
    return limitType.equals("MAX") ? Limit.MAX : Limit.MIN;
  }

  /**
   * Length comparison operators used in message size checks.
   */
  public enum Limit {
    MIN,
    MAX,
    EQUALS;

    /**
     * Returns the name of the provided {@link Limit} constant.
     *
     * @param limit limit to render as string
     * @return enum name of the limit
     */
    public String getValue(Limit limit) {
      return limit.name();
    }

    @Override
    public String toString() {
      return name().toLowerCase();
    }

    /**
     * Evaluates whether {@code currentSize} satisfies the given limit constraint relative
     * to {@code sizeBoundary}.
     *
     * @param actualSize     actual length of the message/node
     * @param sizeBoundary   boundary to compare against
     * @return true if the constraint is met; false otherwise
     */
    public boolean evalMatchingLimitation(int actualSize, int sizeBoundary) {
      return switch (this) {
        case MAX -> actualSize <= sizeBoundary;
        case MIN -> actualSize >= sizeBoundary;
        case EQUALS -> actualSize == sizeBoundary;
      };
    }
  }

}
