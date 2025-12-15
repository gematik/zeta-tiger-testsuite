"""
Utilities to expose Asciidoc requirement narratives to Serenity by converting the existing
readme.adoc files in the feature tree into Markdown counterparts that Serenity can render.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re

from pydowndoc import OUTPUT_CONVERSION_TO_STRING, ConversionError, convert_file


def parse_args() -> argparse.Namespace:
  """CLI argument parsing for the converter script."""
  parser = argparse.ArgumentParser(
      description=(
        "Convert readme.adoc files below the feature tree into readme.md "
        "so that Serenity can render the narratives."
      )
  )
  parser.add_argument(
      "--project-root",
      type=Path,
      default=Path("."),
      help="Repository root (defaults to current directory).",
  )
  parser.add_argument(
      "--features-dir",
      type=Path,
      default=Path("src/test/resources/features"),
      help="Relative path to the feature directory.",
  )
  parser.add_argument(
      "--dry-run",
      action="store_true",
      help="Show the files that would be updated without modifying them.",
  )
  parser.add_argument(
      "--verbose",
      action="store_true",
      help="Print every processed file (not only the modified ones).",
  )
  return parser.parse_args()


def convert_readme(adoc_path: Path, dry_run: bool = False,
    features_root: Path | None = None) -> bool:
  """
  Convert a single readme.adoc file into Markdown using Pydowndoc.

  Returns True when the companion readme.md would change (or does change) so the caller can track
  the number of updates performed.
  """
  md_path = adoc_path.with_suffix(".md")
  try:
    markdown = convert_file(
        adoc_path,
        output_location=OUTPUT_CONVERSION_TO_STRING,
    )
    markdown = markdown or ""
    markdown = format_numbered_lists_in_tables(markdown)
    markdown = normalize_requirement_heading(
        markdown, adoc_path, features_root=features_root)

    if md_path.exists() and md_path.read_text() == markdown:
      return False
    if dry_run:
      return True
    md_path.write_text(markdown)
    return True
  except ConversionError as exc:
    raise SystemExit(f"Failed to convert {adoc_path}: {exc}") from exc


def run(project_root: Path, features_dir: Path, dry_run: bool = False,
    verbose: bool = False) -> int:
  """
  Walk the feature tree under `features_dir` and convert every readme.adoc.

  The return value is the number of Markdown companions that were (or would be) updated.
  """
  base_dir = (project_root / features_dir).resolve()
  if not base_dir.exists():
    raise SystemExit(f"Feature directory {base_dir} does not exist.")

  changes = 0
  processed = 0
  for adoc_path in sorted(base_dir.rglob("readme.adoc")):
    processed += 1
    changed = convert_readme(
        adoc_path, dry_run=dry_run, features_root=base_dir)
    if verbose or (dry_run and changed):
      status = "would update" if dry_run and changed else (
        "updated" if changed else "ok")
      print(f"[{status}] {adoc_path}")
    if changed:
      changes += 1

  print(
      f"Processed {processed} readme.adoc file(s); "
      f"{'would update' if dry_run else 'updated'} {changes} Markdown companion(s)."
  )
  return changes


def format_numbered_lists_in_tables(markdown: str) -> str:
  """
  Insert <br> separators between consecutive numbered steps inside table cells.

  Pydowndoc renders “1. … 2. …” on a single line inside the cell; inserting <br> improves
  readability in the Serenity report without changing the textual content.
  """
  lines = markdown.splitlines()
  formatted: list[str] = []
  for line in lines:
    stripped = line.lstrip()
    if stripped.startswith("|"):
      segments = line.split("|")
      for idx in range(1, len(segments) - 1):
        segments[idx] = _insert_number_breaks(segments[idx])
      line = "|".join(segments)
    formatted.append(line)
  return "\n".join(formatted)


def _insert_number_breaks(cell: str) -> str:
  placeholder = "__LIST_BR__"
  replaced = re.sub(r" (\d+\.)", rf"{placeholder}\1", cell)
  if placeholder not in replaced:
    return cell
  replaced = replaced.replace(placeholder, " ", 1)
  return replaced.replace(placeholder, "<br> ")


_PRIMARY_HEADING_PATTERN = re.compile(r"^(\s*##\s+)(.+)$", re.MULTILINE)
_ANCHOR_PREFIX_PATTERN = re.compile(r"^\s*(\[[^\]]+\])\s*")
_REQUIREMENT_IDENTIFIERS = ("UseCase", "UserStory", "Feature")


def normalize_requirement_heading(
    markdown: str,
    adoc_path: Path,
    features_root: Path | None = None,
) -> str:
  """
  Strip requirement identifiers like “UseCase_01_02:” from converted Markdown headings.

  Serenity derives the requirement type from the folder hierarchy; repeating the identifier inside
  the heading leads to awkward “##s” labels in the generated report. Removing the redundant prefix
  keeps the heading readable (“## Client …”) while preserving anchors.
  """
  identifier = _infer_requirement_identifier(adoc_path, features_root)
  if not identifier:
    return markdown

  def _replace(match) -> str:
    heading_text = match.group(2)
    anchor, remainder = _split_anchor_prefix(heading_text)
    normalized = _strip_identifier_prefix(remainder, identifier)
    if normalized == remainder:
      return match.group(0)
    components = [anchor, normalized] if anchor else [normalized]
    new_heading = " ".join(filter(None, components))
    return f"{match.group(1)}{new_heading}"

  return _PRIMARY_HEADING_PATTERN.sub(_replace, markdown, count=1)


def _infer_requirement_identifier(
    adoc_path: Path,
    features_root: Path | None,
) -> str | None:
  resolved_path = adoc_path.resolve()
  directories: tuple[str, ...]
  resolved_root: Path | None = None
  if features_root is not None:
    resolved_root = features_root.resolve()
    try:
      relative = resolved_path.relative_to(resolved_root)
    except ValueError:
      directories = resolved_path.parts[:-1]
    else:
      directories = relative.parts[:-1]
  else:
    directories = resolved_path.parts[:-1]

  for part in reversed(directories):
    for identifier in _REQUIREMENT_IDENTIFIERS:
      if part.lower().startswith(identifier.lower()):
        return identifier

  return None


def _strip_identifier_prefix(text: str, identifier: str) -> str:
  """
  Remove “UseCase_01_02: …” style prefixes from heading text if they match the inferred identifier.
  """
  prefix_pattern = re.compile(
      rf"^\s*{re.escape(identifier)}(?:[_\s-][^:]+)?:\s*(.+)$",
      re.IGNORECASE,
  )
  match = prefix_pattern.match(text)
  if match:
    return match.group(1).lstrip()
  fallback_pattern = re.compile(
      rf"^\s*{re.escape(identifier)}(?:[_\s-][^\s]+)?\s+(.*)$",
      re.IGNORECASE,
  )
  match = fallback_pattern.match(text)
  if match:
    return match.group(1).lstrip()
  return text


def _split_anchor_prefix(text: str) -> tuple[str, str]:
  """
  Split leading Markdown anchor expressions from a heading line.

  Pydowndoc may include “[#anchor] …” fragments ahead of the actual heading text. The Serenity
  converter should ignore these when matching requirement identifiers.
  """
  remainder = text
  anchors: list[str] = []
  while remainder:
    match = _ANCHOR_PREFIX_PATTERN.match(remainder)
    if not match:
      break
    anchors.append(match.group(1))
    remainder = remainder[match.end():]
  return (" ".join(anchors), remainder.lstrip())


def main() -> None:
  args = parse_args()
  run(
      project_root=args.project_root,
      features_dir=args.features_dir,
      dry_run=args.dry_run,
      verbose=args.verbose,
  )


if __name__ == "__main__":
  main()
