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

"""Check source fidelity, reproducibility and existing generator compatibility."""

from __future__ import annotations

import hashlib
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET

import pytest
from openpyxl import load_workbook
from lxml import etree, html

from testsuite_docs import zeta_afos, zeta_catalog_xml


ROOT = Path(__file__).resolve().parents[3]


@pytest.fixture(scope="module")
def workbook_source() -> bytes:
  """Read the maintained source workbook once for this module."""
  return (ROOT / zeta_catalog_xml.DEFAULT_XLSX).read_bytes()


@pytest.fixture(scope="module")
def catalog_xml(workbook_source: bytes) -> bytes:
  """Render immutable reference XML once for this module."""
  return zeta_catalog_xml.render_catalog(workbook_source)


def test_checked_in_xml_is_byte_reproducible(workbook_source: bytes, catalog_xml: bytes) -> None:
  assert catalog_xml == zeta_catalog_xml.render_catalog(workbook_source)
  assert catalog_xml == (ROOT / zeta_catalog_xml.DEFAULT_XML).read_bytes()
  assert b"\r" not in catalog_xml
  assert hashlib.sha256(workbook_source).hexdigest().encode() in catalog_xml
  assert b"not a published gematik XML catalogue" in catalog_xml


def test_every_workbook_field_survives_xml_round_trip(
  workbook_source: bytes, catalog_xml: bytes
) -> None:
  root = ET.fromstring(
    catalog_xml, parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
  )
  workbook = load_workbook(BytesIO(workbook_source), read_only=True)
  try:
    expected_rows = list(workbook["Festlegungen"].values)[1:]
    requirements = root.findall("./requirements/requirement")
    assert len(requirements) == 467
    assert len({req.get("id") for req in requirements}) == 399
    actual_rows = []
    for req in requirements:
      assert set(req.attrib) == {"id", "severity"}
      actual_rows.append(
        (
          req.get("id"),
          req.findtext("title"),
          req.findtext("description"),
          req.findtext("description_html"),
          req.get("severity"),
          req.findtext("sourceDocumentId"),
          req.findtext("testProcedure"),
          "\n".join(e.text for e in req.findall("./configElements/configElement")),
          req.findtext("chapterNumber"),
          req.findtext("chapterName"),
          req.findtext("iAmParent"),
          "\n".join(e.text for e in req.findall("./myParentIDs/myParentID")) or None,
        )
      )
    # Optional empty cells may be stored as either None or an empty XLSX string.
    assert [tuple((value or "" for value in row)) for row in actual_rows] == [
      tuple((value or "" for value in row)) for row in expected_rows
    ]
    assert [
      tuple((doc.findtext(tag) for tag in ("id", "denotation", "version")))
      for doc in root.findall("./documents/document")
    ] == list(workbook["Dokumente"].values)[1:]
    assert [
      e.text.removeprefix("Workbook note:\n").removesuffix("\n")
      for e in root
      if e.tag is ET.Comment and e.text.startswith("Workbook note:\n")
    ] == [r[0] for r in workbook["Hinweise"].values if r[0]]
    assert root.find("workbookNotes") is None
    assert len(root.find("specificAttributes")) == 0
    references = root.findall("furtherReferences")
    assert len(references) == 2
    for index, reference in enumerate(references, 1):
      rows = list(workbook[f"Weitere Referenzen ({index})"].values)
      assert reference.findtext("title") == rows[0][0]
      assert tuple((e.text for e in reference.findall("./headers/header"))) == rows[2]
      assert [
        tuple((e.text for e in item.findall("entry"))) for item in reference.findall("reference")
      ] == [row for row in rows[3:] if any(row)]
  finally:
    workbook.close()


def test_requirement_end_markers_match_pinned_html(workbook_source: bytes) -> None:
  doc = html.parse(str(ROOT / zeta_catalog_xml.SOURCE_DIR / 'gemSpec_ZETA_V2.0.0_CC.html'))
  workbook = load_workbook(BytesIO(workbook_source), read_only=True)
  try:
    checked = set()
    for row in list(workbook['Festlegungen'].values)[1:]:
      nodes = doc.xpath('//*[@id=$id]', id=row[0])
      if not nodes:
        continue
      assert len(nodes) == 1
      source = nodes[0].text_content().rstrip()
      expected = source.endswith('[<=]')
      assert row[2].rstrip().endswith('[<=]') == expected, row[0]
      quoted_html = html.fromstring(row[3]).text_content().rstrip()
      assert quoted_html.endswith('[<=]') == expected, row[0]
      if expected:
        assert row[2].count('[<=]') == 1, row[0]
        assert quoted_html.count('[<=]') == 1, row[0]
      checked.add(row[0])
    assert checked
    assert 'A_27496' in checked
  finally:
    workbook.close()


