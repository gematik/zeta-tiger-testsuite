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

package de.gematik.zeta.testdriver;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkCodeExpiryProbeTest {
  public interface Callback {
    Object authenticationCb(String client, String uri, Object continuation);

    Object configure(Object value);
  }

  @Test
  void waitsBeforeDelegatingExactlyTheOriginalArgumentsAndContinuation() throws Exception {
    var marker = new Object();
    var response = new Object();
    var waited = new boolean[1];
    Callback delegate = new Callback() {
      public Object authenticationCb(String client, String uri, Object continuation) {
        assertTrue(waited[0]);
        assertEquals("client", client);
        assertEquals("urn:test:original", uri);
        assertSame(marker, continuation);
        return response;
      }

      public Object configure(Object value) {
        return value;
      }
    };
    var method = Callback.class.getMethod("authenticationCb", String.class, String.class, Object.class);
    var evidence = new HashMap<>(Map.of("delayed_client", "client"));
    assertSame(response, SdkCodeExpiryProbe.invokeParCallback(delegate, method,
        new Object[] {"client", "urn:test:original", marker}, evidence, ms -> {
          assertEquals(6000, ms);
          waited[0] = true;
        }));
    assertEquals(64, evidence.get("par_callback_uri_sha").length());
    assertThrows(AssertionError.class, () -> SdkCodeExpiryProbe.invokeParCallback(delegate, method,
        new Object[] {"client", "urn:test:original", marker}, evidence, ms -> fail("Repeated wait")));
    assertSame(marker, SdkCodeExpiryProbe.invokeParCallback(delegate,
        Callback.class.getMethod("configure", Object.class), new Object[] {marker}, evidence,
        ms -> fail("Setup callback was delayed")));
  }

  @Test
  void rejectsForeignMissingAndRelativeCallbackContextBeforeNetworkDelegation() throws Exception {
    var method = Callback.class.getMethod("authenticationCb", String.class, String.class, Object.class);
    for (var args : new Object[][] {null, {}, {"foreign", "urn:test:original", new Object()},
        {"client", "relative", new Object()}, {"client", null, new Object()}}) {
      assertThrows(AssertionError.class, () -> SdkCodeExpiryProbe.invokeParCallback(null, method,
          args, new HashMap<>(Map.of("delayed_client", "client")), ms -> fail("Invalid delay")));
    }
  }

  @Test
  void propagatesInterruptedDelayBeforeDelegating() throws Exception {
    var method = Callback.class.getMethod("authenticationCb", String.class, String.class, Object.class);
    var evidence = new HashMap<>(Map.of("delayed_client", "client"));
    assertThrows(InterruptedException.class, () -> SdkCodeExpiryProbe.invokeParCallback(null,
        method, new Object[] {"client", "urn:test:original", new Object()}, evidence,
        ms -> {
          throw new InterruptedException("cancel");
        }));
    assertFalse(evidence.containsKey("delay_completed_ms"));
  }

  @Test
  void rejectsSecretsDuplicateFieldsAndTheObsoleteInnerCodeProtocol() {
    String outer = "protocol=sdk-par-uri-expiry-v1\n";
    String inner = "protocol=sdk-code-expiry-v1\n";
    assertDoesNotThrow(() -> SdkCodeExpiryProbe.parseParUriReport(outer + "par_auth_completed=false\n"));
    assertThrows(AssertionError.class, () -> SdkCodeExpiryProbe.parseParUriReport(inner));
    for (String extra : new String[] {"access_token=secret\n", "par_uri=urn:test\n",
        "identity=a\nidentity=b\n", "par_auth_completed=true with space\n",
        "control_started_ms=123\n", "control_completed_ms=456\n",
        "control_client=client\n", "control_access_sha=abc\n"}) {
      assertThrows(AssertionError.class, () -> SdkCodeExpiryProbe.parseParUriReport(outer + extra));
    }
  }
}
