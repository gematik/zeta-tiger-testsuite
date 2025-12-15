"""Utilities for querying UseCases and linked TestAspects from YouTrack.

This module retrieves all UseCase issues from a configured YouTrack instance,
resolves their linked TestAspects, and prints the resulting structure in a
human-readable form. For each UseCase, the script lists the associated
TestAspects and their TA_IDs, followed by a compact summary block.

It exposes both a reusable Python API (`collect_usecase_testaspects`) and a
command-line entry point (`fetch-youtrack-testaspects`). The CLI reads YouTrack
connection parameters from environment variables and prints a structured report
to stdout.

Expected environment variables:
    YOUTRACK_URL   Base URL of the YouTrack instance (e.g. https://youtrack.local)
    YOUTRACK_TOKEN Permanent personal access token for authentication
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass
from typing import Any, Sequence

import requests


# === Data classes ===

@dataclass
class LinkedTestAspect:
    """Representation of a test aspect linked to a UseCase."""
    id_readable: str
    summary: str
    ta_id: str | None


@dataclass
class UseCaseResult:
    """Aggregated result for one UseCase."""
    id_readable: str
    summary: str
    testaspekte: list[LinkedTestAspect]


# === YouTrack API Help Functions  ===

def _get_headers() -> dict[str, str]:
    token = os.getenv("YOUTRACK_TOKEN")
    if not token:
        raise RuntimeError("Missing environment variable: YOUTRACK_TOKEN")
    return {"Authorization": f"Bearer {token}", "Accept": "application/json"}


def get_usecase_issues(base_url: str) -> list[dict[str, Any]]:
    """Retrieve all issues of type UseCase from YouTrack."""
    url = f"{base_url}/api/issues"
    params = {"query": "IssueType: UseCase", "fields": "idReadable,summary"}
    resp = requests.get(url, headers=_get_headers(), params=params)
    resp.raise_for_status()
    return resp.json()


def get_linked_testaspekte(base_url: str, usecase_id: str) -> list[dict[str, Any]]:
    """Fetch all test aspect issues linked to the given UseCase."""
    query = f"IssueType: TestAspekt Is required for: {usecase_id}"
    url = f"{base_url}/api/issues"
    params = {"query": query, "fields": "idReadable,summary,customFields(value)"}
    resp = requests.get(url, headers=_get_headers(), params=params)
    resp.raise_for_status()
    return resp.json()


def extract_ta_id(issue: dict[str, Any]) -> str | None:
    """Extract the TA_ID from an issues custom fields if present."""
    for field in issue.get("customFields", []):
        value = field.get("value")
        if isinstance(value, str) and value.startswith("TA_"):
            return value
    return None


# === main logic ===

def collect_usecase_testaspects(base_url: str) -> list[UseCaseResult]:
    """Collect UseCases and their linked test aspects with unique TA_IDs."""
    results: list[UseCaseResult] = []

    usecases = get_usecase_issues(base_url)
    print(f"Found use cases: {len(usecases)}\n")

    total_usecases = 0
    unique_ta_ids: set[str] = set()  # Set for unique TA_IDs

    for uc in usecases:
        uc_id = uc.get("idReadable", "")
        uc_summary = uc.get("summary", "")
        print(f"**** {uc_id} - {uc_summary}")
        total_usecases += 1

        try:
            linked_tas = get_linked_testaspekte(base_url, uc_id)
        except requests.HTTPError as e:
            print(f"   Error retrieving test aspects: {e}")
            continue

        if not linked_tas:
            print("   (No linked test aspects found)\n")
            results.append(UseCaseResult(uc_id, uc_summary, []))
            continue

        ta_entries: list[LinkedTestAspect] = []

        for ta in linked_tas:
            ta_id = extract_ta_id(ta)
            ta_summary = ta.get("summary", "")
            ta_key = ta.get("idReadable", "")

            if ta_id:
                unique_ta_ids.add(ta_id)  #-> Report: Total number of unique TA_IDs

            ta_entries.append(
                LinkedTestAspect(id_readable=ta_key, summary=ta_summary, ta_id=ta_id)
            )

            print(f"* test aspect: {ta_key} | TA_ID: {ta_id} | {ta_summary}")

        # Output TA_IDs per use case (sorted alphanumerically)
        if ta_entries:
            sorted_ta_ids = sorted(
                [ta_entry.ta_id for ta_entry in ta_entries if ta_entry.ta_id]
            )
            print("*\n* Test aspect references for this use case::")
            for ta_id in sorted_ta_ids:
                print(f"* <<{ta_id}>>")
        print()

        results.append(UseCaseResult(uc_id, uc_summary, ta_entries))

    # === Summary at the end ===
    print("=== Summary ===")
    print(f"Total number of use cases: {total_usecases}")
    print(f"Total number of test aspects: {len(unique_ta_ids)}")
    print("========================\n")

    return results



# === CLI support ===

def build_parser() -> argparse.ArgumentParser:
    """Construct the command-line interface parser."""
    parser = argparse.ArgumentParser(
        description="Fetch UseCases and linked test aspects from YouTrack."
    )
    parser.add_argument(
        "--url",
        dest="base_url",
        required=False,
        default=os.getenv("YOUTRACK_URL", ""),
        help="Base URL of the YouTrack instance (default: from YOUTRACK_URL env).",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point for the `fetch-youtrack-testaspects` CLI."""
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.base_url:
        parser.error("Missing YouTrack base URL (use --url or set YOUTRACK_URL).")

    try:
        collect_usecase_testaspects(args.base_url)
    except Exception as exc:
        print(f" Error: {exc}")
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
