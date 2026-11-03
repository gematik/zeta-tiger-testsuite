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

package de.gematik.zeta.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Regression tests for nginx syntax, location selection and independently read configuration dumps. */
class NginxRouteConfigurationTest {

  // Rendered body used by the maintained achelos proxyLocations Helm template.
  private static final String ACHELOS_BODY = """
      proxy_pass https://pep-pep-upstream/testfachdienst/;
      ## credential strips + enforcement sentinel
      include proxy_headers.conf;
      ## with an `upstream {}` block, nginx's default Host ($proxy_host) is the
      ## upstream block NAME — Tomcat rejects the underscores with HTTP 400.
      ## Send the real backend host instead.
      proxy_set_header Host "tiger-proxy:80";
      ## required for upstream keepalive (and for WebSocket upgrades)
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      ## SNI for pooled TLS upstream connections
      proxy_ssl_server_name on;
      proxy_ssl_name tiger-proxy;
      """;
  private static final String ACHELOS_CONFIG = "server { location = /pep { " + ACHELOS_BODY
      + " } location /pep/ { " + ACHELOS_BODY + " } }";

  @ParameterizedTest
  @ValueSource(strings = {"pep_require_popp on;", "pep_forward_client_data on;", "pep_require_scope \"email zero:audience\";"})
  void patchesCompleteAchelosLocation(String directive) {
    var patched = patch(ACHELOS_CONFIG, "/pep/", directive);
    int closingBrace = ACHELOS_CONFIG.lastIndexOf("} }");
    assertEquals(ACHELOS_CONFIG.substring(0, closingBrace) + "\n    " + directive + "\n"
        + ACHELOS_CONFIG.substring(closingBrace), patched);
    assertTrue(patched.contains("location = /pep { " + ACHELOS_BODY + " }"));
    assertEquals(1, occurrences(patched, directive));
    assertEquals(2, occurrences(patched, "proxy_http_version 1.1;"));
    verify(patched, "/pep/", directive);
    assertEquals(patched, patch(patched, "/pep/", directive));
  }

