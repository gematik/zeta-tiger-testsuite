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

"""Check and update independently pinned schema and OpenAPI packages."""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path, PurePosixPath
from typing import Mapping, Sequence

import requests


DEFAULT_UPSTREAM_REPO_URL = "https://github.com/gematik/zeta"
DEFAULT_UPSTREAM_SCHEMA_DIR = "src/schemas"
DEFAULT_LOCAL_SCHEMA_DIR = Path("src/test/resources/schemas/v_2_0")
DEFAULT_DIFF_OUTPUT = Path("target/schema-drift.diff")
DEFAULT_SOURCES_PATH = Path("src/test/resources/schemas/sources.json")
PROVENANCE_FILENAME = "UPSTREAM.md"


@dataclass(frozen=True)
class SchemaFileChange:
  """One schema file difference between local and upstream schemas."""

  relative_path: Path
  change_type: str


@dataclass(frozen=True)
class SchemaComparison:
  """Result of comparing the vendored schema directory with upstream."""

  local_dir: Path
  upstream_dir: Path
  changes: tuple[SchemaFileChange, ...]
  diff_text: str

  @property
  def has_drift(self) -> bool:
    """Return whether at least one schema differs."""
    return bool(self.changes)


@dataclass(frozen=True)
class UpstreamLocation:
  """Resolved upstream repository, ref and schema path."""

  repo_url: str
  ref: str
  schema_dir: str
  file_map: Mapping[str, str] | None = None


def find_repo_root(start: Path) -> Path:
  """Locate the repository root by walking upwards to ``pom.xml``."""
  for candidate in [start.resolve(), *start.resolve().parents]:
    if (candidate / "pom.xml").exists():
      return candidate
  return start.resolve()


def download_upstream_schemas(
    *,
    repo_url: str,
    ref: str,
    upstream_schema_dir: str,
    output_dir: Path,
    file_map: Mapping[str, str] | None = None,
) -> None:
  """Download the upstream repository archive and extract only the schema tree."""
  archive_url = _github_tarball_url(repo_url, ref)
  response = requests.get(archive_url, timeout=60)
  response.raise_for_status()

  output_dir.mkdir(parents=True, exist_ok=True)
  prefix_parts = PurePosixPath(upstream_schema_dir).parts
  found = False
  selected = {source: local for local, source in file_map.items()} if file_map else None
  extracted_paths: set[str] = set()

  with tarfile.open(fileobj=BytesIO(response.content), mode="r:gz") as tar:
    for member in tar:
      if not member.isfile():
        continue
      member_path = PurePosixPath(member.name)
      parts = member_path.parts
      if selected is not None:
        local = selected.get(PurePosixPath(*parts[1:]).as_posix())
        if local is None:
          continue
        relative_parts = PurePosixPath(local).parts
      else:
        if parts[1:1 + len(prefix_parts)] != prefix_parts:
          continue
        relative_parts = parts[1 + len(prefix_parts):]
      if not relative_parts:
        continue
      target_path = output_dir.joinpath(*relative_parts)
      if not target_path.resolve().is_relative_to(output_dir.resolve()):
        raise ValueError(f"Archive target outside schema directory: {member.name}")
      target_path.parent.mkdir(parents=True, exist_ok=True)
      extracted = tar.extractfile(member)
      if extracted is None:
        continue
      target_path.write_bytes(extracted.read())
      extracted_paths.add(PurePosixPath(*relative_parts).as_posix())
      found = True

  if file_map and set(file_map) != extracted_paths:
    raise FileNotFoundError(f"Missing selected upstream files: {sorted(set(file_map) - extracted_paths)}")

  if not found:
    raise FileNotFoundError(
        f"No files found below {upstream_schema_dir!r} in {repo_url}@{ref}."
    )


