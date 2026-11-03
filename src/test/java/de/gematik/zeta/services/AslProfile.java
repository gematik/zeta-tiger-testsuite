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

import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import java.net.URI;
import java.util.Set;

/** Reads the ASL mode advertised for the resource under test, without changing the cluster. */
public final class AslProfile {
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final Set<String> TAGS = Set.of("@asl_off", "@asl_pep", "@asl_rs");

  /** Prevents construction of this utility. */
  private AslProfile() {}

  /** Returns the requested mode tag, rejecting contradictory scenario tags. */
  public static String selectedTag(Set<String> tags) {
    var selected = tags.stream().filter(TAGS::contains).toList();
    if (selected.size() > 1) {
      throw new AssertionError("A scenario must select exactly one ASL mode");
    }
    return selected.isEmpty() ? null : selected.getFirst();
  }

  /** Reads public resource metadata through Tiger, avoiding virtual-host route fallthrough. */
  public static String activeTag() {
    var origin = "https://" + config("zeta_base_url");
    var discoveryUrl = origin + config("paths.guard.wellKnownOAuthProtectedResourcePath");
    var timeout = HttpClientConfig.httpClientConfig()
        .setParam("http.connection.timeout", 5000)
        .setParam("http.socket.timeout", 10000);
    var response = TigerHttpClient.givenDefaultSpec()
        .config(RestAssured.config().httpClient(timeout))
        .get(discoveryUrl);
    if (response.statusCode() != 200) {
      throw new AssertionError("Cannot determine ASL profile: " + discoveryUrl
          + " returned HTTP " + response.statusCode());
    }
    return tagFromMetadata(response.asString(), origin + config("paths.guard.helloZetaPath"));
  }

  /** Validates resource identity and maps only unambiguous advertised modes. */
  public static String tagFromMetadata(String metadata, String resourceUrl) {
    try {
      var document = JSON.readTree(metadata);
      var resource = URI.create(document.path("resource").asText());
      var target = URI.create(resourceUrl);
      if (!"https".equals(resource.getScheme())
          || !java.util.Objects.equals(resource.getAuthority(), target.getAuthority())
          || resource.getQuery() != null || resource.getFragment() != null
          || !(target.getPath().equals(resource.getPath())
              || target.getPath().startsWith(resource.getPath().endsWith("/")
                  ? resource.getPath() : resource.getPath() + "/"))) {
        throw new AssertionError("Resource metadata does not describe the resource under test");
      }
      return switch (document.path("zeta_asl_use").asText()) {
        case "not_supported" -> "@asl_off";
        case "required" -> "@asl_pep";
        case "required_passthrough" -> "@asl_rs";
        default -> throw new AssertionError("Missing or ambiguous zeta_asl_use; refusing to infer ASL mode");
      };
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw new AssertionError("Invalid resource metadata; ASL mode cannot be determined", e);
    }
  }

  /** Resolves a mandatory suite configuration value. */
  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("Missing ASL configuration: " + key));
  }
}
