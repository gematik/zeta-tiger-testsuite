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

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path
from dataclasses import asdict

from .builder import build_traceability


def main(argv: list[str] | None = None) -> int:
  parser = argparse.ArgumentParser(
    prog="traceability",
    description="Generate traceability artefacts for the ZETA testsuite documentation.",
  )
  parser.add_argument(
    "command",
    nargs="?",
    default="build",
    choices=["build"],
    help="Command to execute (default: build).",
  )
  parser.add_argument(
    "--project-root",
    type=Path,
    help="Path to the project root (defaults to autodetected repository root).",
  )
  parser.add_argument(
    "--product-status-csv",
    type=Path,
    help="Optional CSV with product implementation status (defaults to docs/asciidoc/tables/product_implementation.csv).",
  )
  parser.add_argument(
    "--dry-run",
    action="store_true",
    help="Collect data but do not write artefacts to disk.",
  )
  parser.add_argument(
    "--json",
    action="store_true",
    help="Print the collected data as JSON to stdout.",
  )
  parser.add_argument(
    "--log-level",
    default="INFO",
    choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    help="Set the logging verbosity (default: INFO).",
  )

  args = parser.parse_args(argv)

  logging.basicConfig(
    level=getattr(logging, args.log_level.upper(), logging.INFO),
    format="[%(levelname)s] %(message)s",
  )

  if args.command != "build":
    parser.error(f"Unsupported command {args.command!r}")

  report = build_traceability(
    project_root=args.project_root,
    product_status_csv=args.product_status_csv,
    write_outputs=not args.dry_run,
  )

  if args.json:
    json.dump(asdict(report), sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")

  return 0


if __name__ == "__main__":
  raise SystemExit(main())
