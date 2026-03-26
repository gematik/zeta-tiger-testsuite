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

"""Datamodels shared by traceability builder and CLI outputs."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set


@dataclass(frozen=True, slots=True)
class Requirement:
  """Requirement catalogue entry parsed from Asciidoc sources."""

  requirement_id: str
  title: str
  source: Path


@dataclass(frozen=True, slots=True)
class TestAspect:
  """Test aspect metadata and owning requirement reference."""

  test_aspect_id: str
  title: str
  requirement_id: str
  source: Path


@dataclass(slots=True)
class UseCase:
  """Use-case metadata with links to associated feature files."""

  tag_id: str
  anchor_id: str
  title: str
  user_story_id: str
  feature_files: List[Path] = field(default_factory=list)


@dataclass(slots=True)
class ScenarioCoverage:
  """Coverage facts extracted from one feature scenario."""

  scenario_name: str
  feature: Path
  use_cases: Set[str]
  test_aspects: Set[str]
  requirements: Set[str]
  product_implemented: bool = False


@dataclass(slots=True)
class TraceabilityRecord:
  """Internal aggregation row before serialisation to output links."""

  requirement_id: str
  test_aspect_id: str
  use_case_id: Optional[str]
  implemented: bool
  product_implemented: bool
  scenario_names: Set[str] = field(default_factory=set)


@dataclass(slots=True)
class TraceabilityLink:
  """Serialisable representation of a requirement/test-aspect/use-case link."""

  requirement: str
  test_aspect: str
  use_case: Optional[str]
  implemented: bool
  product_implemented: bool
  scenarios: List[str]


@dataclass(slots=True)
class TraceabilityReport:
  """High-level summary returned by the traceability generator."""

  generated_at: str
  requirements: Dict[str, Dict[str, object]]
  test_aspects: Dict[str, Dict[str, object]]
  use_cases: Dict[str, Dict[str, object]]
  traceability: List[TraceabilityLink]
  coverage_summary: Dict[str, Dict[str, int]]
