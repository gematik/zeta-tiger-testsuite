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

"""Deterministically derive a local XML catalogue from the ZETA CC workbook."""

from __future__ import annotations

import argparse
import hashlib
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET

from openpyxl import load_workbook
from lxml import etree


SOURCE_DIR = Path("docs/specifications/2.0.0-cc")
CATALOG_STEM = "gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0"
DEFAULT_XLSX = SOURCE_DIR / f"{CATALOG_STEM}.xlsx"
DEFAULT_XML = SOURCE_DIR / "derived" / f"{CATALOG_STEM}.xml"
SCHEMA_DIR = Path(__file__).resolve().parents[4] / SOURCE_DIR / "schemas"
UPSTREAM_SCHEMA = SCHEMA_DIR / "upstream" / "afoListeSteckbrief_v2.xsd"
LOCAL_SCHEMA = SCHEMA_DIR / "afoListeSteckbrief_v2_local.xsd"
DOCUMENT_HEADERS = ("Dokumentenkürzel", "Bezeichnung des Dokumentes", "Version")
REQUIREMENT_HEADERS = (
    "ID", "Titel", "Beschreibung", "Beschreibung (HTML)", "Verbindlichkeit",
    "Quelle (Referenz)", "Pruefverfahren (im Steckbrief)",
    "zugeordnete KonfigElemente (allgemein)", "Kapitel-Nummer", "Kapitel-Name",
    "bin Parent", "meine Parent-IDs",
)
SPECIFIC_HEADERS = ("ID", "Titel", "Quelle (Referenz)", "Erläuterung")
REFERENCE_HEADERS = (
    "Quelle", "Herausgeber: Bezeichnung / URL", "Version\n    \n    Branch / Tag",
)
SHEETS = (
    "Hinweise", "Dokumente", "Festlegungen", "Spezifische Merkmale",
    "Weitere Referenzen (1)", "Weitere Referenzen (2)",
)


def _rows(sheet) -> list[tuple[str, ...]]:
  """Read literal text cells without evaluating or silently dropping formulas."""
  rows = []
  for cells in sheet:
    values = []
    for cell in cells:
      if cell.data_type in {"f", "e"}:
        raise ValueError(f"Formula or error at {sheet.title}!{cell.coordinate}")
      value = cell.value
      if value is not None and not isinstance(value, str):
        raise ValueError(f"Non-text cell at {sheet.title}!{cell.coordinate}")
      values.append((value or "").replace("\r\n", "\n").replace("\r", "\n"))
    rows.append(tuple(values))
  return rows


def _table(rows: list[tuple[str, ...]], headers: tuple[str, ...],
           name: str) -> list[tuple[str, ...]]:
  """Reject changed layouts instead of guessing their column mapping."""
  if not rows or tuple(cell.strip() for cell in rows[0]) != headers:
    raise ValueError(f"Unexpected headers in {name}")
  return [row for row in rows[1:] if any(row)]


def _text(parent: ET.Element, tag: str, value: str) -> None:
  """Append text using XML escaping, including embedded HTML as literal text."""
  ET.SubElement(parent, tag).text = value


def _list(parent: ET.Element, wrapper: str, tag: str, value: str) -> None:
  """Map newline-separated workbook lists to ordered XML children."""
  if value:
    container = ET.SubElement(parent, wrapper)
    for item in value.splitlines():
      if not item.strip():
        raise ValueError(f"Empty entry in {wrapper}")
      _text(container, tag, item)


def render_local_schema(source: bytes) -> bytes:
  """Relax only the two unavailable attributes in the pinned upstream schema."""
  result = source.replace(b"\r\n", b"\n")
  for name, data_type in (("type", "xs:string"), ("lastUpdate", "xs:date")):
    declaration = f'<xs:attribute type="{data_type}" name="{name}" use="required"/>'.encode()
    if result.count(declaration) != 1:
      raise ValueError(f"Expected exactly one upstream declaration for {name}")
    result = result.replace(declaration, declaration.replace(b'"required"', b'"optional"'))
  return result


def validate_catalog(document: bytes) -> None:
  """Validate offline against the pinned local XSD before accepting output."""
  parser = etree.XMLParser(no_network=True, resolve_entities=False)
  schema = etree.XMLSchema(etree.fromstring(LOCAL_SCHEMA.read_bytes(), parser))
  schema.assertValid(etree.fromstring(document, parser))


