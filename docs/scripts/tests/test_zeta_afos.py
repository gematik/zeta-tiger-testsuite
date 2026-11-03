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

import pytest

from testsuite_docs import zeta_afos


def test_main_generates_filtered_requirements_and_updates_readme(
  tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
  """Preserve source metadata and link generated requirement indexes."""
  input_xml = tmp_path / "gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xml"
  output_dir = tmp_path / "generated"
  readme_path = tmp_path / "readme.adoc"
  references_path = tmp_path / "02_referenzen.adoc"
  introduction_path = tmp_path / "03_einleitung.adoc"
  zeta_guard_components_path = tmp_path / "zeta_guard_komponenten.adoc"

  input_xml.write_text(
    """<catalog>
  <documents>
    <document>
      <denotation>Test-Spezifikation</denotation>
      <id>gemSpec_Test</id>
      <version>1.2.3</version>
    </document>
    <document>
      <denotation>Spezifikation Zero Trust Access (ZETA)</denotation>
      <id>gemSpec_ZETA</id>
      <version>2.0.0_CC</version>
    </document>
    <document>
      <denotation>Übergreifende Spezifikation Verwendung kryptographischer Algorithmen in der Telematikinfrastruktur</denotation>
      <id>gemSpec_Krypt</id>
      <version>2.47.0</version>
    </document>
  </documents>
  <requirements>
    <requirement id="A_0001">
      <testProcedure>Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"</testProcedure>
      <sourceDocumentId>gemSpec_Test</sourceDocumentId>
      <title>Erster Titel</title>
      <description>Mehrzeilig&#160;
        mit  Leerzeichen</description>
    </requirement>
    <requirement id="A_0002">
      <testProcedure>Andere Festlegung</testProcedure>
      <sourceDocumentId>gemSpec_Test</sourceDocumentId>
      <title>Zweiter Titel</title>
      <description>Wird herausgefiltert</description>
    </requirement>
  </requirements>
</catalog>
""",
    encoding="utf-8",
  )
  readme_path.write_text(
    """== Identifizierte Anforderungen

=== Anforderungen gemVZ AFO ZETA Guard (Produkttest 3.1.1)

veralteter Inhalt

=== Bestehender Abschnitt

bleibt erhalten
""",
    encoding="utf-8",
  )
  references_path.write_text(
    "* {gemVZ_Afo_ZETA_Guard}[gemVZ_Afo_ZETA_Guard_V_1.2.1-0]: Version 1.0.0_CC\n",
    encoding="utf-8",
  )
  introduction_path.write_text(
    """-  https://old.example.invalid/["Verzeichnis von Anforderungen Prüfvorschrift Zero Trust Access (ZETA) Guard"]
*gemVZ_Afo_ZETA_Guard_1.2.1-0_V1.0.0_CC*.
""",
    encoding="utf-8",
  )
  zeta_guard_components_path.write_text(
    'Quelle: {gemSpec_ZETA}[gemSpec_ZETA], Abschnitt 3 "Einordnung in die TI 2.0",\n',
    encoding="utf-8",
  )

  monkeypatch.setattr(zeta_afos, "ROOT_README", readme_path)
  monkeypatch.setattr(zeta_afos, "REFERENCES_CHAPTER", references_path)
  monkeypatch.setattr(zeta_afos, "INTRODUCTION_CHAPTER", introduction_path)
  monkeypatch.setattr(zeta_afos, "ZETA_GUARD_COMPONENTS", zeta_guard_components_path)
  exit_code = zeta_afos.main(
    [
      "--input-xml",
      str(input_xml),
      "--output-dir",
      str(output_dir),
      "--force",
    ]
  )

  assert exit_code == 0
  assert capsys.readouterr().out.strip() == f"Generated 1 requirements in {output_dir.as_posix()}"

  generated_files = sorted(
    path.relative_to(output_dir).as_posix() for path in output_dir.rglob("*.adoc")
  )
  assert generated_files == ["gemSpec_Test/A_0001.adoc", "gemSpec_Test/readme.adoc"]

  requirement_content = (output_dir / "gemSpec_Test" / "A_0001.adoc").read_text(encoding="utf-8")
  assert (
    requirement_content == "[#A_0001]\n==== A_0001 - Erster Titel\n\nMehrzeilig mit Leerzeichen\n"
  )

  readme_content = readme_path.read_text(encoding="utf-8")
  assert "==== Test-Spezifikation (gemSpec_Test V1.2.3)" in readme_content
  assert "include::gemSpec_Test/readme.adoc[]" in readme_content
  assert (output_dir / "gemSpec_Test/readme.adoc").read_text(
    encoding="utf-8"
  ) == "include::A_0001.adoc[]\n\n"
  assert "=== Bestehender Abschnitt" in readme_content
  assert "veralteter Inhalt" not in readme_content
  assert "A_0002" not in readme_content
  assert "gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0/" in readme_content
  assert "Kurzbezeichnung: gemSpec_Krypt, Version: 2.47.0" in readme_content

  references_content = references_path.read_text(encoding="utf-8")
  assert (
    "* https://gemspec.gematik.de/docs/gemSpec/gemSpec_ZETA/gemSpec_ZETA_V2.0.0_CC[Spezifikation Zero Trust Access (ZETA)]: Version 2.0.0_CC"
    in references_content
  )
  assert (
    "* https://gemspec.gematik.de/docs/gemSpec/gemSpec_Krypt/gemSpec_Krypt_V2.47.0[Übergreifende Spezifikation Verwendung kryptographischer Algorithmen in der Telematikinfrastruktur]: Version 2.47.0"
    in references_content
  )
  assert (
    "* https://gemspec.gematik.de/docs/gemVZ/gemVZ_Afo_ZETA_Guard/gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0/[gemVZ_Afo_ZETA_Guard_V_2.0.0-0]: Version 1.0.0"
    in references_content
  )
  assert "gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0" in introduction_path.read_text(encoding="utf-8")
  assert (
    "https://gemspec.gematik.de/docs/gemSpec/gemSpec_ZETA/gemSpec_ZETA_V2.0.0_CC"
    in zeta_guard_components_path.read_text(encoding="utf-8")
  )


def test_main_uses_cc_catalog_by_default(
  tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
  repository_root = Path(__file__).resolve().parents[3]
  monkeypatch.chdir(repository_root)

  output_dir = tmp_path / "generated"
  exit_code = zeta_afos.main(
    [
      "--output-dir",
      str(output_dir),
      "--no-readme",
      "--force",
    ]
  )

  assert exit_code == 0
  generated_files = sorted(output_dir.rglob("*.adoc"))
  assert len(generated_files) == 256
  assert capsys.readouterr().out.strip() == f"Generated 256 requirements in {output_dir.as_posix()}"
  assert (output_dir / "gemSpec_ZETA/A_29676.adoc").is_file()
  assert (output_dir / "gemSpec_ZETA/A_26972-03.adoc").is_file()
  assert not (output_dir / "gemSpec_ZETA/A_26972-02.adoc").exists()

  first_requirement = generated_files[0].read_text(encoding="utf-8")
  assert "[#" in first_requirement
  assert "==== " in first_requirement
