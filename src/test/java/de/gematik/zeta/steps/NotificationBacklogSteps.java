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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.NotificationDispatchProfile;
import de.gematik.zeta.services.NotificationDispatchTraffic;
import de.gematik.zeta.services.NotificationDispatchTraffic.Observation;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import java.util.ArrayList;
import tools.jackson.databind.ObjectMapper;

/**
 * SDK-prepared recipients and bounded RS traffic against the actual NS queue.
 */
public class NotificationBacklogSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BACKLOG = "The notification dispatch queue is at capacity";
  private NotificationDispatchProfile profile;
  private NotificationDispatchTraffic traffic;
  private Observation control;
  private final ArrayList<Observation> operations = new ArrayList<>();
  private java.util.List<Observation> burst;

  /**
   * Configures only the explicitly authorized local NS test deployment.
   */
  @Gegebensei("ein isoliertes NS-Versandprofil mit einem Worker und einem Warteschlangenplatz")
  public void startProfile() {
    require(profile == null, "NS dispatch profile already exists");
    require(
        TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false),
        "NS dispatch profile requires explicitly enabled test deployment modification");
    profile =
        new NotificationDispatchProfile(
            ZetaDeploymentConfiguration.getServiceInstance(),
            ZetaDeploymentConfiguration.getNamespace(),
            config("testdata.ccNotification.rsDeployment"));
    profile.start();
  }

  /**
   * Sends only the positive control, before the queue is deliberately filled.
   */
  @Wenn("der RS für Nutzer {tigerResolvedString} eine Kontrollnachricht vor der NS-Überlastung einreicht")
  public void sendControl(String identity) {
    require(profile != null && traffic == null, "Missing dispatch profile or repeated control");
    traffic =
        new NotificationDispatchTraffic(
            config("paths.guard.notificationServiceRs") + "/notifications", identity);
    control = traffic.send();
    operations.add(control);
  }

  /**
   * Proves the active recipient and successful NS baseline before changing gateway availability.
   */
  @Dann("ist die Kontrollnachricht vor der NS-Überlastung erfolgreich angenommen")
  public void verifyControl() {
    require(
        control != null && control.status() == 202, "NS successful control failed before overload");
    verifyResponse(control.status(), control.body());
  }

  /**
   * Suspends only the explicitly configured gateway task, with restoration registered in the
   * profile.
   */
  @Wenn("das lokale Push-Gateway für die NS-Warteschlangenprüfung pausiert wird")
  public void pauseGateway() {
    require(profile != null, "NS dispatch profile was not started");
    profile.pauseGateway(
        config("testdata.ccNotification.pushGatewayDeployment"),
        config("testdata.ccNotification.kindCluster"));
  }

  /**
   * Stimulates one bounded parallel burst while the NS worker awaits the paused gateway.
   */
  @Wenn("der RS 16 Benachrichtigungen gleichzeitig in die NS-Warteschlange einreicht")
  public void submitBurst() throws Exception {
    require(traffic != null && burst == null, "Missing control or repeated burst");
    burst = traffic.burst();
    operations.addAll(burst);
  }

  /**
   * Requires an actual 503 caused by queue capacity, while checking every other burst response.
   */
  @Dann("belegt die NS-Antwortserie die volle Warteschlange mit mindestens einem HTTP 503")
  public void verifyUnavailable() {
    require(burst != null && burst.size() == 16, "Incomplete bounded NS burst");
    for (var observation : burst) {
      verifyResponse(observation.status(), observation.body());
    }
    var unavailable =
        burst.stream()
            .filter(o -> o.status() == 503)
            .reduce((a, b) -> b)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "The bounded test did not establish NS queue unavailability; this is not a"
                            + " passed 503 test"));
    TigerGlobalConfiguration.putValue(
        "CC_NS_UNAVAILABLE_CORRELATION",
        unavailable.correlation(),
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Resumes the gateway separately from observing subsequent queue recovery.
   */
  @Wenn("das lokale Push-Gateway nach der NS-Warteschlangenprüfung fortgesetzt wird")
  public void resumeGateway() {
    require(profile != null, "NS dispatch profile was not started");
    profile.resumeGateway();
  }

  /**
   * Repeats only the recovery probe within the original 20-second deadline.
   */
  @Dann("nimmt der NS innerhalb von 20 Sekunden wieder eine neue Benachrichtigung an")
  public void verifyRecovery() throws InterruptedException {
    require(traffic != null && burst != null, "NS burst was not submitted");
    var recovery = traffic.awaitRecovery(operations);
    for (var observation : operations) {
      verifyResponse(observation.status(), observation.body());
    }
    TigerGlobalConfiguration.putValue(
        "CC_NS_RECOVERY_CORRELATION",
        recovery.correlation(),
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Independently matches every observed HTTP status to exactly one actual NS access entry.
   */
  @Dann("stimmen alle bisherigen RS-Antworten mit eindeutig korrelierten NS-Zugriffslogs überein")
  public void verifyAccessLogs() {
    require(profile != null && !operations.isEmpty(), "No observed NS requests");
    var logs = profile.logs();
    for (var operation : operations) {
      NotificationDispatchProfile.verifyAccessLog(
          logs, operation.correlation(), operation.status());
    }
    SerenityReportUtils.addCustomData(
        "NS queue unavailability proof",
        "One worker, one queue slot. Every observed status matched exactly one NS access log"
            + " entry.\n"
            + operations);
  }

  /**
   * Restores the original NS environment even if setup or any assertion failed.
   */
  @After(value = "@ns_dispatch_profile", order = 100)
  public void restoreProfile() {
    if (profile != null) {
      profile.close();
      profile = null;
    }
  }

  static void verifyResponse(int status, String body) {
    tools.jackson.databind.JsonNode parsed;
    try {
      parsed = JSON.readTree(body);
    } catch (RuntimeException e) {
      throw new AssertionError("NS response is not JSON");
    }
    require(parsed != null && parsed.isObject(), "NS response must be an object");
    if (status == 202) {
      require(
          "accepted".equals(parsed.path("status").asString())
              && "zeta".equals(parsed.path("channel").asString()),
          "NS did not accept the notification for the active channel");
      require(
          parsed.path("notification_id").isTextual()
              && !parsed.path("notification_id").asString().isBlank(),
          "Missing accepted notification ID");
    } else {
      require(status == 503, "Unexpected NS response: " + status);
      require(
          "SERVICE_UNAVAILABLE".equals(parsed.path("errorCode").asString())
              && BACKLOG.equals(parsed.path("errorDetail").asString()),
          "NS error does not establish dispatch queue unavailability");
    }
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
