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
    return getInstanceForPathPrefix("paths.client");
  }

  /**
   * Creates a preconfigured {@link TestDriverConfigurationService} from the given path prefix.
   *
   * @param pathPrefix Tiger config prefix containing {@code reset}, {@code configure}, and optionally
   *                   {@code oidc.kvnrEmail}
   * @return configured testdriver client
   */
  public static TestDriverConfigurationService getInstanceForPathPrefix(final String pathPrefix) {
    var resetUrl = TigerGlobalConfiguration.readStringOptional(pathPrefix + ".reset")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("The testdriver reset URL is not configured at " + pathPrefix + ".reset."));
    var configureUrl = TigerGlobalConfiguration.readStringOptional(pathPrefix + ".configure")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError(
            "The testdriver configure URL is not configured at " + pathPrefix + ".configure."));
    var kvnrEmailUrl = TigerGlobalConfiguration.readStringOptional(pathPrefix + ".oidc.kvnrEmail")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElse(null);

    try {
      return new TestDriverConfigurationService(resetUrl, configureUrl, kvnrEmailUrl);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new AssertionError("The testdriver endpoint configuration is invalid.", e);
    }
  }
}
