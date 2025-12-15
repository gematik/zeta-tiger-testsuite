from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Set


@dataclass(frozen=True, slots=True)
class Requirement:
  requirement_id: str
  title: str
  source: Path


@dataclass(frozen=True, slots=True)
class TestAspect:
  test_aspect_id: str
  title: str
  requirement_id: str
  source: Path


@dataclass(slots=True)
class UseCase:
  tag_id: str
  anchor_id: str
  title: str
  user_story_id: str
  feature_files: List[Path] = field(default_factory=list)


@dataclass(slots=True)
class ScenarioCoverage:
  scenario_name: str
  feature: Path
  use_cases: Set[str]
  test_aspects: Set[str]
  requirements: Set[str]
  product_implemented: bool = False


@dataclass(slots=True)
class TraceabilityRecord:
  requirement_id: str
  test_aspect_id: str
  use_case_id: Optional[str]
  implemented: bool
  product_implemented: bool
  last_run: Optional[datetime]
  last_run_status: Optional[str]
  last_success: Optional[datetime]
  scenario_names: Set[str] = field(default_factory=set)


@dataclass(slots=True)
class TraceabilityLink:
  """Serialisable representation of a requirement/test-aspect/use-case link."""

  requirement: str
  test_aspect: str
  use_case: Optional[str]
  implemented: bool
  product_implemented: bool
  last_run: Optional[str]
  last_run_human: Optional[str]
  last_run_status: Optional[str]
  last_success: Optional[str]
  last_success_human: Optional[str]
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
