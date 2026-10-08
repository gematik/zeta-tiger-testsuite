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

import tempfile
import unittest
from pathlib import Path

from testsuite_docs.zeta_schemas import (
    DEFAULT_UPSTREAM_REF,
    DEFAULT_UPSTREAM_SCHEMA_DIR,
    compare_schema_dirs,
    update_local_schemas,
    write_provenance,
    _build_parser,
    _resolve_upstream_location,
)


class ZetaSchemasTest(unittest.TestCase):
  """Unit tests for vendored ZETA schema maintenance helpers."""

  def test_compare_schema_dirs_reports_changed_missing_and_local_only_files(self) -> None:
    """Diff detection classifies changed, missing and local-only schemas."""
    with tempfile.TemporaryDirectory() as tmp_dir_name:
      tmp_dir = Path(tmp_dir_name)
      local_dir = tmp_dir / "local"
      upstream_dir = tmp_dir / "upstream"
      local_dir.mkdir()
      upstream_dir.mkdir()
      (local_dir / "changed.yaml").write_text("title: local\n", encoding="utf-8")
      (upstream_dir / "changed.yaml").write_text("title: upstream\n", encoding="utf-8")
      (local_dir / "local-only.yaml").write_text("title: local\n", encoding="utf-8")
      (upstream_dir / "missing-locally.yaml").write_text("title: upstream\n", encoding="utf-8")

      result = compare_schema_dirs(local_dir, upstream_dir)

      changes = {change.relative_path.as_posix(): change.change_type for change in result.changes}
      self.assertEqual(changes["changed.yaml"], "changed")
      self.assertEqual(changes["local-only.yaml"], "local-only")
      self.assertEqual(changes["missing-locally.yaml"], "missing-locally")
      self.assertIn("upstream/changed.yaml", result.diff_text)

  def test_update_local_schemas_replaces_local_copy(self) -> None:
    """Updating replaces stale local schema files with upstream files."""
    with tempfile.TemporaryDirectory() as tmp_dir_name:
      tmp_dir = Path(tmp_dir_name)
      local_dir = tmp_dir / "local"
      upstream_dir = tmp_dir / "upstream"
      local_dir.mkdir()
      upstream_dir.mkdir()
      (local_dir / "old.yaml").write_text("title: old\n", encoding="utf-8")
      (upstream_dir / "new.yaml").write_text("title: new\n", encoding="utf-8")

      update_local_schemas(local_dir=local_dir, upstream_dir=upstream_dir)

      self.assertFalse((local_dir / "old.yaml").exists())
      self.assertEqual((local_dir / "new.yaml").read_text(encoding="utf-8"), "title: new\n")

  def test_write_provenance_records_ref_and_paths(self) -> None:
    """The provenance file records the selected upstream source."""
    with tempfile.TemporaryDirectory() as tmp_dir_name:
      output_path = Path(tmp_dir_name) / "UPSTREAM.md"

      write_provenance(
          output_path=output_path,
          repo_url="https://github.com/gematik/zeta",
          ref="v1.3.1-2",
          upstream_schema_dir="src/schemas",
          local_schema_dir=Path("src/test/resources/schemas/v_1_0"),
      )

      text = output_path.read_text(encoding="utf-8")
      self.assertIn("https://github.com/gematik/zeta", text)
      self.assertIn("`v1.3.1-2`", text)
      self.assertIn("`src/schemas`", text)

  def test_resolves_github_tree_url(self) -> None:
    """A GitHub tree URL can provide the repository, ref and schema path."""
    parser = _build_parser("check")
    args = parser.parse_args([
        "--repo-url",
        "https://github.com/gematik/zeta/tree/v1.3.1-2/src/schemas",
    ])

    location = _resolve_upstream_location(args)

    self.assertEqual(location.repo_url, "https://github.com/gematik/zeta")
    self.assertEqual(location.ref, "v1.3.1-2")
    self.assertEqual(location.schema_dir, "src/schemas")

  def test_explicit_ref_and_upstream_dir_override_github_tree_url(self) -> None:
    """Explicit CLI arguments win over values embedded in a GitHub tree URL."""
    parser = _build_parser("check")
    args = parser.parse_args([
        "--repo-url",
        "https://github.com/gematik/zeta/tree/v1.3.1-2/src/schemas",
        "--ref",
        "main",
        "--upstream-dir",
        "other/schemas",
    ])

    location = _resolve_upstream_location(args)

    self.assertEqual(location.ref, "main")
    self.assertEqual(location.schema_dir, "other/schemas")
    self.assertNotEqual(location.ref, DEFAULT_UPSTREAM_REF)
    self.assertNotEqual(location.schema_dir, DEFAULT_UPSTREAM_SCHEMA_DIR)


if __name__ == "__main__":
  unittest.main()
