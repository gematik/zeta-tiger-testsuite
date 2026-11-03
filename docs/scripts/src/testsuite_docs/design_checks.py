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

"""Mechanical source and consistency checks; never independent design validation.

The checker reads the CC workbook within the configured requirement and UseCase scope.
Aspects and references are discovered from current documents. No stored review,
expected assignment, lexical contract or historical source interpretation is read.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter, defaultdict
import itertools
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import BadZipFile

from lxml import etree, html

from testsuite_docs.zeta_catalog_xml import DEFAULT_XLSX, render_catalog


AFO_ROOT = Path("docs/asciidoc/afos")
TA_ROOT = Path("docs/asciidoc/testaspekte")
UC_ROOT = Path("src/test/resources/features")
# Requirement scope, including the split HTTP 401/403 UseCases. These identifiers select
# checks; they contain no expected mappings, review outcomes or acceptance rules.
REQUIREMENT_SCOPE = frozenset({
    'A_26489-02', 'A_26560-01', 'A_27727-02', 'A_28527-01',
    'A_25760',
    'A_26586-01', 'A_27496', 'A_27802-03',
    'A_28988', 'A_29020', 'A_29046', 'A_29376',
    'A_29374', 'A_29380', 'A_29658', 'A_29659', 'A_29660', 'A_29671', 'A_29672',
    'A_29781', 'A_29782', 'A_29809', 'A_29810',
    'A_29820', 'A_29843', 'A_29848', 'A_29853', 'A_29859',
    'A_29860', 'A_29861', 'A_29862',
    'A_29893', 'A_29896', 'A_29897', 'A_29900',
    'A_29901', 'A_29902', 'A_29907', 'A_29933', 'A_29934',
    'A_29935', 'A_29936', 'A_29937', 'A_29938', 'A_29939',
    'A_29951', 'A_29953', 'A_29960', 'A_29970', 'A_29971',
    'A_29972', 'A_29973', 'A_29979', 'A_29980', 'A_29981', 'A_29982',
    'A_29983', 'A_29986', 'A_29987', 'A_29988', 'A_29989', 'A_29991', 'A_29992',
    'A_29993', 'A_29994', 'A_29996', 'A_29997', 'A_29998',
    'A_29999', 'A_30000', 'A_30001', 'A_30002', 'A_30005',
    'A_27379-02',
})
SUPPORTING_REQUIREMENT_SCOPE = frozenset({
    'A_25661-01', 'A_25665-01', 'A_26477-01', 'A_26486-01', 'A_26668-02',
    'A_26972-03', 'A_26973-02', 'A_26988', 'A_28525-02', 'A_29676',
})
USECASE_SCOPE = frozenset({
    'UC_01_24', 'UC_01_13', 'UC_01_14', 'UC_TELEMETRIE_01',
    'UC_01_06', 'UC_01_07', 'UC_01_08', 'UC_01_09', 'UC_01_11', 'UC_01_12',
    'UC_01_16', 'UC_01_17', 'UC_01_27',
    'UC_01_26', 'UC_01_33',
    'UC_01_31', 'UC_01_32', 'UC_02_01', 'UC_02_03', 'UC_02_04', 'UC_02_05',
    'UC_02_06', 'UC_02_07', 'UC_02_08', 'UC_02_09', 'UC_02_10', 'UC_02_11',
    'UC_02_12', 'UC_02_13', 'UC_03_01',
    'UC_03_02', 'UC_03_03', 'UC_03_04', 'UC_04_01', 'UC_04_02', 'UC_04_03', 'UC_04_04',
    'UC_04_05', 'UC_04_06', 'UC_04_07', 'UC_04_08', 'UC_04_09',
    'UC_04_10', 'UC_04_11', 'UC_04_12', 'UC_04_13', 'UC_04_14',
    'UC_GENERIC_02', 'UC_TELEMETRIE_02',
    'UC_TELEMETRIE_03',
    'UC_TLS_02',
})

REF = re.compile(r"<<(TA_[^>,]+)(?:,[^>]+)?>>")
ASPECT_ID = re.compile(r"\bTA_[A-Za-z0-9_-]+\b")
CELL = re.compile(r"(?m)^\| ([^|\n]+?): \|[ \t]*(?:\n)?")


def normalize(value: str) -> str:
  return re.sub(r"\s+", " ", value.replace("\u00ad", "")).strip()


def source_wording(value: str) -> str:
  """Keep source wording separate from the structural requirement-end marker."""
  return normalize(value).removesuffix("[<=]").rstrip()


def local_path(root: Path, relative: str) -> Path:
  path = (root / relative).resolve()
  if not path.is_relative_to(root.resolve()):
    raise ValueError(f"Path outside project: {relative}")
  return path


def inline_wording(text: str) -> str:
  """Remove inline formatting while preserving literal source characters."""
  text = re.sub(r"\+\+\+(.+?)\+\+\+", r"\1", text, flags=re.S).replace("``", "")
  return re.sub(r"(?<![\w*])\*(?![\s*])([^*\n]+?)(?<!\s)\*(?!\w)", r"\1", text)


def quotation(text: str) -> tuple[str, str]:
  """Extract the source block in current and legacy AsciiDoc layouts."""
  parts = text.split("\n\n", 1)
  if len(parts) != 2 or not parts[1].strip():
    raise ValueError("Missing quotation after the TestAspect heading")
  body = parts[1].strip()
  marker = re.search(r'(?:(?:\*\*)?\[<=\](?:\*\*)?"?(?:\*\*)?)', body)
  if marker:
    marked, notes = body[:marker.start()].strip(), body[marker.end():].strip()
    if marked.startswith('"'):
      marked = marked[1:]
  else:
    # Migrated aspects can contain a bare source paragraph without a terminator.
    marked, _, notes = body.partition("\n\n")
    if marked.startswith('"'):
      if len(marked) < 2 or not marked.endswith('"'):
        raise ValueError("Quoted source block has no closing marker")
      marked = marked[1:-1]
  marked = inline_wording(marked).replace("[...]", " ... ")
  marked = re.sub(r"<br\s*/?>", " ", marked)
  # Unordered-list markers are formatting, not part of the quoted source text.
  marked = re.sub(r"(?m)^\*[ \t]+", "", marked)
  # Table delimiters are formatting, not source wording. Preserve cell order.
  if re.search(r"(?m)^\|===\s*$", marked):
    marked = re.sub(r"(?m)^\|===\s*$", "", marked)
    marked = "\n".join(line.replace("|", " ") if line.lstrip().startswith("|") else line
                       for line in marked.splitlines())
  return marked.strip(), notes.strip()


def quote_spans(marked: str, original: str) -> tuple[list, list]:
  """Map exact quotation segments and bold spans onto normalized source offsets.

  Full quotations take precedence, preserving literal ellipses in the source.
  Otherwise ... denotes omissions. Ambiguous matches fail instead of guessing.
  """
  marked = normalize(marked)
  original = normalize(original)
  plain = marked.replace("**", "")
  if plain != original and "..." not in marked:
    raise ValueError("Quoted text differs from source without an explicit omission")
  segments = [marked] if plain == original else marked.split("...")
  quoted, highlighted = [], []
  cursor = 0
  for segment in segments:
    segment = segment.strip()
    if not segment:
      continue
    raw = segment.replace("**", "")
    start = original.find(raw, cursor)
    if start < 0:
      raise ValueError("Quoted text differs from the source or is out of order")
    if len(segments) > 1 and original.find(raw, start + 1) >= 0:
      raise ValueError("Ambiguous quotation segment")
    if segment.count("**") % 2:
      raise ValueError("Unbalanced bold markup")
    quoted.append((start, start + len(raw)))
    for match in re.finditer(r"\*\*(.+?)\*\*", segment):
      offset = len(segment[:match.start()].replace("**", ""))
      highlighted.append((start + offset, start + offset + len(match[1])))
    cursor = start + len(raw)
  if not quoted:
    raise ValueError("Empty quotation")
  return quoted, highlighted


def uncovered(text: str, spans: list, meaningful_only: bool = True) -> list[str]:
  covered = [False] * len(text)
  for start, end in spans:
    covered[start:end] = [True] * (end - start)
  return [text[m.start():m.end()].strip()
          for m in re.finditer(r"0+", "".join("1" if b else "0" for b in covered))
          if (any(c.isalnum() for c in text[m.start():m.end()]) if meaningful_only
              else bool(text[m.start():m.end()].strip()))]


def fields(text: str) -> tuple[dict, list]:
  table = text.split("|===")
  if len(table) != 3:
    raise ValueError("Expected exactly one UseCase table")
  matches = list(CELL.finditer(table[1]))
  names = [m[1].replace("*", "").strip() for m in matches]
  cells = {name: table[1][m.end():matches[i + 1].start()
                        if i + 1 < len(matches) else len(table[1])].strip()
           for i, (name, m) in enumerate(zip(names, matches))}
  return cells, names


def structural_report(findings: list, **details) -> dict:
  """Never let a consistency result or stored review certify semantic validity."""
  return {
      "assessment": {
          "type": "mechanical-source-and-consistency-checks",
          "independent_validation": "not_performed",
          "semantic_correctness": "not_verified",
          "coverage_completeness": "not_verified",
          "runtime_compliance": "not_tested",
          "source_authority": "not_verified",
      },
      "findings": findings,
      **details,
      "limits": [
          "Exit code zero means only that the listed mechanical checks passed.",
          "Current mappings, gaps and overlaps are observations requiring fresh review.",
          "Stored design expectations and review conclusions are not accepted as inputs.",
          "Source versions and changes are tracked by Git; no separate expected-hash inventory is checked.",
          "Fresh analysis against the applicable specification and current code is required for semantic conclusions.",
          "Schema/table coverage, functional completeness, test feasibility and runtime conformity are not checked.",
      ],
  }


def check(root: Path, rendered: Path | None = None) -> dict:
  findings, coverage = [], {}

  def fail(code, target, detail):
    findings.append({"code": code, "target": str(target), "detail": detail})

  catalog = ET.fromstring(render_catalog((root / DEFAULT_XLSX).read_bytes()))
  requirements = {r.attrib["id"]: r for r in catalog.findall(".//requirement")}
  source_scope = REQUIREMENT_SCOPE | SUPPORTING_REQUIREMENT_SCOPE
  source_paths = sorted(path for path in (root / AFO_ROOT).glob("*/A_*.adoc")
                        if path.stem in source_scope)
  source_ids = {p.stem for p in source_paths}
  for aid in sorted(source_scope):
    requirement = requirements.get(aid)
    if requirement is None:
      fail("unknown-afo", aid, "Not in workbook")
    elif not (root / AFO_ROOT / requirement.findtext("sourceDocumentId") / f"{aid}.adoc").is_file():
      fail("source-missing", aid, "No active AFO found in its source-document directory")
  selected = sorted(source_ids & REQUIREMENT_SCOPE)
  tas, ta_paths, notes, all_spans = {}, {}, {}, defaultdict(list)
  uc_paths = {}
  for path in sorted((root / UC_ROOT).glob("UserStory_*/UseCase_*/readme.adoc")):
    text = path.read_text(encoding="utf-8")
    anchors = re.findall(r"(?m)^\[#(UC_[^\]]+)\]", text)
    for anchor in anchors:
      if anchor in uc_paths:
        fail("duplicate-usecase", anchor, "More than one definition")
      uc_paths[anchor] = path

  for path in source_paths:
    requirement = requirements.get(path.stem)
    text = path.read_text(encoding="utf-8")
    if requirement is None:
      fail("source-afo", path.stem, "Not in the source workbook")
      continue
    if path.parent.name != requirement.findtext("sourceDocumentId"):
      fail("source-folder", path.stem, "Wrong source-document directory")
    lines = text.splitlines()
    if (len(lines) < 4 or lines[0] != f"[#{path.stem}]" or lines[2].strip()
        or normalize(lines[1]) != normalize(f"==== {path.stem} - {requirement.findtext('title')}")
        or normalize("\n".join(lines[3:])) != normalize(requirement.findtext("description"))):
      fail("source-afo", path.stem, "Not reproducible from the workbook")

  for aid in selected:
    requirement = requirements.get(aid)
    if requirement is None:
      fail("unknown-afo", aid, "Not in workbook")
      continue
    source = requirement.findtext("sourceDocumentId")
    folder = root / TA_ROOT / source / aid
    for other in (root / TA_ROOT).glob("*/" + aid):
      if other != folder:
        fail("source-folder", aid, "TestAspect group under the wrong source document")
    paths = {p.stem: p for p in sorted(folder.glob("TA_*.adoc"))}
    if not paths:
      fail("missing-aspects", aid, "No current TestAspect files found")
    original = source_wording(requirement.findtext("description"))
    spans, quote_union, overlap = {}, [], []
    for tid, path in paths.items():
      text = path.read_text(encoding="utf-8")
      tas[tid], ta_paths[tid] = text, path
      if re.findall(r"(?m)^\[#([^\]]+)\]", text) != [tid]:
        fail("ta-anchor", tid, "Filename and unique anchor must agree")
      try:
        marked, notes[tid] = quotation(text)
        quoted, bold = quote_spans(marked, original)
        quote_union.extend(quoted)
        spans[tid] = bold
        all_spans[aid].extend(bold)
        if not bold and not re.search(r"\*\*.+?\*\*", notes[tid]):
          fail("missing-focus", tid, "No highlighted focus inside or outside quotation")
      except ValueError as error:
        fail("quotation", tid, str(error))
    missing_quote = uncovered(original, quote_union, meaningful_only=False)
    if missing_quote:
      fail("quotation-omission", aid, missing_quote)
    gaps = uncovered(original, all_spans[aid])
    # Historical explanations never approve gaps or overlaps. Report observations only.
    for left, right in itertools.combinations(sorted(spans), 2):
      shared = sorted({original[max(a, c):min(b, d)] for a, b in spans[left]
                       for c, d in spans[right] if max(a, c) < min(b, d)})
      if shared:
        overlap.append({"aspects": [left, right], "text": shared})
    coverage[aid] = {"source_characters": len(original), "unhighlighted": gaps,
                     "overlaps": overlap, "quotation_omissions": missing_quote,
                     "semantic_review": "required_against_current_sources"}

  template, _ = fields((root / UC_ROOT / "Use_Case_Template.adoc").read_text(encoding="utf-8"))
  allowed = set(template) | {"weitere Akteure", "Hinweis"}  # Existing optional UseCase fields.
  assignments, diagram_names = defaultdict(set), {}
  all_ids = {p.stem for p in (root / TA_ROOT).glob("*/*/TA_*.adoc")}
  scoped_ids = set(tas)
  touched = set(uc_paths) & USECASE_SCOPE
  for uid, path in sorted(uc_paths.items()):
    text = path.read_text(encoding="utf-8")
    try:
      cells, names = fields(text)
    except ValueError as error:
      if uid in touched:
        fail("usecase-table", uid, str(error))
      continue
    covered = REF.findall(cells.get("Abgedeckte Testaspekte", ""))
    for tid in covered:
      assignments[tid].add(uid)
    sections = {name: value for name, value in cells.items() if name != "Abgedeckte Testaspekte"}
    before, table, after = text.split("|===")
    first_field = CELL.search(table)
    sections.update({"before-table": before, "after-table": after,
                     "table-preamble": table[:first_field.start()] if first_field else table})
    misplaced = {}
    for name, content in sections.items():
      ids = set(ASPECT_ID.findall(content))
      if uid not in touched:
        # Follow selected aspects into their callers without expanding structure scope.
        ids &= scoped_ids
      if ids:
        misplaced[name] = sorted(ids)
    if misplaced:
      fail("reference-placement", uid, misplaced)
    if uid not in touched:
      continue
    missing = [key for key in template if not cells.get(key)]
    unknown = sorted(set(cells) - allowed)
    duplicates = sorted(k for k, n in Counter(names).items() if n > 1)
    if missing or unknown or duplicates:
      fail("usecase-fields", uid, {"missing": missing, "unknown": unknown, "duplicate": duplicates})
    if [name for name in names if name in template] != list(template):
      fail("usecase-field-order", uid, "Standard fields must follow template order")
    if re.search(r"(?m)^={3,} |Geplante Ergänzungen|Geplante Testaspekte", text):
      fail("usecase-section", uid, "Nonstandard appended section or field")
    if len(covered) != len(set(covered)):
      fail("duplicate-assignment", uid, "Repeated entry in Abgedeckte Testaspekte")
    for tid in sorted(set(REF.findall(text)) - all_ids):
      fail("unknown-reference", uid, tid)
    diagrams = re.findall(r"(?m)^\[mermaid(?:,([^,\]]+),svg)?\]", text)
    if len(diagrams) != 1:
      fail("diagram-count", uid, "Expected one integrated sequence diagram")
    else:
      diagram_names[uid] = diagrams[0]
      if text.index("[mermaid") < text.rindex("|==="):
        fail("diagram-placement", uid, "Sequence diagram must follow the main table")
  # Follow the maintained document entrypoint, excluding executable feature includes.
  assembled, anchors = Counter(), Counter()

  def walk(path, stack=()):
    path = path.resolve()
    if path in stack:
      fail("include-cycle", path.relative_to(root).as_posix(), "Recursive include")
      return
    if not path.exists():
      fail("include-missing", path.relative_to(root).as_posix(), "Missing AsciiDoc file")
      return
    text = path.read_text(encoding="utf-8")
    assembled[path] += 1
    anchors.update(re.findall(r"(?m)^\[#([^\]]+)\]", text))
    for name in re.findall(r"(?m)^include::([^\[]+)\[", text):
      if name.endswith(".adoc") and "{" not in name:
        child = (path.parent / name).resolve()
        if not child.is_relative_to(root.resolve()):
          fail("include-path", name, "Outside project")
        else:
          walk(child, stack + (path,))

  walk(root / "docs/asciidoc/Testplan_ZETA.adoc")
  required_anchors = set(tas) | set(selected) | touched
  for anchor in sorted(required_anchors):
    if anchors[anchor] != 1:
      fail("assembled-anchor", anchor, f"Expected once, found {anchors[anchor]}")

  if rendered is not None:
    doc = html.parse(str(local_path(root, str(rendered))))
    counts = Counter(doc.xpath("//*[@id]/@id"))
    for anchor in sorted(required_anchors):
      if counts[anchor] != 1:
        fail("rendered-anchor", anchor, f"Expected once, found {counts[anchor]}")
    for tid, text in tas.items():
      nodes = doc.xpath("//*[@id=$id]", id=tid)
      if len(nodes) == 1:
        # Inline passthrough protects literal source text from normal replacements
        # (for example SM(C)-B); compare its visible content, not its delimiters.
        expected = [normalize(s) for s in re.findall(r"\*\*(.+?)\*\*", inline_wording(text))]
        # Asciidoctor's normal replacements render the repository terminator as an arrow.
        expected = [s.replace("[<=]", "[⇐]") for s in expected]
        actual = [normalize(n.text_content()) for n in nodes[0].getparent().xpath(".//strong")]
        if Counter(expected) != Counter(actual):
          fail("rendered-focus", tid, {"expected": expected, "actual": actual})
    for uid, name in diagram_names.items():
      if name:
        images = doc.xpath("//*[@id=$id]/..//img[@alt=$alt]", id=uid, alt=name.replace("-", " "))
      else:
        images = doc.xpath("//*[@id=$id]/..//img[starts-with(@src, 'data:image/svg+xml;base64,')]", id=uid)
      if len(images) != 1:
        fail("rendered-diagram", uid, "Expected one embedded diagram")
      else:
        src = images[0].get("src", "")
        if src.startswith("data:image/svg+xml;base64,"):
          svg = etree.fromstring(base64.b64decode(src.split(",", 1)[1], validate=True),
                                 etree.XMLParser(resolve_entities=False, no_network=True))
          if "Syntax error" in "".join(svg.itertext()):
            fail("rendered-diagram", uid, "Mermaid syntax error")
        else:
          fail("rendered-diagram", uid, "Expected embedded SVG for the self-contained test plan")

  return structural_report(findings, counts={"requirements": len(selected),
          "aspects": len(tas), "usecases": len(touched),
          "afo_documents": len(source_paths),
          "required_anchors": len(required_anchors),
          "observed_unhighlighted_spans": sum(len(c["unhighlighted"]) for c in coverage.values()),
          "observed_overlaps": sum(len(c["overlaps"]) for c in coverage.values()),
          "aspects_with_additional_prose": sum(bool(n) for n in notes.values()),
          "source_workbook": DEFAULT_XLSX.as_posix()}, rendered_checked=rendered is not None, coverage=coverage,
          checked_afo_ids=sorted(source_ids),
          checked_requirement_ids=sorted(selected), checked_aspect_ids=sorted(tas),
          checked_usecase_ids=sorted(touched),
          observed_assignments={tid: sorted(assignments[tid]) for tid in sorted(tas)})


def main(argv: list[str] | None = None) -> int:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--project-root", type=Path, default=Path.cwd())
  parser.add_argument("--rendered", type=Path)
  parser.add_argument("--output", type=Path, default=Path("target/design-verification.json"))
  args = parser.parse_args(argv)
  root = args.project_root.resolve()
  try:
    report = check(root, args.rendered)
  except (ValueError, KeyError, OSError, BadZipFile, ET.ParseError, etree.Error) as error:
    report = structural_report([{"code": "invalid-input", "target": str(DEFAULT_XLSX), "detail": str(error)}])
  destination = local_path(root, str(args.output))
  destination.parent.mkdir(parents=True, exist_ok=True)
  destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
  print(json.dumps({"assessment": report["assessment"], "findings": len(report["findings"]), "counts": report.get("counts", {}),
                    "report": str(args.output)}, ensure_ascii=True))
  return int(bool(report["findings"]))


if __name__ == "__main__":
  raise SystemExit(main())
