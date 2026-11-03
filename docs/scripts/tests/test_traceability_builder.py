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

import json
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from testsuite_docs.traceability import builder
from testsuite_docs.traceability.__main__ import main


@pytest.mark.parametrize("tag", ["UseCase_Generic_01", "UseCase_GENERIC_01"])
def test_use_case_tags_resolve_to_explicit_anchor(tmp_path: Path, tag: str) -> None:
  """Resolve directory and anchor tag forms to the same documented UseCase."""
  use_case = tmp_path / "UserStory_Generic/UseCase_01"
  use_case.mkdir(parents=True)
  (use_case / "readme.adoc").write_text(
    "[#UC_GENERIC_01]\n== Generische Infrastrukturprüfungen\n", encoding="utf-8"
  )
  (use_case / "environment.feature").write_text(
    f"@{tag}\nFunktionalität: Infrastruktur\n\n  Szenario: Umgebung prüfen\n",
    encoding="utf-8",
  )
  other_use_case = tmp_path / "UserStory_01/UseCase_01"
  other_use_case.mkdir(parents=True)
  (other_use_case / "readme.adoc").write_text("[#UC_01_01]\n", encoding="utf-8")

  use_cases, scenarios = builder._parse_feature_files(
    tmp_path,
    user_story_anchors={},
    use_case_anchors=builder._load_use_case_anchors(tmp_path),
    known_requirement_ids=set(),
    known_test_aspect_requirements={},
  )

  assert set(use_cases) == {"UC_GENERIC_01"}
  assert len(scenarios) == 1
  assert scenarios[0].use_cases == {"UC_GENERIC_01"}


def test_build_traceability_covers_non_a_requirements_from_test_aspects(tmp_path: Path) -> None:
  _write_minimal_project(tmp_path)

  report = builder.build_traceability(
    project_root=tmp_path,
    write_outputs=False,
  )

  assert "GS-A_5526" in report.requirements
  assert "TA_GS-A_5526_01" in report.test_aspects
  assert report.test_aspects["TA_GS-A_5526_01"]["title"] == "TLS-Renegotiation-Indication-Extension"
  assert any(
    link.requirement == "GS-A_5526"
    and link.test_aspect == "TA_GS-A_5526_01"
    and link.implemented
    and link.use_case == "UseCase_01"
    for link in report.traceability
  )
  assert report.coverage_summary["requirements"]["vollständig abgedeckt"] == 1


def test_catalogue_selection_requires_documented_requirements(tmp_path: Path) -> None:
  _write_minimal_project(tmp_path)
  catalogue = tmp_path / builder.DEFAULT_XML
  tree = ET.parse(catalogue)
  requirement = ET.SubElement(tree.getroot(), "requirement", id="A_54321")
  ET.SubElement(requirement, "testProcedure").text = builder.DEFAULT_TEST_PROCEDURE
  tree.write(catalogue, encoding="utf-8")

  with pytest.raises(ValueError, match="Missing Asciidoc definitions.*A_54321"):
    builder.build_traceability(project_root=tmp_path, write_outputs=False)

  (tmp_path / "docs/asciidoc/afos/gemSpec_Krypt/A_54321.adoc").write_text(
    "==== A_54321 - Weitere Kataloganforderung\n", encoding="utf-8"
  )
  report = builder.build_traceability(project_root=tmp_path, write_outputs=False)
  assert set(report.requirements) == {"GS-A_5526", "A_54321"}
  assert report.coverage_summary["requirements"]["keine Testaspekte"] == 1


def test_catalogue_selection_deduplicates_ids_and_filters_procedures(tmp_path: Path) -> None:
  _write_minimal_project(tmp_path)
  catalogue = tmp_path / builder.DEFAULT_XML
  tree = ET.parse(catalogue)
  for identifier, procedure in [
    ("GS-A_5526", builder.DEFAULT_TEST_PROCEDURE),
    ("GS-A_5526", "Sicherheitsgutachten"),
    ("A_54321", "Sicherheitsgutachten"),
  ]:
    requirement = ET.SubElement(tree.getroot(), "requirement", id=identifier)
    ET.SubElement(requirement, "testProcedure").text = procedure
  tree.write(catalogue, encoding="utf-8")

  report = builder.build_traceability(project_root=tmp_path, write_outputs=False)
  assert set(report.requirements) == {"GS-A_5526"}


