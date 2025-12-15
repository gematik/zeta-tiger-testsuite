"""Utilities for generating Asciidoc test-aspect stubs from Excel exports.

The tooling in this module is designed to support the documentation workflow
within the testsuite project. The script reads the Tiger Issues Excel export,
creates one Asciidoc file per test aspect, and builds a set of structured
`readme.adoc` indexes so the generated content can be included from the manual.

The module exposes both a reusable Python API (`generate_testaspects`) and a
command line entry point (`update-testaspects`). The CLI resolves default paths
relative to the `docs` directory and performs thorough validation to surface
misconfigurations early.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
from collections.abc import Sequence, Iterable
from dataclasses import dataclass
from pathlib import Path

import pandas as pd

DOCS_DIR = Path(__file__).resolve().parents[3]
DEFAULT_EXCEL_PATH = DOCS_DIR / "Issues.xlsx"
DEFAULT_OUTPUT_DIR = DOCS_DIR / "asciidoc" / "testaspekte"

# Matches a single backslash that only escapes characters understood by Asciidoc.
_ESCAPED_CHAR_PATTERN = re.compile(r"\\(?=[\[\](){}<>*+\-=!/._#~:;,])")


@dataclass
class GenerationResult:
  """Summary of the content generation process.

  Attributes:
      created: Number of `.adoc` files written to disk.
      skipped: Number of rows that were ignored because mandatory columns were
          missing values (for instance an empty TA_ID or AFO_ID).
  """

  created: int
  skipped: int


def clear_output_base(base_folder: Path) -> None:
  """Remove all files and subdirectories from ``base_folder``.

  The function keeps the directory itself intact, which is useful when the
  output folder is already registered with Asciidoctor. Missing directories
  are a no-op, making the routine safe to call before the first generation.
  """
  if not base_folder.exists():
    return

  for child in base_folder.iterdir():
    if child.is_dir():
      shutil.rmtree(child)
    else:
      child.unlink()


def sanitize_name(value: str | None) -> str:
  """Return a filesystem-friendly variant of ``value``.

  The Implementation trims surrounding whitespace, removes matching leading
  and trailing quotes, replaces characters that are invalid on major
  filesystems, and limits the length to 250 characters. ``None`` is converted
  to an empty string so callers can safely test for "truthiness".
  """
  if value is None:
    return ""

  sanitized = str(value).strip()
  if (sanitized.startswith('"') and sanitized.endswith('"')) or (
      sanitized.startswith("'") and sanitized.endswith("'")
  ):
    sanitized = sanitized[1:-1].strip()
  sanitized = re.sub(r'[<>:"/\\|?*\n\r\t]+', "_", sanitized)
  return sanitized[:250]


def clean_description(desc: str | None) -> str:
  """Normalise rich-text artefacts inside the description column.

  The Excel export can contain markdown-like escape sequences and helper tags.
  We strip single escaping backslashes, translate the ``###Table###`` marker
  into a proper Asciidoc table delimiter, drop HTML line breaks, and ensure
  non-breaking spaces do not bleed into the resulting documents.

  Example:
      >>> clean_description(r"\\*Bold\\* and ###Table###")
      '*Bold* and |==='
  """
  if desc is None:
    return ""

  cleaned = str(desc).strip()
  cleaned = _ESCAPED_CHAR_PATTERN.sub("", cleaned)
  cleaned = cleaned.replace("###Table###", "|===")
  cleaned = cleaned.replace("\u00A0", " ")
  cleaned = cleaned.replace("SM(C)-B", "+++SM(C)-B+++")
  cleaned = cleaned.replace("<br />", "+++<br />+++")
  cleaned = cleaned.replace("<br/>", "+++<br/>+++")
  return cleaned


def create_readmes(base_folder: Path) -> None:
  """Generate ``readme.adoc`` files in every AFO folder.

  Each AFO directory receives a short overview that includes all generated
  aspect files. These include directives make it trivial to assemble the
  content from the manual without hard-coding file names in the source.
  """
  for root, _, files in os.walk(base_folder):
    adoc_files = [
      name for name in files if
      name.endswith(".adoc") and name.lower() != "readme.adoc"
    ]
    if not adoc_files:
      continue

    afo_id = Path(root).name
    readme_path = Path(root) / "readme.adoc"
    with readme_path.open("w", encoding="utf-8") as handle:
      handle.write(f"==== Testaspekte für die Anforderung {afo_id}\n\n")
      handle.write(f"Folgende Testaspekte testen <<{afo_id}>>.\n\n")
      for file_name in sorted(adoc_files):
        handle.write(f"include::{file_name}[]\n\n")


def create_spec_readmes(base_folder: Path) -> None:
  """Create ``readme.adoc`` indexes for each specification version."""
  for spec_folder_name in os.listdir(base_folder):
    spec_folder_path = Path(base_folder) / spec_folder_name
    if not spec_folder_path.is_dir():
      continue

    afo_dirs = [entry for entry in spec_folder_path.iterdir() if entry.is_dir()]
    if not afo_dirs:
      continue

    readme_path = spec_folder_path / "readme.adoc"
    with readme_path.open("w", encoding="utf-8") as handle:
      handle.write(f"=== Testaspekte aus {spec_folder_name}\n\n")
      for afo_dir in sorted(afo_dirs):
        include_path = (afo_dir.name + "/readme.adoc").replace("\\", "/")
        handle.write(f"include::{include_path}[]\n\n")


def create_base_readme(base_folder: Path) -> None:
  """Write the top-level ``readme.adoc`` for the generated structure."""
  spec_dirs = [entry for entry in Path(base_folder).iterdir() if entry.is_dir()]
  if not spec_dirs:
    return

  readme_path = Path(base_folder) / "readme.adoc"
  with readme_path.open("w", encoding="utf-8") as handle:
    handle.write("== Abgeleitete Testaspekte\n\n")
    handle.write(
        "Es wurden Testaspekte aus den identifizierten Anforderungen generiert,"
        " die im Folgenden aufgeführt werden.\n\n"
    )
    for spec_dir in sorted(spec_dirs, key=lambda path: path.name):
      include_path = (spec_dir.name + "/readme.adoc").replace("\\", "/")
      handle.write(f"include::{include_path}[]\n\n")


def resolve_input_path(path: Path, search_roots: Iterable[Path]) -> Path:
  """Resolve ``path`` relative to a set of search roots."""
  candidate = path.expanduser()
  if candidate.is_absolute():
    return candidate

  roots = tuple(search_roots)
  if not roots:
    raise ValueError("search_roots must contain at least one path")

  for root in roots:
    resolved = (root / candidate).resolve()
    if resolved.exists():
      return resolved
  # Fall back to the first root even if the file does not exist yet.
  return (roots[0] / candidate).resolve()


def generate_testaspects(excel_path: Path,
    output_dir: Path) -> GenerationResult:
  """Create the Asciidoc structure from the provided Excel input."""
  df = pd.read_excel(excel_path, dtype=str).fillna("")

  output_dir.mkdir(parents=True, exist_ok=True)
  clear_output_base(output_dir)

  created = 0
  skipped = 0

  for _, row in df.iterrows():
    ta_id = sanitize_name(row.get("TA_ID"))
    afo_id = sanitize_name(row.get("AFO_ID"))
    spec_version_raw = row.get("Spec Version", "")
    summary_raw = row.get("Summary", "")
    description_raw = row.get("Description", "")

    if not ta_id or not afo_id:
      skipped += 1
      continue

    summary = "" if summary_raw is None else str(summary_raw).strip()
    description = clean_description(description_raw)
    spec_folder = sanitize_name(
        spec_version_raw.split()[0] if spec_version_raw else "UnknownSpec"
    )

    afo_dir = output_dir / spec_folder / afo_id
    afo_dir.mkdir(parents=True, exist_ok=True)

    file_path = afo_dir / f"{ta_id}.adoc"
    content = f"[#{ta_id}]\n===== {summary}\n\n{description}\n"
    file_path.write_text(content, encoding="utf-8")
    created += 1

  create_readmes(output_dir)
  create_spec_readmes(output_dir)
  create_base_readme(output_dir)

  return GenerationResult(created=created, skipped=skipped)


def build_parser() -> argparse.ArgumentParser:
  """Construct the CLI argument parser.

  All options are optional: the script defaults to reading ``docs/Issues.xlsx``
  and writing into ``docs/asciidoc/testaspekte`` relative to the repository
  root. Users can override both via the exposed flags.
  """
  parser = argparse.ArgumentParser(
      description="Generate Asciidoc test-aspect files from an Issues Excel export."
  )
  parser.add_argument(
      "--excel-path",
      type=Path,
      default=DEFAULT_EXCEL_PATH,
      help="Pfad zur Excel-Quelldatei (Standard: docs/Issues.xlsx).",
  )
  parser.add_argument(
      "--output-dir",
      type=Path,
      default=DEFAULT_OUTPUT_DIR,
      help="Zielordner für generierte Testaspekte (Standard: docs/asciidoc/testaspekte).",
  )
  return parser


def main(argv: Sequence[str] | None = None) -> int:
  """Entry point used by the `update-testaspects` console script."""
  parser = build_parser()
  args = parser.parse_args(argv)

  excel_path = resolve_input_path(args.excel_path, (Path.cwd(), DOCS_DIR))
  if not excel_path.exists():
    parser.error(f"Excel-Datei nicht gefunden: {excel_path}")

  output_dir = args.output_dir.expanduser()
  if not output_dir.is_absolute():
    output_dir = (DOCS_DIR.parent / output_dir).resolve()

  result = generate_testaspects(excel_path, output_dir)

  print(
      f"Fertig: {result.created} Dateien erzeugt, {result.skipped} Zeilen uebersprungen.")
  print(f"Ausgabeordner: {output_dir}")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
