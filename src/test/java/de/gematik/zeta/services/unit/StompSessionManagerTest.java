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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.gematik.zeta.services.StompSessionManager;
import de.gematik.zeta.services.WebSocketClientFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Unit tests for {@link StompSessionManager}. */
class StompSessionManagerTest {

  /**
   * Verifies that subscriptions are tracked after the STOMP client accepts the subscription.
   */
  @Test
  void subscribeTracksSubscriptionWithoutRequiringReceipt() {
    var session = mock(StompSession.class);
    var subscription = mock(StompSession.Subscription.class);
    when(session.isConnected()).thenReturn(true);
    when(session.subscribe(any(StompHeaders.class), any())).thenReturn(subscription);
    var manager = new StompSessionManager(new WebSocketClientFactory());
    ReflectionTestUtils.setField(manager, "session", session);

    manager.subscribe("/user/queue/erezept", "sub-crud");

    @SuppressWarnings("unchecked")
    var activeSubscriptions =
        (java.util.Map<String, String>) ReflectionTestUtils.getField(manager, "activeSubscriptions");
    assertThat(activeSubscriptions).containsEntry("sub-crud", "/user/queue/erezept");

    var headersCaptor = ArgumentCaptor.forClass(StompHeaders.class);
    verify(session).subscribe(headersCaptor.capture(), any());
    assertThat(headersCaptor.getValue().getReceipt()).isNull();
  }

  /**
   * Verifies that a delayed callback from a timed-out retry cannot replace the active session.
   */
  @Test
  void retryKeepsSuccessfulSessionWhenPreviousAttemptConnectsLate() {
    var firstClient = mock(WebSocketStompClient.class);
    var secondClient = mock(WebSocketStompClient.class);
    var clientFactory = mock(WebSocketClientFactory.class);
    when(clientFactory.create()).thenReturn(firstClient, secondClient);

    var firstSession = mock(StompSession.class);
    var secondSession = mock(StompSession.class);
    when(firstSession.isConnected()).thenReturn(true);
    when(secondSession.isConnected()).thenReturn(true);

    var staleFirstHandler = new AtomicReference<StompSessionHandler>();
    when(firstClient.connectAsync(anyString(), any(WebSocketHttpHeaders.class),
        any(StompHeaders.class), any(StompSessionHandler.class)))
        .thenAnswer(invocation -> {
          staleFirstHandler.set(invocation.getArgument(3));
          return new CompletableFuture<StompSession>();
        });
    when(secondClient.connectAsync(anyString(), any(WebSocketHttpHeaders.class),
        any(StompHeaders.class), any(StompSessionHandler.class)))
        .thenAnswer(invocation -> {
          var handler = invocation.<StompSessionHandler>getArgument(3);
          handler.afterConnected(secondSession, new StompHeaders());
          return CompletableFuture.completedFuture(secondSession);
        });

    var manager = new StompSessionManager(clientFactory);
    StompSessionManager.setConnectionTimeout(0);
    try {
      ReflectionTestUtils.invokeMethod(manager, "connectStompInternal", "ws://localhost/ws");
      staleFirstHandler.get().afterConnected(firstSession, new StompHeaders());

      assertThat(manager.getSession()).isSameAs(secondSession);
    } finally {
      StompSessionManager.setConnectionTimeout(5);
    }
  }
}
