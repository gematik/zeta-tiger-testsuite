"""
Generate AsciiDoc AFO files from a gematik ZETA Guard requirement XML.

The script reads the gemVZ XML, filters requirements (default: only
``Produkttest/Produktübergreifender Test``), writes one ``A_*.adoc`` per
requirement into a target folder (default: ``docs/asciidoc/afos/gemSpec_ZETA``)
and can create a minimal ``readme.adoc`` that lists source documents plus
includes for all generated requirements.
"""

from __future__ import annotations

import argparse
import re
import shutil
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Sequence


DEFAULT_XML = Path("docs/gemVZ_Afo_ZETA_Guard_V_1.1.0_V1.0.0.xml")
DEFAULT_OUTPUT = Path("docs/asciidoc/afos/gemSpec_ZETA")
DEFAULT_TEST_PROCEDURE = (
    'Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"'
)
ROOT_README = Path("docs/asciidoc/afos/readme.adoc")


@dataclass(frozen=True)
class SourceDocument:
  denotation: str
  identifier: str
  version: str

  def as_adoc_list_entry(self) -> str:
    base = "https://gemspec.gematik.de/docs/"
    if self.identifier.startswith("gemSpec"):
      base += "gemSpec/"
    elif self.identifier.startswith("gemKPT"):
      base += "gemKPT/"
    elif self.identifier.startswith("gemVZ"):
      base += "gemVZ/"
    base += f"{self.identifier}/{self.identifier}_V{self.version}"
    return (f"* {base}[{self.denotation}], "
            f"Kurzbezeichnung: {self.identifier}, Version: {self.version}")


@dataclass(frozen=True)
class Requirement:
  requirement_id: str
  title: str
  description: str
  source_doc: str

  def render_adoc(self) -> str:
    return (f"[#{self.requirement_id}]\n"
            f"==== {self.requirement_id} - {self.title}\n\n"
            f"{self.description}\n")


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
  parser = argparse.ArgumentParser(
      prog="generate-zeta-afos",
      description="Generate AsciiDoc AFO files from a ZETA Guard XML catalogue.",
  )
  parser.add_argument(
      "--input-xml",
      type=Path,
      default=DEFAULT_XML,
      help=f"Path to gemVZ_Afo_ZETA_Guard XML (default: {DEFAULT_XML})",
  )
  parser.add_argument(
      "--output-dir",
      type=Path,
      default=DEFAULT_OUTPUT,
      help=f"Output directory for generated AFO files (default: {DEFAULT_OUTPUT})",
  )
  parser.add_argument(
      "--test-procedure",
      default=DEFAULT_TEST_PROCEDURE,
      help=("Optional filter on <testProcedure> text. "
            "Use an empty string to disable filtering "
            f'(default: "{DEFAULT_TEST_PROCEDURE}").'),
  )
  parser.add_argument(
      "--readme",
      action=argparse.BooleanOptionalAction,
      default=True,
      help="Update docs/asciidoc/afos/readme.adoc with the generated section.",
  )
  parser.add_argument(
      "--force",
      action="store_true",
      help="Delete the output directory before writing.",
  )
  return parser.parse_args(argv)


def _load_xml(path: Path) -> ET.Element:
  if not path.exists():
    raise FileNotFoundError(f"XML not found: {path}")
  return ET.parse(path).getroot()


def _text(elem) -> str:
  return (elem.text or "").strip() if elem is not None else ""


def _strip_html_tags(value: str) -> str:
  """Remove basic HTML tags; enough for the simple description fragments."""
  return re.sub(r"<[^>]+>", "", value)


def _normalise_whitespace(value: str) -> str:
  return (value.replace(" \xa0", " ").replace("\xa0 ", " ").replace(
      "\xa0", " ").strip())


def _extract_sources(root: ET.Element) -> List[SourceDocument]:
  docs: List[SourceDocument] = []
  for doc in root.findall(".//document"):
    docs.append(
        SourceDocument(
            denotation=_text(doc.find("denotation")),
            identifier=_text(doc.find("id")),
            version=_text(doc.find("version")),
        ))
  return sorted(docs, key=lambda d: d.denotation)


def _extract_requirements(
    root: ET.Element,
    *,
    test_procedure_filter: str | None = None,
) -> List[Requirement]:
  requirements: List[Requirement] = []
  for req in root.findall(".//requirement"):
    req_id = req.get("id")
    if not req_id:
      continue
    test_proc = _text(req.find("testProcedure"))
    if test_procedure_filter:
      if test_procedure_filter not in test_proc:
        continue
    source = _text(req.find("sourceDocumentId")) or "unknown"
    title = _text(req.find("title"))
    description = _normalise_whitespace(_text(req.find("description")))
    if not description:
      desc_html = _text(req.find("description_html"))
      if desc_html:
        description = _normalise_whitespace(_strip_html_tags(desc_html))
    requirements.append(
        Requirement(
            requirement_id=req_id,
            title=title,
            description=description,
            source_doc=source,
        ))
  return sorted(requirements, key=lambda r: r.requirement_id)