def render_catalog(source: bytes) -> bytes:
  """Render the same workbook bytes identically on every run and platform.

  No legacy catalogue data, current timestamps, or absolute paths are used.
  Requirement rows are retained separately for each test procedure.
  """
  workbook = load_workbook(BytesIO(source), read_only=True, data_only=False)
  try:
    if tuple(workbook.sheetnames) != SHEETS:
      raise ValueError("Unexpected workbook sheets or sheet order")
    tables = {name: _rows(workbook[name]) for name in SHEETS}
  finally:
    workbook.close()

  root = ET.Element("characteristics")
  root.append(ET.Comment(
      "\nLocally derived from the ZETA V2.0.0 CC XLSX draft package; "
      "not a published gematik XML catalogue.\n"
      f"Source SHA-256: {hashlib.sha256(source).hexdigest()}\n"
      "Conversion: testsuite_docs.zeta_catalog_xml, format 2.\n"
      "No type or lastUpdate attributes: these are not supplied by the XLSX.\n"
      "Validated against afoListeSteckbrief_v2_local.xsd; "
      "only type and lastUpdate are optional.\n"))

  documents = ET.SubElement(root, "documents")
  document_ids = set()
  for identifier, title, version in _table(
      tables["Dokumente"], DOCUMENT_HEADERS, "Dokumente"):
    if not all((identifier, title, version)) or identifier in document_ids:
      raise ValueError(f"Missing or duplicate document metadata: {identifier}")
    document_ids.add(identifier)
    document = ET.SubElement(documents, "document")
    for tag, value in (("id", identifier), ("denotation", title), ("version", version)):
      _text(document, tag, value)

  requirements = ET.SubElement(root, "requirements")
  rows = _table(tables["Festlegungen"], REQUIREMENT_HEADERS, "Festlegungen")
  if not rows:
    raise ValueError("Empty requirements catalogue")
  requirement_ids = {row[0] for row in rows}
  definitions = {}
  procedure_keys = set()
  for row in rows:
    (identifier, title, description, html, severity, doc, procedure,
     configs, chapter, chapter_name, parent, parents) = row
    if not all(row[:7]) or doc not in document_ids:
      raise ValueError(f"Missing requirement data or unknown source: {identifier}")
    definition = row[:6] + row[7:]
    if identifier in definitions and definitions[identifier] != definition:
      raise ValueError(f"Conflicting repeated requirement: {identifier}")
    definitions[identifier] = definition
    if (identifier, procedure) in procedure_keys:
      raise ValueError(f"Duplicate requirement/test procedure: {identifier}")
    procedure_keys.add((identifier, procedure))
    if parent and parent != identifier:
      raise ValueError(f"Invalid parent marker: {identifier}")
    if any(item not in requirement_ids for item in parents.splitlines()):
      raise ValueError(f"Unknown parent requirement: {identifier}")
    requirement = ET.SubElement(requirements, "requirement", id=identifier,
                                severity=severity)
    for tag, value in (
        ("title", title), ("description", description), ("description_html", html),
        ("sourceDocumentId", doc), ("testProcedure", procedure),
    ):
      _text(requirement, tag, value)
    _list(requirement, "configElements", "configElement", configs)
    for tag, value in (("chapterNumber", chapter), ("chapterName", chapter_name),
                       ("iAmParent", parent)):
      if value:
        _text(requirement, tag, value)
    _list(requirement, "myParentIDs", "myParentID", parents)

  if _table(tables["Spezifische Merkmale"], SPECIFIC_HEADERS, "Spezifische Merkmale"):
    raise ValueError("Populated specific attributes require an explicit XML mapping")
  ET.SubElement(root, "specificAttributes")
  for name in SHEETS[-2:]:
    rows = tables[name]
    if len(rows) < 3 or not rows[0][0] or any(rows[0][1:]) or any(rows[1]):
      raise ValueError(f"Unexpected reference layout: {name}")
    reference_rows = _table(rows[2:], REFERENCE_HEADERS, name)
    references = ET.SubElement(root, "furtherReferences")
    _text(references, "title", rows[0][0])
    headers = ET.SubElement(references, "headers")
    for value in rows[2]:
      _text(headers, "header", value)
    for row in reference_rows:
      reference = ET.SubElement(references, "reference")
      for value in row:
        _text(reference, "entry", value)

  # Comments retain explanatory source text without extending the XSD vocabulary.
  for row in tables["Hinweise"]:
    if len(row) != 1:
      raise ValueError("Unexpected notes layout")
    if row[0]:
      if "--" in row[0]:
        raise ValueError("Workbook note contains a forbidden XML comment sequence")
      root.append(ET.Comment("Workbook note:\n" + row[0] + "\n"))

  ET.indent(root, space="  ")
  result = (b'<?xml version="1.0" encoding="UTF-8"?>\n'
            + ET.tostring(root, encoding="utf-8", short_empty_elements=False) + b"\n")
  validate_catalog(result)
  return result


def main(argv: list[str] | None = None) -> int:
  """Generate a local draft XML or check it without modifying files."""
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--input-xlsx", type=Path, default=DEFAULT_XLSX)
  parser.add_argument("--output-xml", type=Path, default=DEFAULT_XML)
  parser.add_argument("--check", action="store_true",
                      help="Exit nonzero if the XML is missing or differs; do not write.")
  args = parser.parse_args(argv)
  if args.output_xml.suffix != ".xml" or args.input_xlsx.resolve() == args.output_xml.resolve():
    parser.error("Output must be a separate .xml file")
  result = render_catalog(args.input_xlsx.read_bytes())
  if args.check:
    if not args.output_xml.exists() or args.output_xml.read_bytes() != result:
      print(f"Derived XML missing or out of date: {args.output_xml}")
      return 1
    print(f"Derived XML is reproducible: {args.output_xml}")
    return 0
  args.output_xml.parent.mkdir(parents=True, exist_ok=True)
  args.output_xml.write_bytes(result)
  root = ET.fromstring(result)
  rows = root.findall("./requirements/requirement")
  print(f"Generated {len(rows)} rows / {len({row.get('id') for row in rows})} "
        f"requirement IDs: {args.output_xml}")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
