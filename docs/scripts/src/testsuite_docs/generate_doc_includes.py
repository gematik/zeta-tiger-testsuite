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

"""Generate include indexes from existing AFO and TestAspect documents."""

from pathlib import Path

from testsuite_docs.update_testaspects import (
    DOCS_DIR,
    create_base_readme,
    create_readmes,
    create_spec_readmes,
)


def create_afo_readmes(base_folder: Path) -> None:
  """Index existing requirements without consulting or changing a catalog."""
  for folder in sorted(base_folder.iterdir()):
    if folder.is_dir():
      includes = [f"include::{path.name}[]\n\n"
                  for path in sorted(folder.glob("*.adoc"), key=lambda p: p.stem)
                  if path.name != "readme.adoc"]
      (folder / "readme.adoc").write_text(
          "".join(includes), encoding="utf-8", newline="\n")


def generate_includes(asciidoc_root: Path) -> None:
  """Refresh indexes while preserving requirement and TestAspect sources."""
  create_afo_readmes(asciidoc_root / "afos")
  testaspekte = asciidoc_root / "testaspekte"
  create_readmes(testaspekte)
  create_spec_readmes(testaspekte)
  create_base_readme(testaspekte)


def main() -> int:
  """Entry point for the documentation build and standalone uv command."""
  generate_includes(DOCS_DIR / "asciidoc")
  return 0
