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

"""Unit tests for ZETA schema maintenance commands."""

from __future__ import annotations

import io
import json
import re
import tarfile
from pathlib import Path

import pytest
import yaml

from testsuite_docs import zeta_schemas

from testsuite_docs.zeta_schemas import (
  DEFAULT_UPSTREAM_SCHEMA_DIR,
  compare_schema_dirs,
  update_local_schemas,
  write_provenance,
  _build_parser,
  _resolve_upstream_location,
)


def test_compare_schema_dirs_reports_changed_missing_and_local_only_files(tmp_path: Path) -> None:
  """Diff detection classifies changed, missing and local-only schemas."""
  local_dir = tmp_path / "local"
  upstream_dir = tmp_path / "upstream"
  local_dir.mkdir()
  upstream_dir.mkdir()
  (local_dir / "changed.yaml").write_text("title: local\n", encoding="utf-8")
  (upstream_dir / "changed.yaml").write_text("title: upstream\n", encoding="utf-8")
  (local_dir / "local-only.yaml").write_text("title: local\n", encoding="utf-8")
  (upstream_dir / "missing-locally.yaml").write_text("title: upstream\n", encoding="utf-8")

  result = compare_schema_dirs(local_dir, upstream_dir)

  changes = {change.relative_path.as_posix(): change.change_type for change in result.changes}
  assert changes["changed.yaml"] == "changed"
  assert changes["local-only.yaml"] == "local-only"
  assert changes["missing-locally.yaml"] == "missing-locally"
  assert "upstream/changed.yaml" in result.diff_text


def test_update_local_schemas_replaces_local_copy(tmp_path: Path) -> None:
  """Updating replaces stale local schema files with upstream files."""
  local_dir = tmp_path / "local"
  upstream_dir = tmp_path / "upstream"
  local_dir.mkdir()
  upstream_dir.mkdir()
  (local_dir / "old.yaml").write_text("title: old\n", encoding="utf-8")
  (upstream_dir / "new.yaml").write_text("title: new\n", encoding="utf-8")

  update_local_schemas(local_dir=local_dir, upstream_dir=upstream_dir)

  assert not ((local_dir / "old.yaml").exists())
  assert (local_dir / "new.yaml").read_text(encoding="utf-8") == "title: new\n"


def test_write_provenance_records_ref_and_paths(tmp_path: Path) -> None:
  """The provenance file records the selected upstream source."""
  output_path = tmp_path / "UPSTREAM.md"

  write_provenance(
    output_path=output_path,
    repo_url="https://github.com/gematik/zeta",
    ref="test-pin",
    upstream_schema_dir="src/schemas",
    local_schema_dir=Path("src/test/resources/schemas/package_a"),
  )

  text = output_path.read_text(encoding="utf-8")
  assert "https://github.com/gematik/zeta" in text
  assert "`test-pin`" in text
  assert "`src/schemas`" in text


def test_resolves_github_tree_url(tmp_path: Path) -> None:
  """A GitHub tree URL can provide the repository, ref and schema path."""
  parser = _build_parser("check")
  args = parser.parse_args(
    [
      "--project-root", str(tmp_path),
      "--repo-url",
      "https://github.com/gematik/zeta/tree/test-pin/src/schemas",
    ]
  )

  location = _resolve_upstream_location(args)

  assert location.repo_url == "https://github.com/gematik/zeta"
  assert location.ref == "test-pin"
  assert location.schema_dir == "src/schemas"


def test_explicit_ref_and_upstream_dir_override_github_tree_url() -> None:
  """Explicit CLI arguments win over values embedded in a GitHub tree URL."""
  parser = _build_parser("check")
  args = parser.parse_args(
    [
      "--repo-url",
      "https://github.com/gematik/zeta/tree/test-pin/src/schemas",
      "--ref",
      "main",
      "--upstream-dir",
      "other/schemas",
    ]
  )

  location = _resolve_upstream_location(args)

  assert location.ref == "main"
  assert location.schema_dir == "other/schemas"
  assert location.schema_dir != DEFAULT_UPSTREAM_SCHEMA_DIR


def test_refresh_preserves_local_profile_and_historical_provenance(tmp_path: Path) -> None:
  """Updating a pinned bundle must retain historical and normative profile notes."""
  output_path = tmp_path / "UPSTREAM.md"
  notes = "\n## Baseline\nPrevious: old-pin\n\n## Local profile\nNormative override\n"
  output_path.write_text("# Old provenance\n" + notes, encoding="utf-8")
  write_provenance(output_path=output_path, repo_url="https://github.com/gematik/zeta",
                   ref="new-commit", upstream_schema_dir="src/schemas",
                   local_schema_dir=Path("src/test/resources/schemas/v_2_0"))
  updated = output_path.read_text(encoding="utf-8")
  assert "`new-commit`" in updated
  assert updated.endswith(notes)