def _safe_name(value: str) -> str:
  return re.sub(r"[^\w\-.]", "_", value)


def _group_by_source(requirements: Iterable[Requirement]) -> dict[str, list[Requirement]]:
  grouped: dict[str, list[Requirement]] = {}
  for req in requirements:
    key = _safe_name(req.source_doc or "unknown")
    grouped.setdefault(key, []).append(req)
  for reqs in grouped.values():
    reqs.sort(key=lambda r: r.requirement_id)
  return grouped


def _write_requirements(grouped: dict[str, list[Requirement]],
                        output_dir: Path) -> None:
  for source, reqs in grouped.items():
    target_dir = output_dir / source
    target_dir.mkdir(parents=True, exist_ok=True)
    for req in reqs:
      (target_dir / f"{req.requirement_id}.adoc").write_text(
          req.render_adoc(), encoding="utf-8")


def _render_gemspec_zeta_section(
    grouped: dict[str, list[Requirement]],
    sources: List[SourceDocument],
    spec_url: str,
) -> str:
  lines: List[str] = []
  lines.append("=== Anforderungen gemVZ AFO ZETA Guard (Produkttest 3.1.1)\n")
  source_lookup = {s.identifier: s for s in sources}
  for source_key in sorted(grouped):
    src_doc = source_lookup.get(source_key)
    heading = f"{source_key}"
    if src_doc:
      heading = f"{src_doc.denotation} ({src_doc.identifier} V{src_doc.version})"
    lines.append(f"==== {heading}\n")
    for req in grouped[source_key]:
      include_path = f"{source_key}/{req.requirement_id}.adoc"
      lines.append(f"include::{include_path}[]\n")
  return "\n".join(lines).rstrip() + "\n"


def _update_root_readme(root_readme: Path, section: str) -> None:
  """Inject or replace the prodtest section inside afos/readme.adoc."""
  heading = "=== Anforderungen gemVZ AFO ZETA Guard (Produkttest 3.1.1)"
  lines: List[str] = []
  if root_readme.exists():
    lines = root_readme.read_text(encoding="utf-8").splitlines()
  else:
    lines = ["== Identifizierte Anforderungen", ""]

  start = None
  end = len(lines)
  for idx, line in enumerate(lines):
    if line.strip().startswith(heading):
      start = idx
      continue
    # Only treat level-3 headings as the end of this section; ignore deeper
    # subsections (==== ...).
    stripped = line.lstrip()
    if start is not None and stripped.startswith("===") and not stripped.startswith(
        "====") and idx > start:
      end = idx
      break

  if start is None:
    updated = lines + ["", section.rstrip(), ""]
  else:
    updated = lines[:start] + [section.rstrip()] + lines[end:]

  root_readme.write_text("\n".join(updated) + "\n", encoding="utf-8")


def _prepare_targets(grouped: dict[str, list[Requirement]],
                     output_dir: Path,
                     force: bool) -> None:
  """Ensure per-source output folders exist (and wipe them when forced)."""
  output_dir.mkdir(parents=True, exist_ok=True)
  for source in grouped:
    target_dir = output_dir / source
    if target_dir.exists():
      if not force:
        raise FileExistsError(
            f"{target_dir} exists. Re-run with --force to overwrite.")
      shutil.rmtree(target_dir)


def main(argv: Sequence[str] | None = None) -> int:
  args = _parse_args(argv)
  root = _load_xml(args.input_xml)
  sources = _extract_sources(root)
  test_proc_filter = args.test_procedure or None
  requirements = _extract_requirements(
      root, test_procedure_filter=test_proc_filter)

  grouped = _group_by_source(requirements)
  _prepare_targets(grouped, args.output_dir, args.force)
  _write_requirements(grouped, args.output_dir)

  if args.readme:
    spec_url = (f"https://gemspec.gematik.de/docs/gemVZ/"
                f"gemVZ_Afo_ZETA_Guard/"
                f"{args.input_xml.stem}/")
    section = _render_gemspec_zeta_section(grouped, sources, spec_url)
    _update_root_readme(ROOT_README, section)

  print(
      f"Generated {len(requirements)} requirements in {args.output_dir.as_posix()}"
  )
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
