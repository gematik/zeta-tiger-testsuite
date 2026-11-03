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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against selecting a profile for the wrong resource or guessing an unknown ASL mode. */
class AslProfileTest {
  private static final String TARGET = "https://guard.example/pep/resource";

  /** Accepts each explicit supported mode for the matching resource subtree. */
  @ParameterizedTest
  @CsvSource({"not_supported,@asl_off", "required,@asl_pep", "required_passthrough,@asl_rs"})
  void mapsAdvertisedModes(String mode, String tag) {
    assertEquals(tag, AslProfile.tagFromMetadata(metadata("https://guard.example/pep/", mode), TARGET));
  }

  /** Rejects metadata from another origin or a merely similar path prefix. */
  @ParameterizedTest
  @ValueSource(strings = {"https://foreign.example/pep/", "https://guard.example/pe",
      "http://guard.example/pep/", "https://guard.example/pep/?x=1", ""})
  void rejectsWrongResource(String resource) {
    assertThrows(AssertionError.class,
        () -> AslProfile.tagFromMetadata(metadata(resource, "required"), TARGET));
  }

  /** Unknown, optional and absent mode values must not silently select ASL-off. */
  @ParameterizedTest
  @ValueSource(strings = {"optional", "unsupported", "", "required_Passthrough"})
  void rejectsAmbiguousMode(String mode) {
    assertThrows(AssertionError.class,
        () -> AslProfile.tagFromMetadata(metadata("https://guard.example/pep/", mode), TARGET));
  }

  /** Rejects malformed discovery responses. */
  @Test
  void rejectsMalformedMetadata() {
    assertThrows(AssertionError.class, () -> AslProfile.tagFromMetadata("not json", TARGET));
    assertThrows(AssertionError.class, () -> AslProfile.tagFromMetadata(
        "{\"resource\":\"https://guard.example/pep/\",\"zeta_asl_use\":\"required\","
            + "\"zeta_asl_use\":\"not_supported\"}", TARGET));
  }

  /** Conflicting inherited tags must fail while untagged scenarios remain unaffected. */
  @Test
  void checksScenarioTags() {
    assertNull(AslProfile.selectedTag(Set.of("@normal")));
    assertEquals("@asl_rs", AslProfile.selectedTag(Set.of("@normal", "@asl_rs")));
    assertThrows(AssertionError.class, () -> AslProfile.selectedTag(Set.of("@asl_off", "@asl_pep")));
  }

  /** Builds discovery metadata without credentials. */
  private static String metadata(String resource, String mode) {
    return "{\"resource\":\"" + resource + "\",\"zeta_asl_use\":\"" + mode + "\"}";
  }
}