@pytest.mark.parametrize("identifier", ["GS-A_5542", "TIP1-A_6519", "TIP1-A_6519-01", "CUSTOM_REQUIREMENT"])
def test_keeps_additional_maintained_requirements(tmp_path: Path, identifier: str) -> None:
  _write_minimal_project(tmp_path)
  (tmp_path / f"docs/asciidoc/afos/gemSpec_Krypt/{identifier}.adoc").write_text(
    f"==== {identifier} - Zusätzliche Anforderung\n", encoding="utf-8"
  )
  report = builder.build_traceability(project_root=tmp_path, write_outputs=False)
  assert set(report.requirements) == {"GS-A_5526", identifier}


@pytest.mark.parametrize("missing", [True, False])
def test_rejects_missing_or_empty_catalogue(tmp_path: Path, missing: bool) -> None:
  _write_minimal_project(tmp_path)
  catalogue = tmp_path / builder.DEFAULT_XML
  if missing:
    catalogue.unlink()
  else:
    catalogue.write_text("<requirements/>", encoding="utf-8")

  with pytest.raises(FileNotFoundError if missing else ValueError):
    builder.build_traceability(project_root=tmp_path, write_outputs=False)


def test_parse_feature_files_uses_known_requirement_ids_without_a_regex(tmp_path: Path) -> None:
  feature_root = tmp_path / "src/test/resources/features"
  feature_path = feature_root / "UserStory_TLS/UseCase_01/tls_guard.feature"
  feature_path.parent.mkdir(parents=True)
  feature_path.write_text(
    """#language: de
@UseCase_TLS_01
Funktionalität: TLS Guard

  @GS-A_5526
  @TA_GS-A_5526_01
  Szenario: TLS-Renegotiation wird geprüft
    Angenommen etwas ist vorbereitet
""",
    encoding="utf-8",
  )

  _use_cases, scenarios = builder._parse_feature_files(
    feature_root,
    user_story_anchors={},
    use_case_anchors={},
    known_requirement_ids={"GS-A_5526"},
    known_test_aspect_requirements={"TA_GS-A_5526_01": "GS-A_5526"},
  )

  assert len(scenarios) == 1
  assert scenarios[0].requirements == {"GS-A_5526"}
  assert scenarios[0].test_aspects == {"TA_GS-A_5526_01"}