  /** Preserves exact child routes while removing their generic prefix fallback. */
  @Test
  void genericPrefixRestrictionKeepsExplicitChildMapping() {
    var config = "server { location /pep/ { proxy_pass http://rs/; } "
        + "location = /pep/known { proxy_pass http://rs/known; } }";
    var patched = NginxRouteConfiguration.restrictPrefixToExact(config, "/pep/");
    assertTrue(patched.contains("location = /pep/ {"));
    assertTrue(patched.contains("location = /pep/known { proxy_pass http://rs/known; }"));
    NginxRouteConfiguration.requireExactDumpLocation("# configuration file /etc/nginx/nginx.conf:\n" + patched, "/pep/");
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.requireExactDumpLocation(
        "# configuration file /etc/nginx/nginx.conf:\n" + config, "/pep/"));
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.restrictPrefixToExact(config, "/missing/"));
  }

  /** Removes a generic fallback while preserving the existing exact route and explicit children. */
  @ParameterizedTest
  @ValueSource(strings = {"", "^~ "})
  void genericPrefixRestrictionPreservesExistingExactLocation(String modifier) {
    var exact = "location = /pep/ { proxy_pass http://rs/exact; }";
    var child = "location = /pep/known { proxy_pass http://rs/known; }";
    var config = "server { " + exact + " location " + modifier
        + "/pep/ { proxy_pass http://rs/fallback; } " + child + " }";
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.requireExactDumpLocation(
        "# configuration file /etc/nginx/nginx.conf:\n" + config, "/pep/"));
    var patched = NginxRouteConfiguration.restrictPrefixToExact(config, "/pep/");
    assertTrue(patched.contains(exact));
    assertTrue(patched.contains(child));
    assertFalse(patched.contains("http://rs/fallback"));
    assertEquals(patched, NginxRouteConfiguration.restrictPrefixToExact(patched, "/pep/"));
    NginxRouteConfiguration.requireExactDumpLocation("# configuration file /etc/nginx/nginx.conf:\n" + patched, "/pep/");
  }

  /** Verifies literal upstream replacement independently of the route audience. */
  @Test
  void staticTargetChangesRetainTheRouteAudience() {
    var original = "server { location = /pep/resource { pep_require_aud service; proxy_pass http://rs/original; } }";
    var directive = ZetaDeploymentModificationService.staticRoutingDirective("http://rs/changed");
    var patched = NginxRouteConfiguration.patch(original, "/pep/resource", "proxy_pass.*;", directive, "/pep/", "/rs");
    assertTrue(patched.contains("pep_require_aud service;"));
    assertTrue(patched.contains("proxy_pass http://rs/changed;"));
    assertFalse(patched.contains("http://rs/original"));
    NginxRouteConfiguration.requireDumpDirective("# configuration file /etc/nginx/nginx.conf:\n" + patched,
        "/pep/resource", "proxy_pass.*;", directive);
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.requireDumpDirective(
        "# configuration file /etc/nginx/nginx.conf:\n" + original, "/pep/resource", "proxy_pass.*;", directive));
  }

  /** Rejects token-derived upstreams and nginx syntax in a static target. */
  @ParameterizedTest
  @ValueSource(strings = {"http://$aud/path", "http://rs/path;return 200;", "http://rs/quoted\"", "http://rs/path?q=x"})
  void staticTargetRejectsDynamicOrInjectedUrls(String upstream) {
    assertThrows(IllegalArgumentException.class, () -> ZetaDeploymentModificationService.staticRoutingDirective(upstream));
  }

  @Test
  void quotedBracesHashesEscapedQuotesAndVariablesRemainArguments() {
    var config = """
        server { location "/pep/" {
          proxy_set_header X-Text "# } { ; \\" quoted";
          proxy_set_header X-Single '# } {';
          proxy_set_header X-Variable ${request_id};
          # pep_require_popp on; location /pep/ {}
          pep_require_popp off;
        } }
        """;
    var patched = patch(config, "/pep/", "pep_require_popp on;");
    assertEquals(config.replace("pep_require_popp off;", "pep_require_popp on;"), patched);
    verify(patched, "/pep/", "pep_require_popp on;");
  }

  @Test
  void directiveTextInsideQuotedArgumentsDoesNotCount() {
    var config = "server { location /pep/ { add_header X-Text \"pep_require_popp on; }\"; } }";
    assertThrows(AssertionError.class, () -> verify(config, "/pep/", "pep_require_popp on;"));
    verify(patch(config, "/pep/", "pep_require_popp on;"), "/pep/", "pep_require_popp on;");
  }

  @Test
  void nestedBlocksAndEndCommentsDoNotTruncateParent() {
    var config = """
        server {
          location /other/ { if ($arg_test) { add_header X-Text "nested }"; } }
          location /pep/ {
            # final comment with a closing brace }
          }
        }
        """;
    var patched = patch(config, "/pep/", "pep_forward_client_data on;");
    assertTrue(patched.contains("if ($arg_test) { add_header X-Text \"nested }\"; }"));
    verify(patched, "/pep/", "pep_forward_client_data on;");
  }

  /**
   * Verifies credential header stripping leaves the selected route unchanged.
   *
   * @param scope scope of the conditional header directives
   */
  @ParameterizedTest
  @ValueSource(strings = {"server", "location"})
  void credentialHeaderStrippingDoesNotBlockRouteConfiguration(String scope) {
    var guards = new StringBuilder();
    for (var header : List.of("authorization", "dpop", "popp")) {
      guards.append("if ($zeta_strip_").append(header).append(") { set $zeta_")
          .append(header).append(" \"\"; } ");
    }
    var config = scope.equals("server") ? "server { " + guards + " location /pep/ {} }"
        : "server { location /pep/ { " + guards + "} }";
    verify(patch(config, "/pep/", "pep_require_popp on;"), "/pep/", "pep_require_popp on;");
  }

  /**
   * Verifies unrecognized conditionals cannot hide routing or credential changes.
   *
   * @param guard unsupported conditional block
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "if ($arg_test) { set $zeta_authorization \"\"; }",
      "if ($zeta_strip_authorization) { set $uri \"\"; }",
      "if ($zeta_strip_authorization) { set $zeta_authorization other; }",
      "if ($zeta_strip_authorization) { set $zeta_authorization \"\"; return 301 /other/; }"})
  void uncertainHeaderConditionalsCannotPass(String guard) {
    for (var config : List.of("server { " + guard + " location /pep/ {} }",
        "server { location /pep/ { " + guard + "} }")) {
      assertThrows(AssertionError.class, () -> patch(config, "/pep/", "pep_require_popp on;"));
    }
  }

  @Test
  void nestedLocationsAreExplicitlyUnsupported() {
    var config = "server { location /pep/ { location /pep/child { pep_require_popp on; } } }";
    for (var route : new String[]{"/pep/", "/pep/child", "/pep/derived"}) {
      var error = assertThrows(AssertionError.class, () -> patch(config, route, "pep_require_popp on;"));
      assertTrue(error.getMessage().contains("Unsupported nested nginx block"));
      assertThrows(AssertionError.class, () -> verify(config, route, "pep_require_popp on;"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"rewrite ^ /other/ last;", "try_files $uri /other/;", "error_page 403 /other/;", "return 301 /other/;"})
  void routeChangesAreExplicitlyUnsupported(String routing) {
    var config = "server { location /pep/ { " + routing + " pep_require_popp on; } }";
    var error = assertThrows(AssertionError.class, () -> patch(config, "/pep/", "pep_require_popp on;"));
    assertTrue(error.getMessage().contains("Unsupported nginx routing"));
    assertThrows(AssertionError.class, () -> verify(config, "/pep/", "pep_require_popp on;"));
  }

  @Test
  void serverRewriteCannotBypassTheTargetLocation() {
    var config = "server { rewrite ^ /other/ last; location /pep/ { pep_require_popp on; } }";
    assertThrows(AssertionError.class, () -> patch(config, "/pep/", "pep_require_popp on;"));
    assertThrows(AssertionError.class, () -> verify(config, "/pep/", "pep_require_popp on;"));
  }

  @Test
  void conditionalBlocksAreExplicitlyUnsupported() {
    for (var statement : new String[]{"pep_require_popp off;", "return 204;"}) {
      var config = "server { location /pep/ { if ($arg_test) { " + statement + " } } }";
      var error = assertThrows(AssertionError.class, () -> patch(config, "/pep/", "pep_require_popp on;"));
      assertTrue(error.getMessage().contains("Unsupported nested nginx block"));
      assertThrows(AssertionError.class, () -> verify(config, "/pep/", "pep_require_popp off;"));
    }
  }

  @Test
  void emptyLocationGetsDirectiveDespiteMissingPreviousSemicolon() {
    var patched = patch("server { location /pep/ {} }", "/pep/", "pep_require_popp off;");
    verify(patched, "/pep/", "pep_require_popp off;");
  }

  @Test
  void selectsExactOverSamePathPrefix() {
    var config = "server { location /pep/ { pep_require_popp off; } location = /pep/ { pep_require_popp off; } }";
    var patched = patch(config, "/pep/", "pep_require_popp on;");
    assertTrue(patched.contains("location /pep/ { pep_require_popp off; }"));
    verify(patched, "/pep/", "pep_require_popp on;");
  }

  @Test
  void supportsCaretPrefixAndRejectsRegexShadowing() {
    var config = "server { location ^~ /pep/ {} location ~ ^/pep/ { pep_require_popp off; } }";
    verify(patch(config, "/pep/", "pep_require_popp on;"), "/pep/", "pep_require_popp on;");
    assertThrows(AssertionError.class, () -> patch(config.replace("^~ ", ""), "/pep/", "pep_require_popp on;"));
  }

  @Test
  void regexEscapesArePreservedForLocationSelection() {
    var config = "server { location /pep/1 { pep_require_popp on; } location ~ ^/pep/\\d+$ { pep_require_popp off; } }";
    assertThrows(AssertionError.class, () -> verify(config, "/pep/1", "pep_require_popp on;"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"^/auth/admin", "^/auth/realms/zeta-guard/zeta/(clients|identity/email)(/|$)"})
  void disjointMaintainedRegexLocationsDoNotBlockPepConfiguration(String authLocation) {
    var config = "server { location ~ " + authLocation + " { return 403; } location ~ ^/push/v1/channels/([^/]+)$ { return 200; } location /pep/ {} }";
    verify(patch(config, "/pep/", "pep_require_popp on;"), "/pep/", "pep_require_popp on;");
  }

  @ParameterizedTest
  @ValueSource(strings = {"^/auth/|^/pep/", "[[:alpha:]]", "^/pe[p]?/", "(?i)^/PEP/", "^/auth/*", "^/auth/{0}pep/",
      "^/auth/(clients|identity/email)|^/pep/", "^/auth/(clients|identity/email",
      "^/auth/clients)|^/pep/", "^/auth/\\Q(\\E|^/pep/\\Q)\\E"})
  void uncertainPcreSelectionCannotPass(String expression) {
    var config = "server { location /pep/ { pep_require_popp on; } location ~ \"" + expression + "\" { pep_require_popp off; } }";
    var error = assertThrows(AssertionError.class, () -> verify(config, "/pep/", "pep_require_popp on;"));
    assertTrue(error.getMessage().contains("Unsupported nginx regex routing"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pep_require_popp on;", "pep_forward_client_data on;", "pep_require_scope \"email\";"})
  void derivedEndpointKeepsAchelosTransportConfiguration(String directive) {
    var route = "/pep/achelos_testfachdienst/hellozeta";
    var patched = patch(ACHELOS_CONFIG, route, directive);
    assertTrue(patched.contains("location = " + route + " {"));
    assertTrue(patched.contains("proxy_pass https://pep-pep-upstream/testfachdienst/achelos_testfachdienst/hellozeta;"));
    assertEquals(3, occurrences(patched, "proxy_set_header Host \"tiger-proxy:80\";"));
    assertEquals(3, occurrences(patched, "proxy_http_version 1.1;"));
    assertTrue(patched.endsWith(ACHELOS_CONFIG.substring(ACHELOS_CONFIG.indexOf("location /pep/"))));
    verify(patched, route, directive);
  }

  @ParameterizedTest
  @ValueSource(strings = {"server { location /other/ {} }", "server { location /pep/ { proxy_pass http://other/; } }",
      "server { location /pep/ { proxy_pass http://$upstream/testfachdienst/; } }"})
  void missingOrUnsupportedRoutesFailExplicitly(String config) {
    assertThrows(AssertionError.class, () -> patch(config, "/pep/hello", "pep_require_popp on;"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"server { location /pep/ {", "server { location /pep/ { return 200 \"unclosed; } }",
      "server { location /pep/ { pep_require_popp on } }", "server { location /pep/ {} } }"})
  void invalidSyntaxCannotPassPreparation(String config) {
    assertThrows(AssertionError.class, () -> patch(config, "/pep/", "pep_require_popp on;"));
  }

  @Test
  void duplicateServersOrDirectivesAreRejected() {
    assertThrows(AssertionError.class, () -> patch("server { location /pep/ {} } server { location = /pep/ {} }",
        "/pep/", "pep_require_popp on;"));
    assertThrows(AssertionError.class, () -> patch("location /pep/ { pep_require_popp on; pep_require_popp off; }",
        "/pep/", "pep_require_popp on;"));
  }

  @Test
  void simpleHeaderIncludeIsSupported() {
    verify("server { location /pep/ { include proxy_headers.conf; pep_require_popp on; } }", "/pep/", "pep_require_popp on;");
    verify("server { location /pep/ { include /etc/nginx/proxy_headers.conf; pep_require_popp on; } }", "/pep/", "pep_require_popp on;");
  }

  @ParameterizedTest
  @ValueSource(strings = {"pep_require_popp on;", "pep_forward_client_data on;", "pep_require_scope \"email\";", "pep_require_aud \"expected\";"})
  void standardPepIncludesSupportExistingAndDerivedRoutes(String directive) throws IOException {
    // Common include contents are copied from ngx_pep/misc/config, including its protocol guard.
    var dump = Files.readString(Path.of("src/test/resources/nginx/pep-standard.dump"));
    int start = dump.indexOf('\n') + 1;
    int end = dump.indexOf("# configuration file", start);
    var config = dump.substring(start, end);
    for (var route : new String[]{"/pep/", "/pep/achelos_testfachdienst/hellozeta"}) {
      var patched = patch(config, route, directive);
      var deployed = dump.substring(0, start) + patched + dump.substring(end);
      NginxRouteConfiguration.requireDumpDirective(deployed, route, regex(directive), directive);
      assertEquals(patched, patch(patched, route, directive));
    }
  }

  @Test
  void nestedLiteralIncludeSuppliesDirectiveInItsActualLocation() {
    var dump = """
        # configuration file /etc/nginx/nginx.conf:
        server { location /pep/ { include conf.d/settings.conf; } }
        # configuration file /etc/nginx/conf.d/settings.conf:
        include popp.conf;
        # configuration file /etc/nginx/popp.conf:
        pep_require_popp on;
        # configuration file /etc/nginx/unused.conf:
        pep_require_popp off;
        """;
    NginxRouteConfiguration.requireDumpDirective(dump, "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;");
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.requireDumpDirective(dump.replace("include conf.d/settings.conf;", ""),
        "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"location = /pep/ { pep_require_popp off; }", "location /pep/ { pep_require_popp on; }",
      "location ~ ^/pep/ { pep_require_popp off; }", "rewrite ^ /other/ last;", "if ($arg_test) { return 302 /other/; }",
      "if ($arg_test) { pep_require_popp off; }"})
  void includedServerRoutingCannotHideAConflictingTarget(String routing) {
    var dump = """
        # configuration file /etc/nginx/nginx.conf:
        server { include server_common.conf; location /pep/ { pep_require_popp on; } }
        # configuration file /etc/nginx/server_common.conf:
        """ + routing;
    assertThrows(AssertionError.class,
        () -> NginxRouteConfiguration.requireDumpDirective(dump, "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"conf.d/*.conf", "conf.d/?.conf", "$settings", "../settings.conf"})
  void unsupportedIncludePathsFailBeforePatching(String path) {
    var config = "include " + path + "; server { location /pep/ {} }";
    var error = assertThrows(AssertionError.class, () -> patch(config, "/pep/", "pep_require_popp on;"));
    assertTrue(error.getMessage().contains("Unsupported nginx include"));
    assertThrows(AssertionError.class, () -> verify(config, "/pep/", "pep_require_popp on;"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"location /pep/child {}", "server {}", "rewrite ^ /other/ last;",
      "try_files $uri /other/;", "error_page 403 /other/;", "return 403;"})
  void includedTargetRoutingIsExplicitlyUnsupported(String content) {
    var dump = dump("server { location /pep/ { include proxy_headers.conf; pep_require_popp on; } }")
        .replace("proxy_set_header Authorization \"\";", content);
    var error = assertThrows(AssertionError.class,
        () -> NginxRouteConfiguration.requireDumpDirective(dump, "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;"));
    assertTrue(error.getMessage().contains("Unsupported") && error.getMessage().contains("nginx"));
  }

  @Test
  void includedConflictingDirectiveCannotPass() {
    for (var value : new String[]{"on", "off"}) {
      var dump = dump("server { location /pep/ { include proxy_headers.conf; pep_require_popp on; } }")
          .replace("proxy_set_header Authorization \"\";", "pep_require_popp " + value + ";");
      var error = assertThrows(AssertionError.class,
          () -> NginxRouteConfiguration.requireDumpDirective(dump, "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;"));
      assertTrue(error.getMessage().contains("not effective"));
    }
  }

  @Test
  void missingAndRecursiveIncludesFailClosed() {
    assertThrows(AssertionError.class, () -> verify("server { location /pep/ { include missing.conf; pep_require_popp on; } }", "/pep/", "pep_require_popp on;"));
    assertThrows(AssertionError.class, () -> verify("include nginx.conf;", "/pep/", "pep_require_popp on;"));
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.requireDumpDirective("", "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;"));
  }

  @Test
  void nestedMissingFilesAndIncludeCyclesFailExplicitly() {
    var dump = """
        # configuration file /etc/nginx/nginx.conf:
        server { location /pep/ { include settings.conf; pep_require_popp on; } }
        # configuration file /etc/nginx/settings.conf:
        include missing.conf;
        """;
    var error = assertThrows(AssertionError.class,
        () -> NginxRouteConfiguration.requireDumpDirective(dump, "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;"));
    assertTrue(error.getMessage().contains("missing included file /etc/nginx/missing.conf"));
    var cycle = dump + "# configuration file /etc/nginx/missing.conf:\ninclude settings.conf;";
    error = assertThrows(AssertionError.class,
        () -> NginxRouteConfiguration.requireDumpDirective(cycle, "/pep/", regex("pep_require_popp on;"), "pep_require_popp on;"));
    assertTrue(error.getMessage().contains("Recursive nginx include"));
  }

  @Test
  void audienceAliasIsReplacedAndCannotRemainAsAnAlternative() {
    var regex = "(?m)^\\s*pep_require_aud(?:_any)?\\s+[^;]+;";
    var config = "server { location /pep/ { pep_require_aud_any \"old\"; } }";
    var patched = NginxRouteConfiguration.patch(config, "/pep/", regex, "pep_require_aud \"expected\";", "/pep/", "/testfachdienst");
    assertFalse(patched.contains("pep_require_aud_any"));
    NginxRouteConfiguration.requireDumpDirective(dump(patched), "/pep/", regex, "pep_require_aud \"expected\";");
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.requireDumpDirective(dump(patched.replace("pep_require_aud \"expected\";",
        "pep_require_aud \"expected\"; pep_require_aud_any \"other\";")), "/pep/", regex, "pep_require_aud \"expected\";"));
    assertThrows(AssertionError.class, () -> NginxRouteConfiguration.requireDumpDirective(dump(patched.replace("pep_require_aud \"expected\";",
        "pep_require_aud \"expected\"; if ($arg_test) { pep_require_aud_any \"other\"; }")), "/pep/", regex, "pep_require_aud \"expected\";"));
  }

  private static String patch(String config, String route, String directive) {
    return NginxRouteConfiguration.patch(config, route, regex(directive), directive, "/pep/", "/testfachdienst");
  }

  private static String regex(String directive) {
    return directive.substring(0, directive.indexOf(' ')) + "\\s+[^;]+;";
  }

  private static void verify(String config, String route, String directive) {
    NginxRouteConfiguration.requireDumpDirective(dump(config), route, regex(directive), directive);
  }

  private static String dump(String config) {
    return "# configuration file /etc/nginx/nginx.conf:\n" + config
        + "\n# configuration file /etc/nginx/proxy_headers.conf:\nproxy_set_header Authorization \"\";\n";
  }

  private static int occurrences(String text, String part) {
    return (text.length() - text.replace(part, "").length()) / part.length();
  }
}