def test_existing_afo_generator_selects_all_product_test_rows(
  tmp_path: Path, catalog_xml: bytes
) -> None:
  xml_path = tmp_path / "catalog.xml"
  xml_path.write_bytes(catalog_xml)
  output = tmp_path / "afos"
  result = zeta_afos.main(
    [
      "--input-xml",
      str(xml_path),
      "--output-dir",
      str(output),
      "--no-readme",
    ]
  )
  assert result == 0
  requirements = ET.fromstring(catalog_xml).findall("./requirements/requirement")
  expected = {
    req.get("id")
    for req in requirements
    if req.findtext("testProcedure") == zeta_afos.DEFAULT_TEST_PROCEDURE
  }
  assert len(expected) == 256
  assert {path.stem for path in output.rglob("*.adoc")} == expected
  for identifier in ("A_25340-02", "A_27379-02", "A_29849"):
    text = (output / "gemSpec_ZETA" / f"{identifier}.adoc").read_text(encoding="utf-8")
    assert f"[#{identifier}]" in text


def test_check_reports_stale_or_missing_output_without_writing(
  tmp_path: Path, workbook_source: bytes
) -> None:
  source = tmp_path / "input.xlsx"
  output = tmp_path / "output.xml"
  source.write_bytes(workbook_source)
  arguments = ["--input-xlsx", str(source), "--output-xml", str(output)]
  assert zeta_catalog_xml.main([*arguments, "--check"]) == 1
  assert not (output.exists())
  assert zeta_catalog_xml.main(arguments) == 0
  assert zeta_catalog_xml.main([*arguments, "--check"]) == 0
  output.write_bytes(b"stale XML")
  assert zeta_catalog_xml.main([*arguments, "--check"]) == 1
  assert output.read_bytes() == b"stale XML"


def test_empty_configuration_elements_are_omitted(
  workbook_source: bytes, catalog_xml: bytes
) -> None:
  """Keep requirement rows when their optional configuration cells are empty."""
  workbook = load_workbook(BytesIO(workbook_source))
  try:
    sheet = workbook["Festlegungen"]
    identifier = sheet["A2"].value
    for row in sheet.iter_rows(min_row=2):
      if row[0].value == identifier:
        row[7].value = None
    data = BytesIO()
    workbook.save(data)
  finally:
    workbook.close()
  root = ET.fromstring(zeta_catalog_xml.render_catalog(data.getvalue()))
  original = ET.fromstring(catalog_xml)
  requirements = root.findall("./requirements/requirement")
  expected = original.findall("./requirements/requirement")
  assert len(requirements) == len(expected)
  for actual, previous in zip(requirements, expected):
    if previous.get("id") == identifier:
      assert actual.find("configElements") is None
      configs = previous.find("configElements")
      assert configs is not None
      previous.remove(configs)
    assert ET.tostring(actual) == ET.tostring(previous)


@pytest.mark.parametrize(
  "sheet,cell,value,error",
  (
    ("Festlegungen", "A1", "renamed ID column", "headers"),
    ("Festlegungen", "C2", '=CONCAT("A", "B")', "Formula"),
    ("Festlegungen", "F2", "unknown_spec", "unknown source"),
    ("Festlegungen", "L2", "A_missing_parent", "Unknown parent"),
    ("Spezifische Merkmale", "A2", "new attribute", "explicit XML mapping"),
    *(("Festlegungen", f"{column}2", None, "Missing requirement data") for column in "ABCDEFG"),
  ),
)
def test_unsupported_or_ambiguous_source_data_is_rejected(
  workbook_source: bytes, sheet: str, cell: str, value: str | None, error: str
) -> None:
  """Reject ambiguous inputs and empty mandatory requirement fields."""
  workbook = load_workbook(BytesIO(workbook_source))
  try:
    workbook[sheet][cell] = value
    data = BytesIO()
    workbook.save(data)
  finally:
    workbook.close()
  with pytest.raises(ValueError, match=error):
    zeta_catalog_xml.render_catalog(data.getvalue())