def test_parse_feature_files_warns_about_traceability_inconsistencies(
  tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
  feature_root = tmp_path / "src/test/resources/features"
  feature_path = feature_root / "UserStory_TLS/UseCase_01/tls_guard.feature"
  feature_path.parent.mkdir(parents=True)
  feature_path.write_text(
    """#language: de
Funktionalität: TLS Guard

  @GS-A_5526
  Szenario: Requirement ohne Testaspekt
    Angenommen etwas ist vorbereitet

  @A_0001
  @TA_GS-A_5526_01
  Szenario: Requirement passt nicht zum Testaspekt
    Angenommen etwas ist vorbereitet

  @GS-A_9999
  @TA_UNKNOWN_01
  Szenario: Unbekannte Tags
    Angenommen etwas ist vorbereitet
""",
    encoding="utf-8",
  )

  with caplog.at_level("WARNING", logger=builder.LOGGER.name):
    builder._parse_feature_files(
      feature_root,
      user_story_anchors={},
      use_case_anchors={},
      known_requirement_ids={"A_0001", "GS-A_5526"},
      known_test_aspect_requirements={"TA_GS-A_5526_01": "GS-A_5526"},
    )

  log_output = caplog.text
  assert "without a test aspect tag" in log_output
  assert "do not match test aspect parent requirement" in log_output
  assert "unknown requirement tag GS-A_9999" in log_output
  assert "unknown test aspect tag TA_UNKNOWN_01" in log_output


def test_parse_feature_files_aggregates_outline_example_tags_for_warnings(
  tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
  feature_root = tmp_path / "src/test/resources/features"
  feature_path = feature_root / "UserStory_TLS/UseCase_01/tls_guard.feature"
  feature_path.parent.mkdir(parents=True)
  feature_path.write_text(
    """#language: de
Funktionalität: TLS Guard

  @GS-A_5526
  Szenariogrundriss: Requirement am Szenario und Testaspekt am Beispiel
    Angenommen <wert> ist vorbereitet

    @TA_GS-A_5526_01
    Beispiele:
      | wert |
      | eins |
""",
    encoding="utf-8",
  )

  with caplog.at_level("WARNING", logger=builder.LOGGER.name):
    _use_cases, scenarios = builder._parse_feature_files(
      feature_root,
      user_story_anchors={},
      use_case_anchors={},
      known_requirement_ids={"GS-A_5526"},
      known_test_aspect_requirements={"TA_GS-A_5526_01": "GS-A_5526"},
    )

  assert not caplog.records
  assert len(scenarios) == 1
  combined_requirements = set().union(*(scenario.requirements for scenario in scenarios))
  combined_test_aspects = set().union(*(scenario.test_aspects for scenario in scenarios))
  assert combined_requirements == {"GS-A_5526"}
  assert combined_test_aspects == {"TA_GS-A_5526_01"}


def test_unrelated_tags_do_not_affect_test_coverage(tmp_path: Path) -> None:
  _write_minimal_project(
    tmp_path,
    scenario_tags=("@GS-A_5526", "@TA_GS-A_5526_01", "@regression"),
  )

  report = builder.build_traceability(
    project_root=tmp_path,
    write_outputs=False,
  )

  assert report.coverage_summary["requirements"]["vollständig abgedeckt"] == 1
  assert any((link.requirement == "GS-A_5526" and link.implemented for link in report.traceability))


@pytest.mark.parametrize("ignore_tag", ["@ignore", "@Ignore"])
@pytest.mark.parametrize("tag_scope", ["feature", "scenario"])
@pytest.mark.parametrize("keyword", ["Szenario", "Szenariogrundriss"])
def test_ignored_scenarios_do_not_contribute_to_any_coverage(
  tmp_path: Path, ignore_tag: str, tag_scope: str, keyword: str
) -> None:
  _write_minimal_project(
    tmp_path, scenario_tags=("@GS-A_5526", "@TA_GS-A_5526_01", "@MASVS-NETWORK")
  )
  feature = tmp_path / "src/test/resources/features/UserStory_TLS/UseCase_01/tls_guard.feature"
  content = feature.read_text(encoding="utf-8").replace("Szenario:", keyword + ":")
  if tag_scope == "feature":
    content = ignore_tag + "\n" + content
  else:
    content = content.replace("  @GS-A_5526", "  " + ignore_tag + "\n  @GS-A_5526")
  if keyword == "Szenariogrundriss":
    content += "\n  Beispiele:\n    | wert |\n    | eins |\n"
  feature.write_text(content, encoding="utf-8")
  (tmp_path / "docs/masvs_mapping.csv").write_text("AFO,MASVS\nGS-A_5526,NETWORK\n", encoding="utf-8")

  report = builder.build_traceability(project_root=tmp_path, write_outputs=True)

  assert report.coverage_summary["test_aspects"] == {"getestet": 0, "offen": 1, "gesamt": 1}
  assert report.coverage_summary["requirements"]["nicht abgedeckt"] == 1
  assert report.coverage_summary["requirements_any_coverage"]["mit_testabdeckung"] == 0
  assert report.coverage_summary["masvs"]["covered_requirements"] == 0
  assert report.coverage_summary["masvs"]["scenario_links"] == 0
  assert all(not link.implemented and not link.scenarios for link in report.traceability)
  tables = tmp_path / "docs/asciidoc/tables/generated"
  assert "|nein" in (tables / "traceability_matrix.adoc").read_text(encoding="utf-8")
  assert "|1\n|<<GS-A_5526>>\n|nein\n|0% (0/1)" in (
    tables / "requirements_coverage_table.adoc"
  ).read_text(encoding="utf-8")
  assert "TLS-Renegotiation wird geprüft" not in (
    tables / "masvs_coverage_by_category.adoc"
  ).read_text(encoding="utf-8")


def test_active_scenario_still_covers_aspect_also_tagged_on_ignored_scenario(tmp_path: Path) -> None:
  _write_minimal_project(
    tmp_path,
    scenario_tags=("@GS-A_5526", "@TA_GS-A_5526_01", "@MASVS-NETWORK", "@Ignore"),
  )
  feature = tmp_path / "src/test/resources/features/UserStory_TLS/UseCase_01/tls_guard.feature"
  with feature.open("a", encoding="utf-8") as stream:
    stream.write("\n  @GS-A_5526\n  @TA_GS-A_5526_01\n  @MASVS-NETWORK\n  @ignore_candidate\n"
                 "  Szenario: Aktiver Nachweis\n    Angenommen etwas ist vorbereitet\n")
  (tmp_path / "docs/masvs_mapping.csv").write_text("AFO,MASVS\nGS-A_5526,NETWORK\n", encoding="utf-8")

  report = builder.build_traceability(project_root=tmp_path, write_outputs=True)

  assert report.coverage_summary["test_aspects"]["getestet"] == 1
  assert report.coverage_summary["requirements"]["vollständig abgedeckt"] == 1
  assert report.coverage_summary["masvs"]["covered_requirements"] == 1
  assert report.coverage_summary["masvs"]["scenario_links"] == 1
  assert report.traceability[0].scenarios == ["Aktiver Nachweis"]


@pytest.mark.parametrize("active_examples", [False, True])
def test_outline_coverage_uses_only_non_ignored_examples(tmp_path: Path, active_examples: bool) -> None:
  _write_minimal_project(tmp_path)
  ta_dir = tmp_path / "docs/asciidoc/testaspekte/gemSpec_Krypt/GS-A_5526"
  for suffix in ("02", "03"):
    tid = "TA_GS-A_5526_" + suffix
    (ta_dir / (tid + ".adoc")).write_text(f"[#{tid}]\n===== {tid} - Beispiel\n", encoding="utf-8")
  feature = tmp_path / "src/test/resources/features/UserStory_TLS/UseCase_01/tls_guard.feature"
  middle_ignore = "    @Ignore\n" if not active_examples else ""
  feature.write_text(
    "#language: de\nFunktionalität: TLS Guard\n"
    "  @GS-A_5526\n  @TA_GS-A_5526_01\n  @MASVS-NETWORK\n"
    "  Szenariogrundriss: Varianten\n    Angenommen <wert> ist vorbereitet\n"
    "    @ignore\n    @TA_GS-A_5526_02\n    Beispiele: ignoriert\n    | wert |\n    | eins |\n"
    + middle_ignore
    + "    @TA_GS-A_5526_03\n    Beispiele: weitere\n    | wert |\n    | zwei |\n"
    "    @Ignore\n    Beispiele: ebenfalls ignoriert\n    | wert |\n    | drei |\n",
    encoding="utf-8",
  )
  (tmp_path / "docs/masvs_mapping.csv").write_text("AFO,MASVS\nGS-A_5526,NETWORK\n", encoding="utf-8")

  report = builder.build_traceability(project_root=tmp_path, write_outputs=False)

  implemented = {link.test_aspect for link in report.traceability if link.implemented}
  assert implemented == ({"TA_GS-A_5526_01", "TA_GS-A_5526_03"} if active_examples else set())
  assert report.coverage_summary["test_aspects"]["gesamt"] == 3
  assert report.coverage_summary["requirements_any_coverage"]["mit_testabdeckung"] == int(active_examples)
  assert report.coverage_summary["masvs"]["scenario_links"] == int(active_examples)


def test_untagged_examples_after_ignored_examples_still_cover_outline(tmp_path: Path) -> None:
  _write_minimal_project(tmp_path)
  tid = "TA_GS-A_5526_02"
  (tmp_path / "docs/asciidoc/testaspekte/gemSpec_Krypt/GS-A_5526" / (tid + ".adoc")).write_text(
    f"[#{tid}]\n===== {tid} - Ignoriertes Beispiel\n", encoding="utf-8"
  )
  feature = tmp_path / "src/test/resources/features/UserStory_TLS/UseCase_01/tls_guard.feature"
  feature.write_text(
    "#language: de\nFunktionalität: TLS Guard\n"
    "  @GS-A_5526\n  @TA_GS-A_5526_01\n  @MASVS-NETWORK\n"
    "  Szenariogrundriss: Varianten\n    Angenommen <wert> ist vorbereitet\n"
    "    @Ignore\n    @TA_GS-A_5526_02\n    Beispiele: ignoriert\n    | wert |\n    | eins |\n"
    "    Beispiele: aktiv ohne eigene Tags\n    | wert |\n    | zwei |\n",
    encoding="utf-8",
  )
  (tmp_path / "docs/masvs_mapping.csv").write_text("AFO,MASVS\nGS-A_5526,NETWORK\n", encoding="utf-8")

  report = builder.build_traceability(project_root=tmp_path, write_outputs=False)

  assert {link.test_aspect for link in report.traceability if link.implemented} == {"TA_GS-A_5526_01"}
  assert report.coverage_summary["test_aspects"] == {"getestet": 1, "offen": 1, "gesamt": 2}
  assert report.coverage_summary["requirements"]["teilweise abgedeckt"] == 1
  assert report.coverage_summary["masvs"]["scenario_links"] == 1


def test_outline_without_examples_does_not_contribute_to_coverage(tmp_path: Path) -> None:
  _write_minimal_project(
    tmp_path, scenario_tags=("@GS-A_5526", "@TA_GS-A_5526_01", "@MASVS-NETWORK")
  )
  feature = tmp_path / "src/test/resources/features/UserStory_TLS/UseCase_01/tls_guard.feature"
  feature.write_text(
    feature.read_text(encoding="utf-8").replace("Szenario:", "Szenariogrundriss:"),
    encoding="utf-8",
  )
  (tmp_path / "docs/masvs_mapping.csv").write_text("AFO,MASVS\nGS-A_5526,NETWORK\n", encoding="utf-8")

  report = builder.build_traceability(project_root=tmp_path, write_outputs=False)

  assert report.coverage_summary["test_aspects"] == {"getestet": 0, "offen": 1, "gesamt": 1}
  assert report.coverage_summary["requirements"]["nicht abgedeckt"] == 1
  assert report.coverage_summary["requirements_any_coverage"]["mit_testabdeckung"] == 0
  assert report.coverage_summary["masvs"]["scenario_links"] == 0
  assert all(not link.implemented and not link.scenarios for link in report.traceability)


def test_ignored_scenario_tags_are_still_validated(tmp_path: Path, caplog: pytest.LogCaptureFixture) -> None:
  _write_minimal_project(tmp_path, scenario_tags=("@Ignore", "@A_9999", "@TA_UNKNOWN_01"))

  with caplog.at_level("WARNING", logger=builder.LOGGER.name):
    report = builder.build_traceability(project_root=tmp_path, write_outputs=False)

  assert "unknown requirement tag A_9999" in caplog.text
  assert "unknown test aspect tag TA_UNKNOWN_01" in caplog.text
  assert report.coverage_summary["test_aspects"]["getestet"] == 0


def test_build_traceability_renders_masvs_tables(tmp_path: Path) -> None:
  _write_minimal_project(
    tmp_path,
    scenario_tags=(
      "@GS-A_5526",
      "@TA_GS-A_5526_01",
      "@MASVS-NETWORK",
    ),
  )
  (tmp_path / "docs/masvs_mapping.csv").write_text(
    "AFO,MASVS\nGS-A_5526,NETWORK\n",
    encoding="utf-8",
  )

  report = builder.build_traceability(
    project_root=tmp_path,
    write_outputs=True,
  )

  category_table = (
    tmp_path / "docs/asciidoc/tables/generated/masvs_coverage_by_category.adoc"
  ).read_text(encoding="utf-8")
  requirement_table = (
    tmp_path / "docs/asciidoc/tables/generated/masvs_coverage_by_requirement.adoc"
  ).read_text(encoding="utf-8")

  assert "|NETWORK" in category_table
  assert "|<<GS-A_5526>>" in category_table
  assert "Testaspekt" not in category_table
  assert "|<<TA_GS-A_5526_01>>" not in category_table
  assert "|TLS-Renegotiation wird geprüft" in category_table
  assert "|1" in requirement_table
  assert "|ja" in requirement_table
  assert report.coverage_summary["masvs"]["covered_requirements"] == 1


@pytest.mark.parametrize("covered, label, progress", [
  (0, "nein", "0% (0/3)"),
  (1, "teilweise", "33% (1/3)"),
  (2, "teilweise", "67% (2/3)"),
  (3, "ja", "100% (3/3)"),
])
def test_numbered_requirement_coverage_counts_unique_active_aspects(
  tmp_path: Path, covered: int, label: str, progress: str
) -> None:
  covered_tags = tuple(f"@TA_GS-A_5526_{index:02}" for index in range(1, covered + 1))
  _write_minimal_project(tmp_path, scenario_tags=("@GS-A_5526", *covered_tags))
  asciidoc = tmp_path / "docs/asciidoc"
  for index in (2, 3):
    identifier = f"TA_GS-A_5526_{index:02}"
    (asciidoc / f"testaspekte/gemSpec_Krypt/GS-A_5526/{identifier}.adoc").write_text(
      f"[#{identifier}]\n===== {identifier} - TLS-Prüfung\n", encoding="utf-8"
    )
  for identifier in ("A_100", "A_200", "A_300"):
    (asciidoc / f"afos/gemSpec_Krypt/{identifier}.adoc").write_text(
      f"[#{identifier}]\n==== {identifier} - Weitere Anforderung\n", encoding="utf-8"
    )
    if identifier != "A_300":
      aspect_directory = asciidoc / f"testaspekte/gemSpec_Krypt/{identifier}"
      aspect_directory.mkdir()
      (aspect_directory / f"TA_{identifier}_01.adoc").write_text(
        f"[#TA_{identifier}_01]\n===== TA_{identifier}_01 - Weitere Prüfung\n",
        encoding="utf-8",
      )
  feature = tmp_path / "src/test/resources/features/UserStory_TLS/UseCase_02/coverage.feature"
  feature.parent.mkdir()
  feature.write_text(
    "#language: de\nFunktionalität: Weitere Abdeckung\n"
    "  @A_100 @TA_A_100_01 " + " ".join(covered_tags) + "\n"
    "  Szenario: Aktive Abdeckung\n    Angenommen etwas ist vorbereitet\n"
    "  @Ignore @TA_GS-A_5526_01 @TA_GS-A_5526_02 @TA_GS-A_5526_03 @TA_A_200_01\n"
    "  Szenario: Ignorierte Abdeckung\n    Angenommen etwas ist vorbereitet\n",
    encoding="utf-8",
  )

  report = builder.build_traceability(project_root=tmp_path, write_outputs=True)

  table = (asciidoc / "tables/generated/requirements_coverage_table.adoc").read_text(encoding="utf-8")
  assert [line[2:] for line in table.splitlines() if line.startswith("^|")] == [
    "Nr.", "Anforderung", "getestet", "Testabdeckung"
  ]
  cells = [line[1:] for line in table.splitlines() if line.startswith("|") and line != "|==="]
  assert [cells[index:index + 4] for index in range(0, len(cells), 4)] == [
    ["1", "<<A_100>>", "ja", "100% (1/1)"],
    ["2", "<<A_200>>", "nein", "0% (0/1)"],
    ["3", "<<A_300>>", "nein", "0% (0/0)"],
    ["4", "<<GS-A_5526>>", label, progress],
  ]
  assert report.coverage_summary["test_aspects"]["getestet"] == covered + 1
  assert report.coverage_summary["requirements"] == {
    "vollständig abgedeckt": 1 + int(covered == 3),
    "teilweise abgedeckt": int(0 < covered < 3),
    "nicht abgedeckt": 1 + int(covered == 0),
    "keine Testaspekte": 1,
    "gesamt": 4,
  }


@pytest.mark.parametrize("dry_run", [False, True])
def test_cli_outputs_only_catalogue_and_scenario_coverage(
  tmp_path: Path, capsys: pytest.CaptureFixture[str], dry_run: bool
) -> None:
  _write_minimal_project(tmp_path)
  arguments = ["--project-root", str(tmp_path), "--json"]
  if dry_run:
    arguments.append("--dry-run")

  assert main(arguments) == 0

  payload = json.loads(capsys.readouterr().out)
  assert set(payload["coverage_summary"]) == {
    "requirements", "requirements_any_coverage", "test_aspects", "masvs"
  }
  assert payload["requirements"]["GS-A_5526"]["title"] == "TLS-Renegotiation-Indication-Extension"
  assert payload["traceability"] == [{
    "requirement": "GS-A_5526",
    "test_aspect": "TA_GS-A_5526_01",
    "use_case": "UseCase_01",
    "implemented": True,
    "scenarios": ["TLS-Renegotiation wird geprüft"],
  }]
  tables = tmp_path / "docs/asciidoc/tables"
  diagrams = tmp_path / "docs/asciidoc/diagrams"
  assert not (tables / "source").exists()
  assert {path.name for path in tables.glob("generated/*")} == (set() if dry_run else {
    "features_table.adoc", "traceability_matrix.adoc",
    "requirements_coverage_table.adoc",
    "masvs_coverage_by_category.adoc", "masvs_coverage_by_requirement.adoc",
  })
  assert {path.name for path in diagrams.glob("generated/*")} == (set() if dry_run else {
    "traceability-coverage-requirements.mmd", "traceability-coverage-requirements-tested.mmd",
    "traceability-coverage-testaspects.mmd",
  })
  assert not (tmp_path / "target").exists()


def _write_minimal_project(
  project_root: Path,
  *,
  scenario_tags: tuple[str, ...] = ("@GS-A_5526", "@TA_GS-A_5526_01"),
) -> None:
  catalogue = project_root / builder.DEFAULT_XML
  catalogue.parent.mkdir(parents=True)
  catalogue.write_text(
    '<requirements><requirement id="GS-A_5526">'
    '<sourceDocumentId>gemSpec_Krypt</sourceDocumentId>'
    f'<testProcedure>{builder.DEFAULT_TEST_PROCEDURE}</testProcedure>'
    '</requirement></requirements>', encoding="utf-8",
  )
  afo_dir = project_root / "docs/asciidoc/afos/gemSpec_Krypt"
  ta_dir = project_root / "docs/asciidoc/testaspekte/gemSpec_Krypt/GS-A_5526"
  feature_dir = project_root / "src/test/resources/features/UserStory_TLS/UseCase_01"
  afo_dir.mkdir(parents=True)
  ta_dir.mkdir(parents=True)
  feature_dir.mkdir(parents=True)

  (afo_dir / "GS-A_5526.adoc").write_text(
    "[#GS-A_5526]\n==== GS-A_5526 - TLS-Renegotiation-Indication-Extension\n",
    encoding="utf-8",
  )
  (ta_dir / "TA_GS-A_5526_01.adoc").write_text(
    "[#TA_GS-A_5526_01]\n===== TA_GS-A_5526_01 - TLS-Renegotiation-Indication-Extension\n",
    encoding="utf-8",
  )
  tag_block = "\n  ".join(scenario_tags)
  (feature_dir / "tls_guard.feature").write_text(
    f"""#language: de
Funktionalität: TLS Guard

  {tag_block}
  Szenario: TLS-Renegotiation wird geprüft
    Angenommen etwas ist vorbereitet
""",
    encoding="utf-8",
  )
