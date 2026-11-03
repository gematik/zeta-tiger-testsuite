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

"""Regression checks for indexes generated from maintained documents."""

from pathlib import Path

from testsuite_docs.generate_doc_includes import generate_includes


def test_refresh_preserves_sources_and_removes_stale_includes(tmp_path: Path) -> None:
  """Discover new files, drop deleted files, and preserve maintained sources."""
  afos = tmp_path / "afos/gemSpec_Test"
  aspects = tmp_path / "testaspekte/gemSpec_Test/A_1"
  afos.mkdir(parents=True)
  aspects.mkdir(parents=True)
  sources = {
    tmp_path / "afos/readme.adoc": "Source versions remain maintained.\n",
    afos / "A_2.adoc": "[#A_2]\n==== Requirement 2\n",
    afos / "A_1.adoc": "[#A_1]\n==== Requirement 1\n",
    aspects / "TA_A_1_02.adoc": "[#TA_A_1_02]\n===== Aspect 2\n",
    aspects / "TA_A_1_01.adoc": "[#TA_A_1_01]\n===== Aspect 1\n",
  }
  for path, content in sources.items():
    path.write_text(content, encoding="utf-8")

  generate_includes(tmp_path)
  first = {path: path.read_bytes() for path in tmp_path.rglob("readme.adoc")}
  generate_includes(tmp_path)
  assert first == {path: path.read_bytes() for path in first}
  for path, content in sources.items():
    assert path.read_text(encoding="utf-8") == content
  assert (afos / "readme.adoc").read_text() == "include::A_1.adoc[]\n\ninclude::A_2.adoc[]\n\n"
  assert "Folgende Testaspekte testen <<A_1>>." in (aspects / "readme.adoc").read_text()

  (afos / "A_1.adoc").unlink()
  (afos / "A_3.adoc").write_text("New requirement\n")
  (aspects / "TA_A_1_01.adoc").unlink()
  generate_includes(tmp_path)
  assert (afos / "readme.adoc").read_text() == "include::A_2.adoc[]\n\ninclude::A_3.adoc[]\n\n"
  assert "TA_A_1_01" not in (aspects / "readme.adoc").read_text()
  assert "include::A_1/readme.adoc[]" in (aspects.parent / "readme.adoc").read_text()
  assert "include::gemSpec_Test/readme.adoc[]" in (tmp_path / "testaspekte/readme.adoc").read_text()

  (aspects / "TA_A_1_02.adoc").unlink()
  generate_includes(tmp_path)
  assert "include::" not in (tmp_path / "testaspekte/readme.adoc").read_text()
