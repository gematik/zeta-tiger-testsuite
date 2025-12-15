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

package de.gematik.test.tiger.lib;

import de.gematik.rbellogger.util.RbelAnsiColors;
import de.gematik.test.tiger.common.Ansi;
import de.gematik.test.tiger.common.jexl.TigerJexlExecutor;
import de.gematik.zeta.jexl.CustomJexlNamespaces;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Copy of {@link TigerInitializer} class from the tiger framework. NOTE: Please make sure to
 * compare this class to the other if there are any version upgrades in Tiger.
 */
@Slf4j
public class TigerInitializer {

  private static final Object STARTUP_MUTEX = new Object();
  private static final AtomicBoolean NAMESPACES_REGISTERED = new AtomicBoolean();
  private static RuntimeException tigerStartupFailedException;

  /**
   * Copied method.
   *
   * @param runnable the runnable
   */
  public void runWithSafelyInitialized(Runnable runnable) {
    synchronized (STARTUP_MUTEX) {
      if (!TigerDirector.isInitialized()) {
        showTigerVersion();
        initializeTiger();
        TigerDirector.assertThatTigerIsInitialized();
      } else {
        log.info("Tiger is already initialized, skipping initialization");
      }
    }
    runnable.run();
  }

  /**
   * Copied method.
   */
  private synchronized void initializeTiger() {
    if (tigerStartupFailedException != null) {
      return;
    }
    log.info("Initializing Tiger");
    registerAdditionalNamespaces();
    try {
      TigerDirector.registerShutdownHook();
      TigerDirector.start();
    } catch (RuntimeException ex) {
      tigerStartupFailedException = ex;
      throw ex;
    }
  }

  private void registerAdditionalNamespaces() {
    if (NAMESPACES_REGISTERED.compareAndSet(false, true)) {
      for (Map.Entry<String, Object> entry : CustomJexlNamespaces.namespaces().entrySet()) {
        TigerJexlExecutor.registerAdditionalNamespace(entry.getKey(), entry.getValue());
        log.info("Registered Tiger JEXL namespace '{}'", entry.getKey());
      }
    }
  }

  private void showTigerVersion() {
    log.info(
        Ansi.colorize("Tiger version: " + getTigerVersionString(), RbelAnsiColors.GREEN_BRIGHT));
  }

  /**
   * Copied method.
   */
  public String getTigerVersionString() {
    Properties properties = new Properties();
    try (InputStream inputStream = TigerInitializer.class.getResourceAsStream(
        "/build.properties")) {
      properties.load(Objects.requireNonNull(inputStream));
      String version = properties.getProperty("tiger.version");
      if ("${project.version}".equals(version)) {
        version = "UNKNOWN";
      }
      return version + " (" + properties.getProperty("tiger.build.timestamp") + ')';
    } catch (IOException | RuntimeException ex) {
      return "UNKNOWN";
    }
  }
}
