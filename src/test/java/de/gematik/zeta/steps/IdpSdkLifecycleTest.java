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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.SdkIdpOnlyRuntime;
import org.junit.jupiter.api.Test;

class IdpSdkLifecycleTest {
  @Test
  void pendingFactorFixtureCleansUpAfterPartialStart() throws Exception {
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "example.org");
    try (var runtimes =
        mockConstruction(
            SdkIdpOnlyRuntime.class,
            (runtime, context) ->
                doThrow(new IllegalStateException("partial setup"))
                    .when(runtime)
                    .start(anyString(), anyString()))) {
      var steps = new IdpOnlySteps();
      assertThrows(IllegalStateException.class, steps::prepare);
      steps.clear();
      verify(runtimes.constructed().getFirst()).close();
    } finally {
      TigerGlobalConfiguration.reset();
    }
  }

}
