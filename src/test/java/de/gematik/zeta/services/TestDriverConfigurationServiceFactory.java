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

/**
 * Factory for {@link TestDriverConfigurationService}.
 */
public class TestDriverConfigurationServiceFactory {

  /**
   * Creates a preconfigured {@link TestDriverConfigurationService} from Tiger config.
   *
   * @return configured testdriver client
   */
  public static TestDriverConfigurationService getInstance() {
    var resetUrl = TigerGlobalConfiguration.readStringOptional("paths.client.reset")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("The testdriver reset URL is not configured."));
    var configureUrl = TigerGlobalConfiguration.readStringOptional("paths.client.configure")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("The testdriver configure URL is not configured."));

    try {
      return new TestDriverConfigurationService(resetUrl, configureUrl);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new AssertionError("The testdriver endpoint configuration is invalid.", e);
    }
  }
}