def test_only_package_root_provenance_is_excluded_from_drift(tmp_path: Path) -> None:
  local, upstream = tmp_path / "local", tmp_path / "upstream"
  local.mkdir()
  upstream.mkdir()
  (local / "UPSTREAM.md").write_text("local provenance", encoding="utf-8")
  assert not compare_schema_dirs(local, upstream).has_drift
  (local / "unexpected.yaml").write_text("type: object", encoding="utf-8")
  assert compare_schema_dirs(local, upstream).has_drift
  (local / "unexpected.yaml").unlink()
  (local / "nested").mkdir()
  (local / "nested/UPSTREAM.md").write_text("not package metadata", encoding="utf-8")
  assert compare_schema_dirs(local, upstream).has_drift


def test_update_preserves_package_provenance(tmp_path: Path) -> None:
  local, upstream = tmp_path / "local", tmp_path / "upstream"
  local.mkdir()
  upstream.mkdir()
  metadata = b"# Source\n\n## Notes\nPreserve this package's history.\n"
  (local / "UPSTREAM.md").write_bytes(metadata)
  (local / "obsolete.yaml").write_text("old", encoding="utf-8")
  (upstream / "current.yaml").write_text("new", encoding="utf-8")
  update_local_schemas(local_dir=local, upstream_dir=upstream)
  assert (local / "UPSTREAM.md").read_bytes() == metadata
  assert not (local / "obsolete.yaml").exists()
  assert not compare_schema_dirs(local, upstream).has_drift


@pytest.mark.parametrize("package", ["package_a", "package_b"])
def test_selected_package_supplies_its_own_pin(tmp_path: Path, package: str) -> None:
  (tmp_path / "pom.xml").write_text("<project/>", encoding="utf-8")
  manifest = tmp_path / zeta_schemas.DEFAULT_SOURCES_PATH
  manifest.parent.mkdir(parents=True)
  manifest.write_text(json.dumps({package: {
      "repository": "https://github.com/gematik/zeta", "ref": "package-pin",
      "upstream_dir": "src/schemas"}}), encoding="utf-8")
  args = _build_parser("check").parse_args([
      "--project-root", str(tmp_path), "--local-dir", f"src/test/resources/schemas/{package}"])
  assert _resolve_upstream_location(args).ref == "package-pin"
  args.ref = "explicit-pin"
  assert _resolve_upstream_location(args).ref == "explicit-pin"


@pytest.mark.parametrize("command", [zeta_schemas.check_main, zeta_schemas.update_main])
@pytest.mark.parametrize("sources", [None, {}, {"v_2_0": {"repository": "https://github.com/gematik/zeta"}}])
def test_missing_pin_fails_before_download(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, command, sources,
) -> None:
  monkeypatch.delenv("ZETA_SCHEMA_REF", raising=False)
  monkeypatch.delenv("ZETA_SCHEMA_REPO_URL", raising=False)
  manifest = tmp_path / zeta_schemas.DEFAULT_SOURCES_PATH
  if sources is not None:
    manifest.parent.mkdir(parents=True)
    manifest.write_text(json.dumps(sources), encoding="utf-8")

  def unexpected_download(**kwargs):
    pytest.fail("Missing pin must fail before fetching or replacing schemas")

  monkeypatch.setattr(zeta_schemas, "download_upstream_schemas", unexpected_download)
  with pytest.raises(ValueError, match="No upstream ref configured"):
    command(["--project-root", str(tmp_path)])


@pytest.mark.parametrize("via_environment", [False, True])
def test_explicit_ref_works_without_manifest(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, via_environment: bool,
) -> None:
  monkeypatch.delenv("ZETA_SCHEMA_REF", raising=False)
  arguments = ["--project-root", str(tmp_path)]
  if via_environment:
    monkeypatch.setenv("ZETA_SCHEMA_REF", "explicit-pin")
  else:
    arguments.extend(["--ref", "explicit-pin"])
  args = _build_parser("check").parse_args(arguments)
  assert _resolve_upstream_location(args).ref == "explicit-pin"


