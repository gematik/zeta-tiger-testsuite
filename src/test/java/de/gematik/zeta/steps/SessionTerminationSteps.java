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

import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.KeycloakReadOnlyStore;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Targets a freshly created test session through Keycloak administration, never token revocation.
 */
public class SessionTerminationSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private String identity;
  private String targetClient;
  private String targetSession;
  private String controlClient;
  private String controlSession;
  private String userId;

  /**
   * Validates the SDK target independently of preparing a controlled resource response.
   */
  @Gegebensei("die Ressourcen-URL {tigerResolvedString} gehört zum konfigurierten SDK-Testdriver")
  public void requireSdkUrl(String sdkUrl) {
    var base = URI.create(global("paths.client.baseUrl"));
    var requested = URI.create(sdkUrl);
    require(
        base.getScheme().equals(requested.getScheme())
            && base.getAuthority().equals(requested.getAuthority())
            && requested.getRawUserInfo() == null
            && requested.getRawQuery() == null
            && requested.getRawFragment() == null,
        "Expected a resource URL on the configured SDK testdriver");
  }

  /**
   * Restores the feature's controlled resource responses even after an earlier failure.
   */
  @After("@controlled_session_refresh")
  public void cleanupManipulations() {
    new TigerProxyManipulationsSteps().stopAllTigerProxyManipulations();
  }

  /**
   * Binds independently signed access tokens and the latest unconsumed target refresh token.
   *
   * @param targetAccess target client's successful control-refresh access token
   * @param targetRefresh same control response's latest refresh token
   * @param controlAccess the second SDK context's independently issued access token
   * @param expectedIdentity freshly generated synthetic identity
   * @throws Exception if a recorded token is malformed
   */
  @Gegebensei("bereite die Session-Terminierung mit Access Token {tigerResolvedString}, Refresh Token {tigerResolvedString}, Kontrolltoken {tigerResolvedString} und KVNR {tigerResolvedString} vor")
  public void prepare(
      String targetAccess, String targetRefresh, String controlAccess, String expectedIdentity)
      throws Exception {
    var target = SignedJWT.parse(targetAccess).getJWTClaimsSet();
    var refresh = SignedJWT.parse(targetRefresh).getJWTClaimsSet();
    var control = SignedJWT.parse(controlAccess).getJWTClaimsSet();
    require(
        expectedIdentity.matches("[A-Z][0-9]{9}")
            && expectedIdentity.equals(target.getSubject())
            && expectedIdentity.equals(control.getSubject())
            && expectedIdentity.equals(refresh.getSubject()),
        "Sessions do not belong to the fresh synthetic identity");
    require(
        target.getIssuer().equals(control.getIssuer())
            && target.getIssuer().equals(refresh.getIssuer()),
        "Session tokens have different issuers");
    for (var claims : List.of(target, refresh, control)) {
      require(
          claims.getExpirationTime() != null
              && claims.getExpirationTime().toInstant().isAfter(Instant.now().plusSeconds(60)),
          "Session fixture token has insufficient remaining lifetime");
    }
    identity = expectedIdentity;
    targetClient = target.getStringClaim("client_id");
    targetSession = target.getStringClaim("sid");
    controlClient = control.getStringClaim("client_id");
    controlSession = control.getStringClaim("sid");
    for (var id : new String[] {targetClient, targetSession, controlClient, controlSession}) {
      safe(id);
    }
    require(
        !targetClient.equals(controlClient) && !targetSession.equals(controlSession),
        "Control must use a distinct client and session");
    require(
        targetClient.equals(refresh.getStringClaim("client_id"))
            && targetSession.equals(refresh.getStringClaim("sid")),
        "Latest refresh token is not bound to the target session/client");
    var snapshot = readState();
    verifyState(snapshot, identity, 1);
    userId = snapshot.path("user_id").asString();
    safe(userId);
    verifyAdminSessions(
        admin("list"), userId, targetSession, targetClient, controlSession, controlClient, true);
  }

  /**
   * Terminates only the prepared session, retaining fresh ownership and active-session prechecks.
   * No realm-wide/user-wide logout, client deletion or /revoke operation is performed.
   *
   * @throws Exception if the committed session state cannot be read in time
   */
  @Wenn("nur die vorbereitete Zielsession administrativ beendet wird")
  public void terminate() throws Exception {
    require(userId != null, "Session fixture has not been prepared");
    verifyState(readState(), identity, 1);
    verifyAdminSessions(
        admin("list"), userId, targetSession, targetClient, controlSession, controlClient, true);
    admin("delete");
  }

  /**
   * Verifies the original terminated session was not revived while the control remains active.
   *
   * @throws Exception if persistence has not caught up within the bounded poll
   */
  @Dann("die Zielsession bleibt terminiert und die unabhängige Kontrollsession bleibt aktiv")
  public void verifyTerminated() throws Exception {
    require(userId != null, "Session fixture has not been prepared");
    var deadline = Instant.now().plusSeconds(10);
    var row = readState();
    while (row.path("target_sessions").asInt(-1) != 0 && Instant.now().isBefore(deadline)) {
      Thread.sleep(200);
      row = readState();
    }
    verifyState(row, identity, 0);
    require(userId.equals(row.path("user_id").asString()), "Session deletion changed the identity");
    verifyAdminSessions(
        admin("list"), userId, targetSession, targetClient, controlSession, controlClient, false);
    SerenityReportUtils.addCustomData(
        "Targeted session termination",
        "Target session absent in admin API and committed storage; "
            + "control session and both client registrations retained. Target="
            + targetSession
            + ", control="
            + controlSession);
  }

  /**
   * Excludes authentication/proof errors and token expiry as alternative explanations of rejection.
   * The feature separately checks the exact submitted RT and the correlated HTTP rejection.
   *
   * @param body direct refresh error response
   * @param refresh exact latest unconsumed token recorded in that request
   * @throws Exception if the recorded refresh token is malformed
   */
  @Dann("die Session-Refresh-Antwort {tigerResolvedString} verweigert neue Token für das gültige Refresh Token {tigerResolvedString}")
  public void verifyRejection(String body, String refresh) throws Exception {
    var claims = SignedJWT.parse(refresh).getJWTClaimsSet();
    require(
        claims.getExpirationTime() != null
            && claims.getExpirationTime().toInstant().isAfter(Instant.now().plusSeconds(10)),
        "Rejected refresh token expired during the session test");
    verifyError(parse(body));
  }

  static void verifyError(JsonNode body) {
    require(
        body.isObject()
            && body.path("error").isString()
            && Set.of("invalid_grant", "refresh_token_revoked", "session_terminated")
                .contains(body.path("error").asString()),
        "Refresh was not rejected for its terminated grant");
    require(
        !body.has("access_token") && !body.has("refresh_token") && !body.has("id_token"),
        "Rejected refresh issued tokens");
  }

  static void verifyAdminSessions(
      JsonNode sessions,
      String user,
      String targetSid,
      String targetCid,
      String controlSid,
      String controlCid,
      boolean active) {
    require(sessions.isArray(), "Administrative session projection must be an array");
    var target =
        sessions.valueStream().filter(s -> targetSid.equals(s.path("id").asString())).toList();
    var control =
        sessions.valueStream().filter(s -> controlSid.equals(s.path("id").asString())).toList();
    require(
        target.size() == (active ? 1 : 0) && control.size() == 1,
        "Administrative target/control session states differ from the expected fixture");
    if (active) {
      verifyAdminBinding(target.getFirst(), user, targetCid);
    }
    verifyAdminBinding(control.getFirst(), user, controlCid);
  }

  private static void verifyAdminBinding(JsonNode session, String user, String client) {
    require(
        user.equals(session.path("userId").asString())
            && session.path("clients").isObject()
            && session
                .path("clients")
                .valueStream()
                .anyMatch(v -> v.isString() && client.equals(v.asString())),
        "Administrative session belongs to another user/client");
  }

  static void verifyState(JsonNode row, String owner, int targetCount) {
    require(
        row.isObject() && owner.equals(row.path("identity").asString()),
        "Session inventory belongs to another identity");
    for (var key :
        List.of("target_sessions", "control_sessions", "target_clients", "control_clients")) {
      require(
          row.path(key).isIntegralNumber()
              && row.path(key).asInt() == ("target_sessions".equals(key) ? targetCount : 1),
          "Unexpected persisted session/client inventory: " + key);
    }
  }

  private JsonNode readState() {
    return readQuery(
        stateQuery(
            config("realmName"),
            identity,
            targetClient,
            targetSession,
            controlClient,
            controlSession));
  }

  /**
   * Reads a single SDK grant's authoritative admin state; persistent row counts are diagnostics,
   * not a physical-purge requirement.
   */
  public JsonNode inspectServerSession(String client, String session) {
    var row = readQuery(singleSessionQuery(config("realmName"), client, session));
    require(
        row.isObject() && client.equals(row.path("client_id").asString()),
        "Logout session inventory does not identify the original client");
    userId = row.path("user_id").asString();
    safe(userId);
    targetSession = session;
    var sessions = admin("list");
    require(sessions.isArray(), "Expected the authoritative administrative session list");
    var matching =
        sessions.valueStream().filter(s -> session.equals(s.path("id").asString())).toList();
    require(matching.size() <= 1, "Ambiguous original session in the admin API");
    if (!matching.isEmpty()) {
      verifyAdminBinding(matching.getFirst(), userId, client);
    }
    return JSON.createObjectNode().put("admin_sessions", matching.size()).set("persisted", row);
  }

  static String singleSessionQuery(String realm, String client, String session) {
    for (var selector : List.of(realm, client, session)) {
      safe(selector);
    }
    return """
    BEGIN TRANSACTION READ ONLY; SET LOCAL statement_timeout='5s';
    SELECT json_build_object('client_id', c.client_id, 'user_id', u.id,
      'database_sessions', (SELECT count(*) FROM offline_user_session s
        WHERE s.realm_id=r.id AND s.user_session_id='%s' AND s.offline_flag='0'),
      'database_client_sessions', (SELECT count(*) FROM offline_client_session cs
        WHERE cs.realm_id=r.id AND cs.user_session_id='%s' AND cs.offline_flag='0'))
    FROM client c JOIN realm r ON r.id=c.realm_id JOIN zeta_client_data d ON d.id=c.id
    JOIN user_entity u ON u.username=d.user_id AND u.realm_id=r.id
    WHERE r.name='%s' AND c.client_id='%s'; COMMIT;
    """
        .formatted(session, session, realm, client);
  }

  private static JsonNode readQuery(String sql) {
    return parse(KeycloakReadOnlyStore.read(sql));
  }

  static String stateQuery(
      String realm,
      String owner,
      String targetCid,
      String targetSid,
      String controlCid,
      String controlSid) {
    for (var selector : List.of(realm, owner, targetCid, targetSid, controlCid, controlSid)) {
      safe(selector);
    }
    return """
    BEGIN TRANSACTION READ ONLY; SET LOCAL statement_timeout='5s';
    SELECT json_build_object('identity', (SELECT s.data::jsonb->'notes'->>'zetaguard.mobileuser.kvnr'
        FROM offline_user_session s WHERE s.realm_id=r.id AND s.user_id=u.id AND s.offline_flag='0' AND s.user_session_id='%s'),
      'user_id', u.id,
      'target_sessions', (SELECT count(*) FROM offline_user_session s JOIN offline_client_session cs
        ON cs.user_session_id=s.user_session_id AND cs.realm_id=s.realm_id AND cs.offline_flag=s.offline_flag
        JOIN client c ON c.id=cs.client_id AND c.realm_id=s.realm_id
        WHERE s.realm_id=r.id AND s.user_id=u.id AND s.offline_flag='0' AND s.user_session_id='%s' AND c.client_id='%s'),
      'control_sessions', (SELECT count(*) FROM offline_user_session s JOIN offline_client_session cs
        ON cs.user_session_id=s.user_session_id AND cs.realm_id=s.realm_id AND cs.offline_flag=s.offline_flag
        JOIN client c ON c.id=cs.client_id AND c.realm_id=s.realm_id
        WHERE s.realm_id=r.id AND s.user_id=u.id AND s.offline_flag='0' AND s.user_session_id='%s' AND c.client_id='%s'),
      'target_clients', (SELECT count(*) FROM client c JOIN zeta_client_data d ON d.id=c.id
        WHERE c.realm_id=r.id AND c.client_id='%s' AND d.user_id=u.username),
      'control_clients', (SELECT count(*) FROM client c JOIN zeta_client_data d ON d.id=c.id
        WHERE c.realm_id=r.id AND c.client_id='%s' AND d.user_id=u.username))
    FROM client target JOIN zeta_client_data d ON d.id=target.id
    JOIN user_entity u ON u.username=d.user_id AND u.realm_id=target.realm_id
    JOIN realm r ON r.id=u.realm_id WHERE r.name='%s' AND target.client_id='%s'; COMMIT;
    """
        .formatted(
            controlSid,
            targetSid,
            targetCid,
            controlSid,
            controlCid,
            targetCid,
            controlCid,
            realm,
            targetCid);
  }

  private JsonNode admin(String operation) {
    require(Set.of("list", "delete").contains(operation), "Unsupported scoped session operation");
    safe(config("realmName"));
    safe(userId);
    safe(targetSession);
    var server = URI.create(config("sessionAdmin.serverUrl"));
    require(
        "http".equals(server.getScheme())
            && "localhost".equals(server.getHost())
            && server.getRawUserInfo() == null,
        "The session administrator must use the in-container loopback server");
    var script =
        """
        set -eu
        umask 077
        probe_dir=$(mktemp -d /tmp/cc-session-check-XXXXXX)
        trap 'rm -f "$probe_dir/admin.config"; rmdir "$probe_dir"' EXIT
        /opt/keycloak/bin/kcadm.sh config credentials --server "$1" --realm master --user "$KEYCLOAK_ADMIN" \
          --password "$KEYCLOAK_ADMIN_PASSWORD" --config "$probe_dir/admin.config" >/dev/null 2>&1
        if [ "$5" = list ]; then
          /opt/keycloak/bin/kcadm.sh get "users/$3/sessions" -r "$2" --config "$probe_dir/admin.config" --fields 'id,userId,clients(*)'
        else
          /opt/keycloak/bin/kcadm.sh delete "sessions/$4" -r "$2" --config "$probe_dir/admin.config" >/dev/null 2>&1
          printf deleted
        fi
        """;
    var result =
        ZetaDeploymentConfiguration.getServiceInstance()
            .executeKubectlCommand(
                false,
                "-n",
                ZetaDeploymentConfiguration.getNamespace(),
                "exec",
                "deploy/" + config("sessionAdmin.deploymentName"),
                "-c",
                config("sessionAdmin.containerName"),
                "--",
                "timeout",
                "--signal=TERM",
                "--kill-after=2s",
                "25s",
                "sh",
                "-c",
                script,
                "cc-session-admin",
                server.toString(),
                config("realmName"),
                userId,
                targetSession,
                operation);
    require(
        result.exitCode() == 0,
        "Scoped session admin operation failed; no credential output is exposed");
    if ("delete".equals(operation)) {
      require(
          "deleted".equals(result.stdout().trim()),
          "Missing scoped session deletion command acknowledgement");
      return JSON.createObjectNode().put("deleted", true);
    }
    return parse(result.stdout());
  }

  static JsonNode parse(String output) {
    try {
      var value = JSON.readTree(output);
      require(value != null, "Missing session projection");
      return value;
    } catch (RuntimeException failure) {
      throw new AssertionError("Malformed session projection");
    }
  }

  private static void safe(String value) {
    require(
        value != null && value.matches("[A-Za-z0-9_.-]{1,128}"),
        "Unsafe/unresolved session selector");
  }

  private static String config(String key) {
    return global("zetaDeploymentConfig.keycloak." + key);
  }

  private static String global(String key) {
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
