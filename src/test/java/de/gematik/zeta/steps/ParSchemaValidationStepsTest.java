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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerConfigurationKey;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.common.exceptions.TigerJexlException;
import de.gematik.test.tiger.common.jexl.TigerJexlExecutor;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks decoded PAR forms and deferred schema failures against the local OpenAPI contract. */
class ParSchemaValidationStepsTest {
  private static final String SCHEMA = "schemas/v_2_0/openapi/as-par-endpoint.yaml"
      + "#/paths/~1par/post/requestBody/content/application~1x-www-form-urlencoded/schema";

  @AfterEach
  void resetSoftAssertions() {
    SoftAssertionsContext.reset();
  }

  @Test
  void validatesOuterFormWithEncodedNamesAndOptionalExtensions() {
    validate("/par", "response_type=code&client%5Fid=https%3A%2F%2Fguard.example"
        + "&scope=openid+scope&claims=%7B%22id_token%22%3A%7B%7D%7D", SCHEMA);
    assertDoesNotThrow(SoftAssertionsContext::assertAll);
  }

  @ParameterizedTest
  @ValueSource(strings = {"client_id", "response_type"})
  void missingRequiredFieldDoesNotPreventLaterValidationButFailsAtScenarioEnd(String field) {
    var incomplete = field.equals("client_id") ? "response_type=code" : "client_id=guard";
    assertDoesNotThrow(() -> validate("/par", incomplete, SCHEMA));
    assertDoesNotThrow(() -> validate("/par", "client_id=guard&response_type=code", SCHEMA));
    assertEquals(1, SoftAssertionsContext.softly().errorsCollected().size());
    var error = assertThrows(AssertionError.class, SoftAssertionsContext::assertAll);
    assertTrue(error.getMessage().contains(field));
    assertTrue(error.getMessage().contains("as-par-endpoint.yaml"));
    assertDoesNotThrow(SoftAssertionsContext::assertAll);
  }

  @Test
  void emptyFormRecordsMissingRequiredFieldsWithoutStopping() {
    assertDoesNotThrow(() -> validate("/par", "", SCHEMA));
    var error = assertThrows(AssertionError.class, SoftAssertionsContext::assertAll);
    assertTrue(error.getMessage().contains("client_id"));
    assertTrue(error.getMessage().contains("response_type"));
  }

  @Test
  void missingSchemaIsAnImmediateTestSetupError() {
    assertThrows(AssertionError.class,
        () -> validate("/par", "client_id=guard&response_type=code", SCHEMA.replace("as-par", "missing")));
    assertDoesNotThrow(SoftAssertionsContext::assertAll);
  }

  @Test
  void duplicateParametersCannotBeLostDuringFormConversion() {
    var error = assertThrows(TigerJexlException.class, () -> validate("/par",
        "client_id=guard&response_type=code&client_id=other", SCHEMA));
    assertThat(error).hasRootCauseInstanceOf(AssertionError.class)
        .hasStackTraceContaining("Duplicate SDK form parameter");
  }

  private static void validate(String path, String body, String schema) {
    var raw = "POST " + path + " HTTP/1.1\r\nHost: guard.example\r\n"
        + "Content-Type: application/x-www-form-urlencoded\r\n"
        + "Content-Length: " + body.length() + "\r\n\r\n" + body;
    var message = RbelLogger.build().getRbelConverter().parseMessage(
        raw.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    var retriever = mock(RbelMessageRetriever.class);
    when(retriever.findElementInCurrentRequest("$.body"))
        .thenReturn(message.findRbelPathMembers("$.body").getFirst());
    TigerJexlExecutor.registerAdditionalNamespace("rbel", retriever.new JexlToolbox());
    var originalSoft = TigerGlobalConfiguration.readStringOptional("schemaValidation.soft");
    TigerGlobalConfiguration.putValue("schemaValidation.soft", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      var step = Files.readAllLines(Path.of("src/test/resources/features/UserStory_03/UseCase_02/"
          + "oidc_par_federation_happy_path.feature")).stream()
          .filter(line -> line.strip().startsWith("Und validiere \""))
          .findFirst().orElseThrow();
      var expression = step.substring(step.indexOf('"') + 1, step.indexOf("\" gegen"));
      new SchemaValidationSteps().validateJsonAgainstOpenApiSchema(
          TigerGlobalConfiguration.resolvePlaceholders(expression), schema);
    } catch (IOException e) {
      throw new AssertionError("Cannot read PAR feature", e);
    } finally {
      TigerJexlExecutor.deregisterNamespace("rbel");
      originalSoft.ifPresentOrElse(value -> TigerGlobalConfiguration.putValue(
          "schemaValidation.soft", value, ConfigurationValuePrecedence.TEST_CONTEXT),
          () -> TigerGlobalConfiguration.deleteFromAllSources(
              new TigerConfigurationKey("schemaValidation", "soft")));
    }
  }
}