def test_update_writes_provenance_to_selected_package_and_updates_inventory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
  (tmp_path / "pom.xml").write_text("<project/>", encoding="utf-8")
  manifest = tmp_path / zeta_schemas.DEFAULT_SOURCES_PATH
  local = manifest.parent / "package_a"
  local.mkdir(parents=True)
  (local / "old.yaml").write_text("old", encoding="utf-8")
  (local / "UPSTREAM.md").write_text("# Old\n\n## Usage\nKeep notes.\n", encoding="utf-8")
  manifest.write_text(json.dumps({"package_a": {
      "repository": "https://github.com/gematik/zeta", "ref": "old-pin",
      "upstream_dir": "src/schemas"}}), encoding="utf-8")

  def download(**kwargs):
    kwargs["output_dir"].mkdir()
    (kwargs["output_dir"] / "new.yaml").write_text("type: string", encoding="utf-8")

  monkeypatch.setattr(zeta_schemas, "download_upstream_schemas", download)
  assert zeta_schemas.update_main([
      "--project-root", str(tmp_path), "--local-dir", "src/test/resources/schemas/package_a",
      "--ref", "new-pin"]) == 0
  assert "new-pin" in (local / "UPSTREAM.md").read_text(encoding="utf-8")
  assert "Keep notes." in (local / "UPSTREAM.md").read_text(encoding="utf-8")
  assert not (manifest.parent / "UPSTREAM.md").exists()
  assert json.loads(manifest.read_text(encoding="utf-8"))["package_a"]["ref"] == "new-pin"


