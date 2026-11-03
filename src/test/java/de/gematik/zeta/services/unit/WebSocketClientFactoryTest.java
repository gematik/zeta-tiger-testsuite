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

package de.gematik.zeta.services.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import de.gematik.zeta.services.DirectWebSocketClient;
import de.gematik.zeta.services.WebSocketClientFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Verifies failed initialization releases owned resources and allows a fully configured retry. */
class WebSocketClientFactoryTest {

  /** Preserves initialization failures, cleans both resources, and retries even if closing fails. */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retriesAfterConfigurationFailure(boolean failCleanup) {
    var configurationFailure = new IllegalStateException("Heartbeat configuration failed");
    var cleanupFailure = new IllegalStateException("Transport cleanup failed");
    try (var transports = mockConstruction(DirectWebSocketClient.class, (client, context) -> {
      if (context.getCount() == 1) {
        doThrow(configurationFailure).when(client).setDefaultHeartbeat(any(long[].class));
        if (failCleanup) {
          doThrow(cleanupFailure).when(client).close();
        }
      }
    }); var schedulers = mockConstruction(ThreadPoolTaskScheduler.class)) {
      try (var factory = new WebSocketClientFactory()) {
        assertThatThrownBy(factory::createRawClient)
            .isInstanceOf(AssertionError.class)
            .hasMessage("Could not configure direct WebSocket client")
            .hasCause(configurationFailure);
        assertThat(transports.constructed()).hasSize(1);
        assertThat(schedulers.constructed()).hasSize(1);
        verify(transports.constructed().getFirst()).close();
        verify(schedulers.constructed().getFirst()).shutdown();
        if (failCleanup) {
          assertThat(configurationFailure.getSuppressed()).containsExactly(cleanupFailure);
        } else {
          assertThat(configurationFailure.getSuppressed()).isEmpty();
        }

        var retry = factory.createRawClient();
        assertThat(transports.constructed()).hasSize(2);
        assertThat(schedulers.constructed()).hasSize(2);
        assertThat(retry).isSameAs(transports.constructed().get(1));
        assertThat(factory.createRawClient()).isSameAs(retry);
        verify(schedulers.constructed().get(1)).initialize();
        verify(retry).setMessageConverter(any());
        verify(retry).setTaskScheduler(schedulers.constructed().get(1));
        verify(retry).setDefaultHeartbeat(new long[]{30000, 30000});
      }
      verify(transports.constructed().get(1)).close();
      verify(schedulers.constructed().get(1)).shutdown();
    }
  }
}