def compare_schema_dirs(local_dir: Path, upstream_dir: Path) -> SchemaComparison:
  """Compare local vendored schemas against the extracted upstream schemas."""
  if not local_dir.exists():
    raise FileNotFoundError(f"Local schema directory not found: {local_dir}")
  if not upstream_dir.exists():
    raise FileNotFoundError(f"Upstream schema directory not found: {upstream_dir}")

  local_files = _relative_file_map(local_dir)
  upstream_files = _relative_file_map(upstream_dir)
  # Only our package-root provenance is metadata. Additional source files still cause drift.
  local_files.pop(Path(PROVENANCE_FILENAME), None)
  upstream_files.pop(Path(PROVENANCE_FILENAME), None)
  relative_paths = sorted(set(local_files) | set(upstream_files))
  changes: list[SchemaFileChange] = []
  diff_parts: list[str] = []

  for relative_path in relative_paths:
    local_path = local_files.get(relative_path)
    upstream_path = upstream_files.get(relative_path)
    if local_path is None:
      changes.append(SchemaFileChange(relative_path, "missing-locally"))
      diff_parts.extend(_file_diff(None, upstream_path, relative_path))
      continue
    if upstream_path is None:
      changes.append(SchemaFileChange(relative_path, "local-only"))
      diff_parts.extend(_file_diff(local_path, None, relative_path))
      continue
    if local_path.read_bytes() != upstream_path.read_bytes():
      changes.append(SchemaFileChange(relative_path, "changed"))
      diff_parts.extend(_file_diff(local_path, upstream_path, relative_path))

  return SchemaComparison(
      local_dir=local_dir,
      upstream_dir=upstream_dir,
      changes=tuple(changes),
      diff_text="".join(diff_parts),
  )


def update_local_schemas(*, local_dir: Path, upstream_dir: Path) -> None:
  """Replace the local schema directory with the upstream schema directory."""
  provenance = local_dir / PROVENANCE_FILENAME
  preserved = provenance.read_bytes() if provenance.is_file() else None
  if local_dir.exists():
    shutil.rmtree(local_dir)
  shutil.copytree(upstream_dir, local_dir)
  if preserved is not None:
    provenance.write_bytes(preserved)


def write_provenance(
    *,
    output_path: Path,
    repo_url: str,
    ref: str,
    upstream_schema_dir: str,
    local_schema_dir: Path,
) -> None:
  """Write a small provenance note for the vendored schema copy."""
  generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
  notes = ""
  if output_path.exists():
    previous = output_path.read_text(encoding="utf-8")
    section = previous.find("\n## ")
    if section >= 0:
      notes = previous[section:]
  output_path.parent.mkdir(parents=True, exist_ok=True)
  output_path.write_text(
      "\n".join([
          "# Schema Package Provenance",
          "",
          f"The files in `{local_schema_dir.name}` are vendored from the upstream repository below.",
          "They are intentionally kept local so scenario execution remains reproducible.",
          "",
          f"- Upstream repository: {repo_url}",
          f"- Upstream ref: `{ref}`",
          f"- Upstream path: `{upstream_schema_dir}`",
          f"- Local path: `{local_schema_dir.as_posix()}`",
          f"- Last updated: `{generated_at}`",
          "",
          "This package-root UPSTREAM.md is local metadata, excluded from byte comparison and preserved during updates.",
          "Local profiles and mock schemas are kept outside the vendored directory and are not part of the upstream comparison.",
          "",
      ]) + notes,
      encoding="utf-8",
  )


def check_main(argv: Sequence[str] | None = None) -> int:
  """CLI entry point for checking schema drift."""
  args = _build_parser("check").parse_args(argv)
  if args.all_packages:
    return check_all_packages(args)
  upstream = _resolve_upstream_location(args)
  project_root = find_repo_root(args.project_root)
  local_dir = _resolve_path(project_root, args.local_dir)
  diff_output = _resolve_path(project_root, args.diff_output)

  with tempfile.TemporaryDirectory() as temp_dir_name:
    upstream_dir = Path(temp_dir_name) / "schemas"
    download_upstream_schemas(
        repo_url=upstream.repo_url,
        ref=upstream.ref,
        upstream_schema_dir=upstream.schema_dir,
        output_dir=upstream_dir,
        file_map=upstream.file_map,
    )
    comparison = compare_schema_dirs(local_dir, upstream_dir)

  diff_output.parent.mkdir(parents=True, exist_ok=True)
  diff_output.write_text(comparison.diff_text, encoding="utf-8")

  if comparison.has_drift:
    print(f"ZETA schemas differ from {upstream.repo_url}@{upstream.ref}.")
    print(f"Diff written to {diff_output.relative_to(project_root)}.")
    for change in comparison.changes:
      print(f"{change.change_type}: {change.relative_path.as_posix()}")
    return 1

  print(f"ZETA schemas match {upstream.repo_url}@{upstream.ref}.")
  return 0


