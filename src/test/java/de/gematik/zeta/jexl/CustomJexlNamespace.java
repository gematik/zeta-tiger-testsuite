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

package de.gematik.zeta.jexl;

/**
 * Provide helper methods that should be available inside Tiger JEXL expressions.
 *
 * <p>Extend this class with the functions you need and reference it via
 * {@link CustomJexlNamespaces#namespaces()}.</p>
 */
public class CustomJexlNamespace {

  /**
   * Singleton instance used when exporting the namespace.
   */
  public static final CustomJexlNamespace INSTANCE = new CustomJexlNamespace();

  private CustomJexlNamespace() {
    // utility
  }

  /**
   * Example function. Replace or extend with real helpers.
   *
   * @return placeholder value
   */
  public String example() {
    return "example";
  }
}