def test_duplicate_ids_cannot_silently_overwrite_different_text(workbook_source: bytes) -> None:
  workbook = load_workbook(BytesIO(workbook_source))
  try:
    sheet = workbook["Festlegungen"]
    seen = set()
    for row in sheet.iter_rows(min_row=2):
      if row[0].value in seen:
        row[2].value = "Changed normative text for the same ID"
        break
      seen.add(row[0].value)
    else:
      pytest.fail("Fixture must contain repeated requirement IDs")
    data = BytesIO()
    workbook.save(data)
  finally:
    workbook.close()
  with pytest.raises(ValueError, match="Conflicting repeated requirement"):
    zeta_catalog_xml.render_catalog(data.getvalue())


def test_local_schema_changes_only_two_attribute_requirements() -> None:
  upstream = zeta_catalog_xml.UPSTREAM_SCHEMA.read_bytes()
  local = zeta_catalog_xml.LOCAL_SCHEMA.read_bytes()
  assert (
    hashlib.sha256(upstream).hexdigest()
    == "cf38270fdc31d15afd55a4e23aebf7f67a5881d9822ccfb60f413c53e72296e0"
  )
  assert local == zeta_catalog_xml.render_local_schema(upstream)
  parser = etree.XMLParser(remove_blank_text=True)
  original_tree = etree.fromstring(upstream, parser)
  local_tree = etree.fromstring(local, parser)
  namespaces = {"xs": "http://www.w3.org/2001/XMLSchema"}
  for name in ("type", "lastUpdate"):
    path = f"./xs:element[@name='requirement']/xs:complexType/xs:attribute[@name='{name}']"
    (attribute,) = original_tree.xpath(path, namespaces=namespaces)
    assert attribute.get("use") == "required"
    attribute.set("use", "optional")
  assert etree.tostring(original_tree) == etree.tostring(local_tree)


def test_generated_document_passes_local_but_requires_metadata_for_official_schema(
  catalog_xml: bytes,
) -> None:
  zeta_catalog_xml.validate_catalog(catalog_xml)
  official = etree.XMLSchema(etree.fromstring(zeta_catalog_xml.UPSTREAM_SCHEMA.read_bytes()))
  document = etree.fromstring(catalog_xml)
  assert not (official.validate(document))
  assert all(
    "attribute 'type'" in error.message or "attribute 'lastUpdate'" in error.message
    for error in official.error_log
  )
  # Synthetic metadata exists only in this in-memory validation fixture.
  for requirement in document.findall("./requirements/requirement"):
    requirement.set("type", "leafRequirement")
    requirement.set("lastUpdate", "2026-07-09")
  official.assertValid(document)
  zeta_catalog_xml.validate_catalog(etree.tostring(document))


@pytest.mark.parametrize(
  "name,mutate",
  (
    ("missing ID", lambda req: req.attrib.pop("id")),
    ("missing severity", lambda req: req.attrib.pop("severity")),
    ("invalid severity", lambda req: req.set("severity", "MAYBE")),
    ("invalid optional date", lambda req: req.set("lastUpdate", "not-a-date")),
    ("missing title", lambda req: req.remove(req.find("title"))),
    ("wrong element order", lambda req: req.insert(0, req.find("testProcedure"))),
    ("unexpected element", lambda req: etree.SubElement(req, "unexpected")),
    ("unexpected attribute", lambda req: req.set("extra", "value")),
    ("notes extension", lambda req: etree.SubElement(req.getroottree().getroot(), "workbookNotes")),
  ),
)
def test_local_schema_still_rejects_invalid_requirements(catalog_xml: bytes, name, mutate) -> None:
  """Reject each invalid-schema mutation independently."""
  document = etree.fromstring(catalog_xml)
  mutate(document.find("./requirements/requirement"))
  with pytest.raises(etree.DocumentInvalid):
    zeta_catalog_xml.validate_catalog(etree.tostring(document))


def test_schema_error_prevents_overwriting_output(tmp_path: Path, workbook_source: bytes) -> None:
  workbook = load_workbook(BytesIO(workbook_source))
  try:
    workbook["Festlegungen"]["E2"] = "MAYBE"
    source = tmp_path / "input.xlsx"
    output = tmp_path / "output.xml"
    workbook.save(source)
    output.write_bytes(b"previous output")
    with pytest.raises(etree.DocumentInvalid):
      zeta_catalog_xml.main(["--input-xlsx", str(source), "--output-xml", str(output)])
    assert output.read_bytes() == b"previous output"
  finally:
    workbook.close()