def check_all_packages(args: argparse.Namespace) -> int:
  """Check every independently pinned package, including selective OpenAPI imports."""
  project_root = find_repo_root(args.project_root)
  manifest = _resolve_path(project_root, DEFAULT_SOURCES_PATH)
  packages = json.loads(manifest.read_text(encoding="utf-8"))
  diff_output = _resolve_path(project_root, args.diff_output)
  diffs: list[str] = []
  failed = False
  for name, source in packages.items():
    local_dir = manifest.parent / name
    if not local_dir.resolve().is_relative_to(manifest.parent.resolve()):
      raise ValueError(f"Package outside schema directory: {name}")
    with tempfile.TemporaryDirectory() as temp_dir_name:
      upstream_dir = Path(temp_dir_name) / "schemas"
      download_upstream_schemas(
          repo_url=source["repository"], ref=source["ref"],
          upstream_schema_dir=source.get("upstream_dir", ""), output_dir=upstream_dir,
          file_map=source.get("files"),
      )
      comparison = compare_schema_dirs(local_dir, upstream_dir)
    failed |= comparison.has_drift
    print(f"{name}: {'DRIFT' if comparison.has_drift else 'matches'} {source['repository']}@{source['ref']}")
    if comparison.has_drift:
      diffs.append(f"Package: {name}\n{comparison.diff_text}")
  diff_output.parent.mkdir(parents=True, exist_ok=True)
  diff_output.write_text("\n".join(diffs), encoding="utf-8")
  return 1 if failed else 0


def update_main(argv: Sequence[str] | None = None) -> int:
  """CLI entry point for updating vendored schemas."""
  args = _build_parser("update").parse_args(argv)
  upstream = _resolve_upstream_location(args)
  project_root = find_repo_root(args.project_root)
  local_dir = _resolve_path(project_root, args.local_dir)
  provenance_path = (
      _resolve_path(project_root, args.provenance_path)
      if args.provenance_path is not None else local_dir / PROVENANCE_FILENAME
  )

  with tempfile.TemporaryDirectory() as temp_dir_name:
    upstream_dir = Path(temp_dir_name) / "schemas"
    download_upstream_schemas(
        repo_url=upstream.repo_url,
        ref=upstream.ref,
        upstream_schema_dir=upstream.schema_dir,
        output_dir=upstream_dir,
        file_map=upstream.file_map,
    )
    comparison = compare_schema_dirs(local_dir, upstream_dir)
    update_local_schemas(local_dir=local_dir, upstream_dir=upstream_dir)

  write_provenance(
      output_path=provenance_path,
      repo_url=upstream.repo_url,
      ref=upstream.ref,
      upstream_schema_dir=upstream.schema_dir or "selected files listed in ../sources.json",
      local_schema_dir=args.local_dir,
  )
  manifest = project_root / DEFAULT_SOURCES_PATH
  if manifest.is_file() and local_dir.is_relative_to(manifest.parent):
    packages = json.loads(manifest.read_text(encoding="utf-8"))
    name = local_dir.relative_to(manifest.parent).as_posix()
    if name in packages:
      packages[name]["repository"] = upstream.repo_url
      packages[name]["ref"] = upstream.ref
      if upstream.file_map is None:
        packages[name]["upstream_dir"] = upstream.schema_dir
        packages[name].pop("files", None)
      manifest.write_text(json.dumps(packages, indent=2) + "\n", encoding="utf-8")

  if comparison.has_drift:
    print(f"Updated {local_dir.relative_to(project_root)} from {upstream.repo_url}@{upstream.ref}.")
    for change in comparison.changes:
      print(f"{change.change_type}: {change.relative_path.as_posix()}")
  else:
    print(f"ZETA schemas already matched {upstream.repo_url}@{upstream.ref}; provenance refreshed.")
  return 0


def _build_parser(command: str) -> argparse.ArgumentParser:
  """Build a parser for the check or update command."""
  parser = argparse.ArgumentParser()
  parser.add_argument(
      "--project-root",
      type=Path,
      default=Path.cwd(),
      help="Repository root, defaults to the current working directory.",
  )
  parser.add_argument(
      "--repo-url",
      default=os.environ.get("ZETA_SCHEMA_REPO_URL"),
      help=(
          "Upstream GitHub repository or tree URL, defaults to "
          f"{DEFAULT_UPSTREAM_REPO_URL}."
      ),
  )
  parser.add_argument(
      "--ref",
      default=os.environ.get("ZETA_SCHEMA_REF"),
      help="Upstream branch, tag or commit; defaults to the selected package's pin.",
  )
  parser.add_argument(
      "--upstream-dir",
      default=None,
      help=f"Upstream schema directory, defaults to {DEFAULT_UPSTREAM_SCHEMA_DIR}.",
  )
  parser.add_argument(
      "--local-dir",
      type=Path,
      default=DEFAULT_LOCAL_SCHEMA_DIR,
      help=f"Local schema directory, defaults to {DEFAULT_LOCAL_SCHEMA_DIR}.",
  )
  if command == "check":
    parser.add_argument(
        "--all", dest="all_packages", action="store_true",
        help="Check all packages against the independent pins in schemas/sources.json.",
    )
    parser.add_argument(
        "--diff-output",
        type=Path,
        default=DEFAULT_DIFF_OUTPUT,
        help=f"Path for the generated unified diff, defaults to {DEFAULT_DIFF_OUTPUT}.",
    )
  else:
    parser.add_argument(
        "--provenance-path",
        type=Path,
        default=None,
        help="Path for provenance; defaults to UPSTREAM.md inside --local-dir.",
    )
  return parser


