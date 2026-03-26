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

"""Shared helpers for generating Asciidoc table markup."""

from __future__ import annotations

from typing import List, Optional, Sequence


def table_block_attributes(*, cols_directive: str, autowidth: bool) -> str:
  """Build the normalized Asciidoc table attribute line."""
  if autowidth:
    return f'[%header,cols="{cols_directive}",options="autowidth"]'
  return f'[%header,width="100%",cols="{cols_directive}"]'


def escape_asciidoc_table_cell(value: object) -> str:
  """Escape the subset of characters that would break table rendering."""
  text = str(value)
  text = text.replace("\r", " ").replace("\n", " ")
  return text.replace("|", r"\|")


def render_asciidoc_table_body(headers: Sequence[str],
                               rows: Sequence[Sequence[object]]) -> str:
  """Render the ``|===``...``|===`` body with escaped cell content."""
  content_rows = [list(row) for row in rows] if rows else [["-"] * len(headers)]
  lines: List[str] = ["|==="]
  for header in headers:
    lines.append(f"^|{escape_asciidoc_table_cell(header)}")
  lines.append("")
  for row in content_rows:
    for cell in row:
      lines.append(f"|{escape_asciidoc_table_cell(cell)}")
    lines.append("")
  lines.append("|===")
  return "\n".join(lines)


def write_asciidoc_table(
    headers: Sequence[str],
    rows: Sequence[Sequence[object]],
    *,
    disclaimer: Optional[str] = None,
    cols_directive: str,
    autowidth: bool = False,
    block_attributes: Optional[Sequence[str]] = None,
) -> str:
  """Return a complete Asciidoc table block."""
  prefix_lines: List[str] = []
  if disclaimer:
    prefix_lines.append(disclaimer)
  if block_attributes:
    prefix_lines.extend(block_attributes)
  prefix_lines.append(
      table_block_attributes(cols_directive=cols_directive, autowidth=autowidth))

  body = render_asciidoc_table_body(headers, rows)
  content = "\n".join(prefix_lines + [body])
  if not content.endswith("\n"):
    content += "\n"
  return content
