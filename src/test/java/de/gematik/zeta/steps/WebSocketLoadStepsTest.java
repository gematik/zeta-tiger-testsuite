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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.gematik.zeta.services.WebSocketLoadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Checks setup failure propagation and non-blocking final capacity assertions. */
class WebSocketLoadStepsTest {

  /** Releases any soft assertion state collected by a step. */
  @AfterEach
  void clearAssertions() {
    SoftAssertionsContext.reset();
  }

  /** Propagates insufficient capacity as a hard failure and retains hook cleanup. */
  @Test
  void setupFailureStopsTheStepAndCleanupRemainsAvailable() {
    var service = mock(WebSocketLoadService.class);
    doThrow(new AssertionError("Expected 320 connections but found 300"))
        .when(service).openStompConnections("ws://fixture/ws", 320);
    var steps = new WebSocketLoadSteps(service);
    assertThatThrownBy(() -> steps.openWebSocketConnections(320, "ws://fixture/ws"))
        .isInstanceOf(AssertionError.class).hasMessageContaining("found 300");
    steps.cleanupAfterScenario();
    verify(service).close();
  }

  /** Retains non-blocking reporting of lost capacity after the load run. */
  @Test
  void finalCapacityCheckStillCollectsSoftFailures() {
    var service = mock(WebSocketLoadService.class);
    when(service.getOpenConnectionCount()).thenReturn(300);
    new WebSocketLoadSteps(service).assertOpenWebSocketConnections(301);
    assertThatThrownBy(SoftAssertionsContext::assertAll)
        .isInstanceOf(AssertionError.class).hasMessageContaining("found 300");
  }
}
