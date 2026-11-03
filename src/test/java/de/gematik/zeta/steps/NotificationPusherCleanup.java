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

import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.zeta.services.NotificationKvnrPool.Reservation;
import de.gematik.zeta.services.TestDriverConfigurationService;
import io.restassured.http.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Removes the pushers of one exclusively reserved pool identity through the testdriver SDK.
 * The protected list request must carry that identity before any destructive operation is allowed.
 */
public class NotificationPusherCleanup {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final TestDriverConfigurationService driver;
  private final TigerProxyAccessTokenExtractor tokens;
  private final TigerProxyManipulationsSteps proxy;

  /**
   * Creates cleanup using the configured TigerProxy for identity verification.
   *
   * @param driver testdriver configuration client
   */
  public NotificationPusherCleanup(TestDriverConfigurationService driver) {
    this(driver, new TigerProxyAccessTokenExtractor());
  }

  /**
   * Creates cleanup with an explicit token observer for isolated tests.
   *
   * @param driver testdriver configuration client
   * @param tokens observer of freshly recorded protected requests
   */
  public NotificationPusherCleanup(TestDriverConfigurationService driver, TigerProxyAccessTokenExtractor tokens) {
    this(driver, tokens, new TigerProxyManipulationsSteps());
  }

  NotificationPusherCleanup(TestDriverConfigurationService driver, TigerProxyAccessTokenExtractor tokens,
      TigerProxyManipulationsSteps proxy) {
    this.driver = driver;
    this.tokens = tokens;
    this.proxy = proxy;
  }

  /**
   * Reauthenticates the reserved identity, deletes its current pushers and verifies the empty list.
   * Channel subscriptions disappear with removal of the last pusher; no global channels are changed.
   *
   * @param identity exclusively reserved notification identity
   */
  public void clean(Reservation identity) {
    proxy.stopAllTigerProxyManipulationsStrictly();
    driver.reset();
    // Reset forgets the SDK registration; its previous F1 may remain bound in Keycloak.
    driver.setKvnrEmail(identity.kvnr(), identity.newBindingEmail());
    var endpoint = TigerGlobalConfiguration.resolvePlaceholders("${paths.client.notifications.pushers}");
    var pushers = list(endpoint, identity.kvnr());
    var failure = new AssertionError("Notification cleanup failed for reserved KVNR " + identity.kvnr() + ".");
    for (var pusher : pushers) {
      try {
        var separator = endpoint.contains("?") ? "&" : "?";
        var uri = URI.create(endpoint + separator + "pushkey=" + encode(pusher.pushkey())
            + "&appId=" + encode(pusher.appId()));
        var response = TigerHttpClient.givenDefaultSpec().request(Method.DELETE, uri);
        requireSuccess(response.statusCode(), "Deleting pusher " + pusher.pushkey());
      } catch (RuntimeException | AssertionError e) {
        failure.addSuppressed(e);
      }
    }
    try {
      if (!list(endpoint, identity.kvnr()).isEmpty()) {
        throw new AssertionError("Pushers remain after cleanup for " + identity.kvnr() + ".");
      }
    } catch (RuntimeException | AssertionError e) {
      failure.addSuppressed(e);
    }
    if (failure.getSuppressed().length > 0) {
      throw failure;
    }
  }

  private List<Pusher> list(String endpoint, String expectedKvnr) {
    var before = tokens.captureMessageIds();
    var response = TigerHttpClient.givenDefaultSpec().request(Method.GET, URI.create(endpoint));
    requireSuccess(response.statusCode(), "Listing notification pushers");
    var token = tokens.extractLatestAccessToken(before,
        TigerGlobalConfiguration.resolvePlaceholders("${paths.guard.notificationPushersPath}"));
    try {
      var parts = token.split("\\.", -1);
      if (parts.length != 3) {
        throw new AssertionError("Cannot verify notification cleanup identity: malformed Access Token.");
      }
      var claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      if (!expectedKvnr.equals(claims.path("sub").asText())) {
        throw new AssertionError("Refusing cleanup: protected pusher request does not belong to " + expectedKvnr + ".");
      }
      var body = JSON.readTree(response.asString());
      if (body == null || !body.isArray()) {
        throw new AssertionError("The testdriver pusher response must be a JSON array.");
      }
      var result = new ArrayList<Pusher>();
      for (var item : body) {
        var pushkey = item.path("pushkey");
        var appId = item.path("app_id");
        if (!pushkey.isTextual() || pushkey.asText().isBlank() || !appId.isTextual() || appId.asText().isBlank()) {
          throw new AssertionError("The testdriver returned a pusher without pushkey/app_id.");
        }
        result.add(new Pusher(pushkey.asText(), appId.asText()));
      }
      return result;
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw new AssertionError("Could not validate notification cleanup identity or pusher response.", e);
    }
  }

  private static void requireSuccess(int status, String operation) {
    if (status < 200 || status >= 300) {
      throw new AssertionError(operation + " returned HTTP " + status + ".");
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record Pusher(String pushkey, String appId) {}
}
