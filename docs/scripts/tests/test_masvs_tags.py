#
# #%L
# ZETA Testsuite
# %%
# (C) achelos GmbH, 2025, licensed for gematik GmbH
# %%
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# *******
#
# For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
# #L%
#

from __future__ import annotations

from pathlib import Path

from testsuite_docs.masvs_tags import load_masvs_mapping, tag_masvs_scenarios


def test_tags_scenarios_from_csv_mapping(tmp_path: Path) -> None:
  features_root = tmp_path / "src/test/resources/features"
  feature_path = features_root / "UserStory_01/UseCase_01/auth.feature"
  feature_path.parent.mkdir(parents=True)
  feature_path.write_text(
    """#language: de
Funktionalität: Auth

  @A_25660
  Szenario: Refresh Token wird ausgestellt
    Angenommen etwas ist vorbereitet
""",
    encoding="utf-8",
  )
  mapping_csv = tmp_path / "masvs.csv"
  mapping_csv.write_text("AFO,MASVS\nA_25660,AUTH\n", encoding="utf-8")

  mapping = load_masvs_mapping(mapping_csv)
  result = tag_masvs_scenarios(
    features_root=features_root,
    mapping=mapping,
    write=True,
  )

  assert result.inserted_tag_count == 1
  assert "  @MASVS-AUTH\n  Szenario" in feature_path.read_text(encoding="utf-8")
  proof = tag_masvs_scenarios(
    features_root=features_root,
    mapping=mapping,
    write=False,
  )
  assert proof.inserted_tag_count == 0


def test_uses_base_afo_fallback_for_unsuffixed_mapping_rows(tmp_path: Path) -> None:
  features_root = tmp_path / "src/test/resources/features"
  feature_path = features_root / "UserStory_01/UseCase_01/registration.feature"
  feature_path.parent.mkdir(parents=True)
  feature_path.write_text(
    """#language: de
Funktionalität: Registrierung

  @A_25644-01
  Szenario: Client wird registriert
    Angenommen etwas ist vorbereitet
""",
    encoding="utf-8",
  )
  mapping_csv = tmp_path / "masvs.csv"
  mapping_csv.write_text("AFO;MASVS\nA_25644;MASVS-AUTH\n", encoding="utf-8")

  mapping = load_masvs_mapping(mapping_csv)
  result = tag_masvs_scenarios(
    features_root=features_root,
    mapping=mapping,
    write=True,
  )

  assert result.inserted_tag_count == 1
  assert "@MASVS-AUTH" in feature_path.read_text(encoding="utf-8")


def test_accepts_heading_style_auth_category_from_csv() -> None:
  mapping = load_masvs_mapping(
    "-",
    stdin_text="AFO,MASVS\nA_25660,Authentification and Authorization\n",
  )

  assert mapping["A_25660"] == frozenset({"MASVS-AUTH"})


def test_check_mode_reports_missing_tags_without_writing(tmp_path: Path) -> None:
  features_root = tmp_path / "src/test/resources/features"
  feature_path = features_root / "UserStory_01/UseCase_01/auth.feature"
  feature_path.parent.mkdir(parents=True)
  original = """#language: de
Funktionalität: Auth

  @A_26450
  Szenario: PoPP Signatur wird geprueft
    Angenommen etwas ist vorbereitet
"""
  feature_path.write_text(original, encoding="utf-8")
  mapping = load_masvs_mapping("-", stdin_text="AFO,MASVS\nA_26450,CRYPTO\n")

  result = tag_masvs_scenarios(
    features_root=features_root,
    mapping=mapping,
    write=False,
  )

  assert result.inserted_tag_count == 1
  assert feature_path.read_text(encoding="utf-8") == original


def test_tags_examples_when_examples_have_mapped_afo_tags(tmp_path: Path) -> None:
  features_root = tmp_path / "src/test/resources/features"
  feature_path = features_root / "UserStory_01/UseCase_01/outline.feature"
  feature_path.parent.mkdir(parents=True)
  feature_path.write_text(
    """#language: de
Funktionalität: Outline

  Szenariogrundriss: Beispiele tragen AFO Tags
    Angenommen <wert> ist vorbereitet

    @A_27260
    Beispiele:
      | wert |
      | eins |
""",
    encoding="utf-8",
  )
  mapping = load_masvs_mapping("-", stdin_text="AFO,MASVS\nA_27260,PRIVACY\n")

  result = tag_masvs_scenarios(
    features_root=features_root,
    mapping=mapping,
    write=True,
  )

  assert result.inserted_tag_count == 1
  assert "    @MASVS-PRIVACY\n    Beispiele:" in feature_path.read_text(encoding="utf-8")
