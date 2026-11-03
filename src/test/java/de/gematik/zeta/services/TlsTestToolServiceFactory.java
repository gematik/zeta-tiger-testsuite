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
 * Factory for {@link TlsTestToolService}.
 */
public class TlsTestToolServiceFactory {
  private static final ThreadLocal<TlsTestToolSession> CURRENT = new ThreadLocal<>();

  public static final String TLS_TEST_TOOL_SERVICE_BASE_URL_CONFIG_KEY = "tlsTestTool.serviceUrl";

  /**
   * Uses global Tiger configuration to create a preconfigured instance of {@link TlsTestToolService}.
   *
   * @return configured client instance
   */
  public static TlsTestToolService getInstance() {
    try {
      return new TlsTestToolService(serviceUrl());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new AssertionError("The TLS Test Tool service configuration is invalid.", e);
    }
  }

  static String serviceUrl() {
    var baseUrl = TigerGlobalConfiguration.readStringOptional(TLS_TEST_TOOL_SERVICE_BASE_URL_CONFIG_KEY)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("The TLS Test Tool service URL is not configured."));
    if (baseUrl.isBlank() || baseUrl.contains("${")) {
      throw new AssertionError("The TLS Test Tool service URL is not configured.");
    }
    return normalizeBaseUrl(baseUrl).replaceAll("/+$", "");
  }

  /** Claims and resets the shared tool once, before any ordinary scenario configuration upload. */
  public static TlsTestToolSession getSession() {
    var session = CURRENT.get();
    if (session == null) {
      session = new TlsTestToolSession(getInstance());
      CURRENT.set(session);
      session.start("", "ordinary-tls");
      session.reset();
    }
    session.assertOwned();
    return session;
  }

  /** Returns partially initialized ownership for the scenario cleanup hook, without creating it. */
  public static TlsTestToolSession currentSession() {
    return CURRENT.get();
  }

  /** Removes only this scenario's session after cleanup, leaving failed recovery backups intact. */
  public static void releaseSession(TlsTestToolSession session) {
    if (CURRENT.get() == session) {
      CURRENT.remove();
    }
  }

  /**
   * Ensures a configured host:port value can be used as an HTTP base URL.
   *
   * @param baseUrl configured base URL or host:port value
   * @return normalized HTTP base URL
   */
  private static String normalizeBaseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return "";
    }
    baseUrl = baseUrl.trim();
    if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
      return baseUrl;
    }
    return "http://" + baseUrl;
  }
}
