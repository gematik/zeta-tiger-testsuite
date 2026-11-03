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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Preserves scenario-entry values of temporary HTTP default headers. */
public final class TemporaryHttpHeaderHooks {
  private final Map<String, Optional<String>> original = new LinkedHashMap<>();

  /** Captures header values before scenario actions can overwrite them. */
  @Before(order = Integer.MIN_VALUE)
  public void capture() {
    original.clear();
    for (var name : List.of("popp", "Idempotency-Key")) {
      original.put(name, TigerGlobalConfiguration.readStringOptional("tiger.httpClient.defaultHeader." + name));
    }
  }

  /** Restores both headers before cleanup operations issue further HTTP requests. */
  @After(order = Integer.MAX_VALUE)
  public void restore() {
    var http = new HttpGlueCode();
    var failure = new AssertionError("Temporary HTTP header restoration failed");
    original.forEach((name, value) -> {
      try {
        if (value.isPresent()) {
          http.setDefaultHeader(name, value.orElseThrow());
        } else {
          http.clearDefaultHeader(name);
        }
      } catch (RuntimeException | AssertionError exception) {
        failure.addSuppressed(exception);
      }
    });
    if (failure.getSuppressed().length > 0) {
      throw failure;
    }
  }
}