def test_selective_archive_import_preserves_bytes_and_relative_layout(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
  buffer = io.BytesIO()
  files = {"repo/src/openapi/api.yaml": b"$ref: ../schemas/response.yaml\r\n",
           "repo/src/schemas/response.yaml": b"type: object\n", "repo/LICENSE": b"license\n",
           "repo/unrelated.yaml": b"ignore"}
  with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
    for name, content in files.items():
      entry = tarfile.TarInfo(name)
      entry.size = len(content)
      archive.addfile(entry, io.BytesIO(content))

  class Response:
    content = buffer.getvalue()

    def raise_for_status(self):
      pass

  monkeypatch.setattr(zeta_schemas.requests, "get", lambda *args, **kwargs: Response())
  mapping = {"openapi/api.yaml": "src/openapi/api.yaml",
             "schemas/response.yaml": "src/schemas/response.yaml", "LICENSE": "LICENSE"}
  zeta_schemas.download_upstream_schemas(
      repo_url="https://github.com/gematik/zeta", ref="pin", upstream_schema_dir="",
      output_dir=tmp_path / "result", file_map=mapping)
  for local, source in mapping.items():
    assert (tmp_path / "result" / local).read_bytes() == files["repo/" + source]
  assert not (tmp_path / "result/unrelated.yaml").exists()
  with pytest.raises(FileNotFoundError, match="Missing selected"):
    zeta_schemas.download_upstream_schemas(
        repo_url="https://github.com/gematik/zeta", ref="pin", upstream_schema_dir="",
        output_dir=tmp_path / "missing", file_map={**mapping, "missing.yaml": "src/missing.yaml"})


def test_all_packages_use_independent_pins_and_fail_on_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
  (tmp_path / "pom.xml").write_text("<project/>", encoding="utf-8")
  manifest = tmp_path / zeta_schemas.DEFAULT_SOURCES_PATH
  manifest.parent.mkdir(parents=True)
  sources = {name: {"repository": "https://github.com/gematik/zeta", "ref": name + "-pin",
                    "upstream_dir": "src/schemas"} for name in ("package_a", "package_b")}
  manifest.write_text(json.dumps(sources), encoding="utf-8")
  for name in sources:
    local = manifest.parent / name
    local.mkdir()
    (local / "schema.yaml").write_text("type: string\n", encoding="utf-8")
    (local / "UPSTREAM.md").write_text("provenance", encoding="utf-8")
  calls = []

  def download(**kwargs):
    calls.append(kwargs["ref"])
    kwargs["output_dir"].mkdir()
    (kwargs["output_dir"] / "schema.yaml").write_text("type: string\n", encoding="utf-8")

  monkeypatch.setattr(zeta_schemas, "download_upstream_schemas", download)
  args = ["--project-root", str(tmp_path), "--all"]
  assert zeta_schemas.check_main(args) == 0
  assert calls == ["package_a-pin", "package_b-pin"]
  (manifest.parent / "package_b/schema.yaml").write_text("type: integer\n", encoding="utf-8")
  assert zeta_schemas.check_main(args) == 1
  assert "Package: package_b" in (tmp_path / "target/schema-drift.diff").read_text(encoding="utf-8")


def test_runtime_schema_packages_resolve_all_local_references_and_fragments() -> None:
  """Cover both moved OpenAPI trees and JSON-Schema fragment entry points offline."""
  from urllib.parse import unquote

  resources = Path(__file__).resolve().parents[3] / "src/test/resources"
  schemas = resources / "schemas"
  assert not (resources / "openapi").exists()
  assert not (resources / "push_notifications_openapi").exists()
  assert not (schemas / "UPSTREAM.md").exists()
  assert not (schemas / "openapi").exists()
  sources = json.loads((schemas / "sources.json").read_text(encoding="utf-8"))
  assert "openapi" not in sources
  assert {path.name for path in schemas.iterdir() if path.is_dir()} == set(sources) | {"mock"}
  assert {p.name for p in (schemas / "v_2_0").iterdir()} == {"openapi", "schemas", "UPSTREAM.md"}
  for name, source in sources.items():
    provenance = (schemas / name / "UPSTREAM.md").read_text(encoding="utf-8")
    assert source["ref"] in provenance
    if "files" in source:
      actual = {p.relative_to(schemas / name).as_posix()
                for p in (schemas / name).rglob("*")
                if p.is_file() and p != schemas / name / "UPSTREAM.md"}
      assert actual == set(source["files"])

  def visit(value, source):
    if isinstance(value, dict):
      for key, child in value.items():
        if key == "$ref":
          path, _, fragment = child.partition("#")
          assert "://" not in path, (source, child)
          target = ((resources / path.removeprefix("classpath:")) if path.startswith("classpath:")
                    else source.parent / path) if path else source
          assert target.resolve().is_relative_to(resources.resolve()), (source, child)
          node = yaml.safe_load(target.read_text(encoding="utf-8"))
          if fragment:
            assert fragment.startswith("/"), (source, child)
            for token in unquote(fragment)[1:].split("/"):
              token = token.replace("~1", "/").replace("~0", "~")
              node = node[int(token)] if isinstance(node, list) else node[token]
          assert isinstance(node, (dict, bool)), (source, child)
        else:
          visit(child, source)
    elif isinstance(value, list):
      for child in value:
        visit(child, source)

  for path in schemas.rglob("*.yaml"):
    visit(yaml.safe_load(path.read_text(encoding="utf-8")), path)

  # Feature arguments and inline Tiger schemas must use the relocated resources too.
  feature_refs = set()
  for path in (resources / "features").rglob("*.feature"):
    feature_refs.update(re.findall(
        r'"((?:classpath:)?schemas/[^"\s$]+\.yaml(?:#[^"\s]*)?)"',
        path.read_text(encoding="utf-8")))
    assert "classpath:push_notifications_openapi/" not in path.read_text(encoding="utf-8")
  assert feature_refs
  for ref in feature_refs:
    visit({"$ref": "classpath:" + ref.removeprefix("classpath:")}, resources / "feature")


def test_default_check_and_update_keep_both_v2_trees_on_one_pin(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
  """A normal update must retain both trees and the shared inventory used by CI."""
  manifest = tmp_path / zeta_schemas.DEFAULT_SOURCES_PATH
  local = manifest.parent / "v_2_0"
  local.mkdir(parents=True)
  mapping = {"openapi/api.yaml": "src/openapi/api.yaml",
             "schemas/response.yaml": "src/schemas/response.yaml"}
  manifest.write_text(json.dumps({"v_2_0": {
      "repository": "https://github.com/gematik/zeta", "ref": "old-pin", "files": mapping}}),
      encoding="utf-8")
  calls = []

  def download(**kwargs):
    calls.append(kwargs["ref"])
    assert kwargs["file_map"] == mapping
    for name in mapping:
      target = kwargs["output_dir"] / name
      target.parent.mkdir(parents=True, exist_ok=True)
      target.write_text("type: string\n", encoding="utf-8")

  monkeypatch.setattr(zeta_schemas, "download_upstream_schemas", download)
  monkeypatch.delenv("ZETA_SCHEMA_REF", raising=False)
  monkeypatch.delenv("ZETA_SCHEMA_REPO_URL", raising=False)
  assert zeta_schemas.update_main(["--project-root", str(tmp_path), "--ref", "new-pin"]) == 0
  assert json.loads(manifest.read_text(encoding="utf-8"))["v_2_0"] == {
      "repository": "https://github.com/gematik/zeta", "ref": "new-pin", "files": mapping}
  assert "new-pin" in (local / "UPSTREAM.md").read_text(encoding="utf-8")
  assert zeta_schemas.check_main(["--project-root", str(tmp_path)]) == 0
  assert zeta_schemas.check_main(["--project-root", str(tmp_path), "--all"]) == 0
  assert calls == ["new-pin"] * 3
