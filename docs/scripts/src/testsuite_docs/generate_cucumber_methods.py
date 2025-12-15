"""Helper script to generate an Asciidoc table of project-specific TGR/Cucumber steps.

Extracts:
* Local glue steps (Cucumber annotations) with short descriptions derived from Javadoc.

Output: an Asciidoc table (German text) for inclusion in the docs; by default written to
`docs/asciidoc/tables/cucumber_methods_table.adoc`.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable, Sequence

import pandas as pd
from pytablewriter import AsciiDocTableWriter


@dataclass(frozen=True)
class StepEntry:
  """Container for a method-level set of step phrases."""

  german: str | None
  english: str | None
  description: str
  origin: Path

  def file_relative(self, repo_root: Path) -> str:
    try:
      return str(self.origin.relative_to(repo_root))
    except ValueError:
      return str(self.origin)


class H5Collector(HTMLParser):
  """Collects the text content of <h5> headings from an HTML document."""

  def __init__(self) -> None:
    super().__init__()
    self.headings: list[str] = []
    self._capture = False
    self._buffer: list[str] = []

  def handle_starttag(self, tag: str, attrs) -> None:  # type: ignore[override]
    if tag.lower() == "h5":
      self._capture = True
      self._buffer = []

  def handle_data(self, data: str) -> None:  # type: ignore[override]
    if self._capture:
      self._buffer.append(data)

  def handle_endtag(self, tag: str) -> None:  # type: ignore[override]
    if tag.lower() == "h5" and self._capture:
      text = " ".join("".join(self._buffer).split()).strip()
      if text:
        self.headings.append(text)
      self._capture = False
      self._buffer = []


def find_repo_root(start: Path) -> Path:
  """Locate the repository root by looking for a pom.xml up the tree."""
  for candidate in [start, *start.parents]:
    if (candidate / "pom.xml").exists():
      return candidate
  return start


def extract_manual_steps(html_path: Path) -> list[str]:
  """Parse Tiger-User-Manual.html and return TGR headings (for completeness)."""
  if not html_path.exists():
    return []

  parser = H5Collector()
  parser.feed(html_path.read_text(encoding="utf-8"))

  return [heading for heading in parser.headings if heading.startswith("TGR ")]


def extract_project_glue(glue_root: Path) -> list[StepEntry]:
  """Scan Java glue classes, grouping German/English annotations per method."""
  step_pattern = re.compile(
    r"@(Gegebensei|Wenn|Dann|Given|When|Then)\s*\(\s*\"(?P<phrase>[^\"]+)\""
  )

  entries: list[StepEntry] = []

  for java_file in glue_root.rglob("*.java"):
    try:
      lines = java_file.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
      lines = java_file.read_text(errors="ignore").splitlines()

    current_javadoc: list[str] = []
    collecting_javadoc = False
    annotations: list[str] = []

    for line in lines:
      stripped = line.strip()

      # Javadoc start/end handling
      if stripped.startswith("/**"):
        collecting_javadoc = True
        current_javadoc = [stripped]
        continue
      if collecting_javadoc:
        current_javadoc.append(stripped)
        if "*/" in stripped:
          collecting_javadoc = False
        continue

      # Annotations before method
      if stripped.startswith("@"):
        annotations.append(stripped)
        continue

      # Method signature detection (simple heuristic)
      if re.match(r"^(public|protected|private)\s+[\w<>\[\]\s,?]+\s+\w+\s*\(", stripped):
        german = None
        english = None
        for ann in annotations:
          m = step_pattern.search(ann)
          if not m:
            continue
          phrase = " ".join(m.group("phrase").split())
          annot = m.group(1)
          if annot in {"Gegebensei", "Wenn", "Dann"}:
            german = phrase
          elif annot in {"Given", "When", "Then"}:
            english = phrase

        if german or english:
          docstring = normalize_javadoc("\n".join(current_javadoc)) if current_javadoc else ""
          entries.append(
            StepEntry(
              german=german,
              english=english,
              description=docstring,
              origin=java_file,
            )
          )

        # reset for next method
        current_javadoc = []
        annotations = []

    # safety reset at file end
    current_javadoc = []
    annotations = []

  return deduplicate_entries(entries)


def normalize_javadoc(raw: str) -> str:
  """Strip leading comment markers and condense whitespace to a short description."""
  lines = []
  for line in raw.splitlines():
    line = line.strip()
    if line.startswith("/**"):
      line = line[3:]
    if line.endswith("*/"):
      line = line[:-2]
    if line.startswith("*"):
      line = line[1:].lstrip()
    lines.append(line)
  text = " ".join(" ".join(lines).split())
  # Keep only the first sentence-ish to avoid overly long table cells.
  match = re.split(r"(?<=[.!?])\s", text, maxsplit=1)
  return match[0] if match else text


def deduplicate_entries(entries: Iterable[StepEntry]) -> list[StepEntry]:
  """Preserve first occurrence per (german, english) pair to avoid duplicates."""
  seen: dict[tuple[str | None, str | None, Path], StepEntry] = {}
  for entry in entries:
    key = (entry.german, entry.english, entry.origin.resolve())
    if key not in seen:
      seen[key] = entry
  return list(seen.values())


def render_asciidoc_table(
  project_steps: Sequence[StepEntry],
  repo_root: Path,
) -> str:
  """Render only the table body as Asciidoc (German column titles)."""
  df = pd.DataFrame(
    [
      {
        "Deutsch": entry.german or "-",
        "Englisch": entry.english or "-",
        "Datei": entry.file_relative(repo_root),
        "Beschreibung": entry.description or "-",
      }
      for entry in sorted(
        project_steps,
        key=lambda item: (item.german or item.english or "").lower(),
      )
    ]
  )

  writer = AsciiDocTableWriter()
  writer.headers = list(df.columns)
  writer.value_matrix = df.values.tolist()
  table = writer.dumps()
  header = (
    "// Dieses Tabellenfragment wird automatisch von generate-cucumber-methods erzeugt.\n"
    "// Nicht manuell bearbeiten; Änderungen an Glue-Klassen + Skript neu ausführen.\n\n"
  )
  return header + table + "\n"


def escape_pipes(value: str) -> str:
  """Escape table cell separators in Asciidoc content."""
  return value.replace("|", "\\|")


def build_parser(default_repo: Path) -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
    prog="generate-cucumber-methods",
    description=(
      "Generate an Asciidoc table of project-specific TGR/Tiger Cucumber step phrases."
    ),
  )
  parser.add_argument(
    "--project-root",
    type=Path,
    default=default_repo,
    help="Repository root (defaults to auto-detected value).",
  )
  parser.add_argument(
    "--glue-dir",
    type=Path,
    default=None,
    help="Root directory containing project glue (defaults to src/test/java).",
  )
  parser.add_argument(
    "--output",
    type=Path,
    default=None,
    help=(
      "Target Asciidoc file. Defaults to "
      "`docs/asciidoc/tables/cucumber_methods_table.adoc`."
    ),
  )
  return parser


def main(argv: list[str] | None = None) -> int:
  repo_root = find_repo_root(Path(__file__).resolve())
  parser = build_parser(default_repo=repo_root)
  args = parser.parse_args(argv)

  repo_root = args.project_root.resolve()
  glue_root = (args.glue_dir or repo_root / "src" / "test" / "java").resolve()

  project_steps = extract_project_glue(glue_root)

  document = render_asciidoc_table(project_steps, repo_root)

  if args.output:
    output_path = args.output.resolve()
  else:
    output_path = (repo_root / "docs" / "asciidoc" / "tables" /
                   "cucumber_methods_table.adoc").resolve()

  output_path.parent.mkdir(parents=True, exist_ok=True)
  output_path.write_text(document, encoding="utf-8")
  print(f"Wrote {len(project_steps)} project steps to {output_path}")

  return 0


if __name__ == "__main__":
  raise SystemExit(main())