def _resolve_upstream_location(args: argparse.Namespace) -> UpstreamLocation:
  """Resolve CLI arguments, including optional GitHub tree URLs."""
  project_root = find_repo_root(args.project_root)
  manifest = project_root / DEFAULT_SOURCES_PATH
  local_dir = _resolve_path(project_root, args.local_dir)
  source = {}
  if manifest.is_file() and local_dir.is_relative_to(manifest.parent):
    source = json.loads(manifest.read_text(encoding="utf-8")).get(
        local_dir.relative_to(manifest.parent).as_posix(), {}
    )
  repo_url = args.repo_url or source.get("repository", DEFAULT_UPSTREAM_REPO_URL)
  ref = args.ref or source.get("ref")
  schema_dir = args.upstream_dir if args.upstream_dir is not None else source.get(
      "upstream_dir", "" if source.get("files") else DEFAULT_UPSTREAM_SCHEMA_DIR
  )
  file_map = source.get("files") if args.upstream_dir is None else None
  parsed = urllib.parse.urlparse(repo_url)
  tree_match = re.fullmatch(r"/([^/]+)/([^/]+)/tree/([^/]+)/(.+)", parsed.path)
  if tree_match is None:
    if not ref:
      raise ValueError(
          f"No upstream ref configured for {local_dir}. "
          f"Add its pin to {manifest}, pass --ref, set ZETA_SCHEMA_REF, "
          "or use a GitHub tree URL."
      )
    return UpstreamLocation(repo_url, ref, schema_dir, file_map)

  owner, repo, tree_ref, tree_path = tree_match.groups()
  repo_url = urllib.parse.urlunparse(parsed._replace(path=f"/{owner}/{repo}", params="", query="", fragment=""))
  ref = args.ref or tree_ref
  schema_dir = args.upstream_dir if args.upstream_dir is not None else tree_path
  return UpstreamLocation(repo_url, ref, schema_dir)


def _github_tarball_url(repo_url: str, ref: str) -> str:
  """Convert a GitHub repository URL and ref into a codeload tarball URL."""
  parsed = urllib.parse.urlparse(repo_url)
  if parsed.netloc != "github.com":
    raise ValueError(f"Only github.com repository URLs are supported: {repo_url}")
  match = re.fullmatch(r"/([^/]+)/([^/]+?)(?:\.git)?/?", parsed.path)
  if not match:
    raise ValueError(f"Could not parse GitHub owner and repository from: {repo_url}")
  owner, repo = match.groups()
  quoted_ref = urllib.parse.quote(ref, safe="")
  return f"https://codeload.github.com/{owner}/{repo}/tar.gz/{quoted_ref}"


def _relative_file_map(root: Path) -> dict[Path, Path]:
  """Return all regular files below root keyed by relative path."""
  return {
      path.relative_to(root): path
      for path in root.rglob("*")
      if path.is_file()
  }


def _file_diff(local_path: Path | None, upstream_path: Path | None, relative_path: Path) -> list[str]:
  """Build a unified diff for one changed schema file."""
  local_lines = _read_diff_lines(local_path)
  upstream_lines = _read_diff_lines(upstream_path)
  local_name = f"local/{relative_path.as_posix()}" if local_path else "/dev/null"
  upstream_name = f"upstream/{relative_path.as_posix()}" if upstream_path else "/dev/null"
  return [
      f"{line}\n"
      for line in difflib.unified_diff(
          local_lines,
          upstream_lines,
          fromfile=local_name,
          tofile=upstream_name,
          lineterm="",
      )
  ]


def _read_diff_lines(path: Path | None) -> list[str]:
  """Read a text file for diff output, preserving line endings."""
  if path is None:
    return []
  return path.read_text(encoding="utf-8", errors="replace").splitlines()


def _resolve_path(project_root: Path, path: Path) -> Path:
  """Resolve a possibly relative path against the project root."""
  if path.is_absolute():
    return path
  return project_root / path


if __name__ == "__main__":
  sys.exit(check_main())
