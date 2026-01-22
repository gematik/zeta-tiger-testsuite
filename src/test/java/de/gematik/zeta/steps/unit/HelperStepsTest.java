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

package de.gematik.zeta.steps.unit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.zeta.steps.HelperSteps;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HelperSteps}.
 */
class HelperStepsTest {

  private final HelperSteps helperSteps = new HelperSteps();

  /**
   * Ensures invalid Base64-URL strings trigger an {@link AssertionError}.
   */
  @Test
  void decodeBase64UrlToStringInvalidInput() {
    Method method;
    try {
      method = HelperSteps.class.getDeclaredMethod("decodeBase64UrlToString", String.class);
      method.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Test setup failed, method not found", e);
    }

    assertThrows(AssertionError.class, () -> {
      try {
        method.invoke(helperSteps, "### not base64-url ###");
      } catch (InvocationTargetException e) {
        if (e.getCause() instanceof AssertionError assertionError) {
          throw assertionError;
        }
        throw new RuntimeException(e.getCause());
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }, "Invalid Base64-URL input should raise an AssertionError");
  }
}
