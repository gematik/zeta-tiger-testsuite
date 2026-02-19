#!/usr/bin/env python3
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

"""
Sync GitLab AFO/Testaspekt issues using the GitLab REST API.

The script creates missing AFO/TA issues from the local Asciidoc sources,
links AFOs and Testaspekte, closes TA issues tagged in feature files, and
optionally syncs MR links. It defaults to dry-run mode and only applies
changes when --apply is provided.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from testsuite_docs.traceability.builder import collect_testaspect_tag_locations


ID_RE = re.compile(r"(?i)\b(TA_[A-Z0-9_-]+|A_\d+(?:-\d+)?|GS-A_\d+(?:-\d+)?|TIP1-A_\d+(?:-\d+)?)\b")


class GitLabClient:
    def __init__(
        self,
        base_url: str,
        project_path: str,
        token: str,
        cache_get: bool = True,
        request_timeout: float = 30.0,
        use_graphql: bool = False,
        max_retries: int = 3,
        retry_backoff: float = 1.0,
    ):
        self.base_url = base_url.rstrip("/")
        self.project_path = project_path
        self.token = token
        self.cache_get = cache_get
        self.request_timeout = request_timeout
        self.use_graphql = use_graphql
        self.max_retries = max_retries
        self.retry_backoff = retry_backoff
        self._cache: dict[str, object] = {}
        self._project_id: int | None = None
        self._project_info: dict | None = None

    def api_url(self, path: str, params: dict | None = None) -> str:
        """Build a GitLab REST API URL with optional query parameters."""
        url = f"{self.base_url}/api/v4/{path.lstrip('/')}"
        if params:
            url += "?" + urllib.parse.urlencode(params)
        return url

    def request(self, method: str, url: str, data=None):
        """Execute a REST request and parse JSON responses."""
        headers = {"User-Agent": "GitLab-Issue-Sync"}
        if self.token.startswith("job:"):
            headers["JOB-TOKEN"] = self.token[4:]
        else:
            headers["PRIVATE-TOKEN"] = self.token
        body = None
        if data is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(data).encode("utf-8")
        req = urllib.request.Request(url, method=method, headers=headers, data=body)
        retryable = {429, 500, 502, 503, 504}
        for attempt in range(self.max_retries + 1):
            try:
                with urllib.request.urlopen(req, timeout=self.request_timeout) as resp:
                    payload = resp.read().decode("utf-8")
                    return json.loads(payload) if payload else None
            except urllib.error.HTTPError as exc:
                if exc.code in retryable and attempt < self.max_retries:
                    retry_after = exc.headers.get("Retry-After")
                    delay = self.retry_backoff * (2**attempt)
                    if retry_after:
                        try:
                            delay = max(delay, float(retry_after))
                        except ValueError:
                            pass
                    time.sleep(delay)
                    continue
                raise
            except urllib.error.URLError:
                if attempt < self.max_retries:
                    time.sleep(self.retry_backoff * (2**attempt))
                    continue
                raise

    def get(self, path: str, params: dict | None = None):
        """GET an endpoint with caching for repeated calls."""
        url = self.api_url(path, params)
        if self.cache_get and url in self._cache:
            return self._cache[url]
        data = self.request("GET", url)
        if self.cache_get:
            self._cache[url] = data
        return data

    def paginate(self, path: str, params: dict | None = None):
        """Iterate through paginated GitLab REST responses."""
        page = 1
        per_page = 100
        items: list = []
        while True:
            page_params = {"per_page": per_page, "page": page}
            if params:
                page_params.update(params)
            data = self.get(path, page_params)
            if not data:
                break
            items.extend(data)
            if len(data) < per_page:
                break
            page += 1
        return items

    def graphql(self, query: str, variables: dict | None = None):
        """POST a GraphQL query via the REST helper."""
        url = f"{self.base_url}/api/graphql"
        data = {"query": query, "variables": variables or {}}
        return self.request("POST", url, data)

    def project_info(self) -> dict:
        """Resolve and cache GitLab project metadata."""
        if self._project_info is None:
            encoded = urllib.parse.quote(self.project_path, safe="")
            self._project_info = self.get(f"projects/{encoded}")
        return self._project_info

    def project_id(self) -> int:
        """Resolve and cache the numeric GitLab project id."""
        if self._project_id is None:
            self._project_id = int(self.project_info()["id"])
        return self._project_id

    def list_issues(self, state: str = "all"):
        if self.use_graphql:
            try:
                return self.list_issues_graphql(state)
            except Exception as exc:  # pragma: no cover - network fallback
                print(f"GraphQL issue listing failed, falling back to REST: {exc}", file=sys.stderr)
        return self.paginate(f"projects/{self.project_id()}/issues", {"state": state})

    def list_mrs(self, state: str = "opened"):
        if self.use_graphql:
            try:
                return self.list_mrs_graphql(state)
            except Exception as exc:  # pragma: no cover - network fallback
                print(f"GraphQL MR listing failed, falling back to REST: {exc}", file=sys.stderr)
        return self.paginate(f"projects/{self.project_id()}/merge_requests", {"state": state})

    def issue_notes(self, iid: int):
        return self.paginate(f"projects/{self.project_id()}/issues/{iid}/notes", {"sort": "asc"})

    def mr_changes(self, iid: int):
        return self.get(f"projects/{self.project_id()}/merge_requests/{iid}/changes")

    def fetch_file(self, path: str, ref: str) -> str:
        file_path = urllib.parse.quote(path, safe="")
        url = self.api_url(
            f"projects/{self.project_id()}/repository/files/{file_path}",
            {"ref": ref},
        )
        data = self.request("GET", url)
        if not data:
            return ""
        return base64.b64decode(data.get("content", "")).decode("utf-8")

    def list_issues_graphql(self, state: str = "all"):
        items: list[dict] = []
        cursor = None
        state_value = state.lower()
        use_state = state_value != "all"
        query_with_state = """
        query($fullPath: ID!, $after: String, $state: IssuableState!) {
          project(fullPath: $fullPath) {
            issues(first: 100, after: $after, state: $state) {
              pageInfo { endCursor hasNextPage }
              nodes { iid title webUrl state }
            }
          }
        }
        """
        query_all = """
        query($fullPath: ID!, $after: String) {
          project(fullPath: $fullPath) {
            issues(first: 100, after: $after) {
              pageInfo { endCursor hasNextPage }
              nodes { iid title webUrl state }
            }
          }
        }
        """
        state_map = {
            "opened": "opened",
            "open": "opened",
            "closed": "closed",
            "locked": "locked",
        }
        graphql_state = state_map.get(state_value, state_value)
        while True:
            if use_state:
                variables = {"fullPath": self.project_path, "after": cursor, "state": graphql_state}
                payload = self.graphql(query_with_state, variables)
            else:
                variables = {"fullPath": self.project_path, "after": cursor}
                payload = self.graphql(query_all, variables)
            if payload.get("errors"):
                raise RuntimeError(payload["errors"])
            data = payload.get("data", {}).get("project", {}).get("issues", {})
            nodes = data.get("nodes") or []
            for node in nodes:
                items.append(
                    {
                        "iid": int(node["iid"]),
                        "title": node.get("title", ""),
                        "web_url": node.get("webUrl", ""),
                        "state": node.get("state", "").lower(),
                        "issue_type": None,
                    }
                )
            page_info = data.get("pageInfo") or {}
            if not page_info.get("hasNextPage"):
                break
            cursor = page_info.get("endCursor")
        return items

    def list_mrs_graphql(self, state: str = "opened"):
        items: list[dict] = []
        cursor = None
        state_value = state.lower()
        use_state = state_value != "all"
        query_with_state = """
        query($fullPath: ID!, $after: String, $state: MergeRequestState!) {
          project(fullPath: $fullPath) {
            mergeRequests(first: 100, after: $after, state: $state) {
              pageInfo { endCursor hasNextPage }
              nodes { iid title webUrl sourceBranch }
            }
          }
        }
        """
        query_all = """
        query($fullPath: ID!, $after: String) {
          project(fullPath: $fullPath) {
            mergeRequests(first: 100, after: $after) {
              pageInfo { endCursor hasNextPage }
              nodes { iid title webUrl sourceBranch }
            }
          }
        }
        """
        mr_state_map = {
            "opened": "opened",
            "open": "opened",
            "closed": "closed",
            "merged": "merged",
            "locked": "locked",
        }
        graphql_state = mr_state_map.get(state_value, state_value)
        while True:
            if use_state:
                variables = {"fullPath": self.project_path, "after": cursor, "state": graphql_state}
                payload = self.graphql(query_with_state, variables)
            else:
                variables = {"fullPath": self.project_path, "after": cursor}
                payload = self.graphql(query_all, variables)
            if payload.get("errors"):
                raise RuntimeError(payload["errors"])
            data = payload.get("data", {}).get("project", {}).get("mergeRequests", {})
            nodes = data.get("nodes") or []
            for node in nodes:
                items.append(
                    {
                        "iid": int(node["iid"]),
                        "title": node.get("title", ""),
                        "web_url": node.get("webUrl", ""),
                        "source_branch": node.get("sourceBranch", ""),
                    }
                )
            page_info = data.get("pageInfo") or {}
            if not page_info.get("hasNextPage"):
                break
            cursor = page_info.get("endCursor")
        return items

def read_token(token_file: str | None) -> str:
    """Load a GitLab token from a file or environment variable."""
    if token_file:
        token_path = Path(token_file)
        if token_path.exists():
            token = token_path.read_text(encoding="utf-8").strip()
            if token:
                return token
    token = os.environ.get("GITLAB_TOKEN", "").strip()
    if not token:
        job_token = os.environ.get("CI_JOB_TOKEN", "").strip()
        if job_token:
            return f"job:{job_token}"
        raise SystemExit("Missing token. Set GITLAB_TOKEN, CI_JOB_TOKEN, or pass --token-file.")
    return token


def parse_anchor(text: str) -> str:
    """Extract the Asciidoc anchor from a document, if present."""
    match = re.search(r"\[#([A-Za-z0-9_-]+)\]", text)
    return match.group(1) if match else ""


def first_heading(text: str) -> str:
    """Return the first Asciidoc heading line without the leading '='."""
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("="):
            match = re.match(r"^=+\s+(.*)$", line)
            if match:
                return match.group(1).strip()
    return ""


def parse_afo_docs(repo_root: Path):
    """Collect AFO ids, titles, and paths from the Asciidoc sources."""
    out = {}
    for path in (repo_root / "docs" / "asciidoc" / "afos").rglob("*.adoc"):
        if path.name.startswith("readme"):
            continue
        text = path.read_text(encoding="utf-8")
        afo_id = parse_anchor(text) or path.stem
        out[afo_id] = {"path": path, "title": first_heading(text)}
    return out


def parse_ta_docs(repo_root: Path):
    """Collect TA ids, titles, and paths from the Asciidoc sources."""
    out = {}
    for path in (repo_root / "docs" / "asciidoc" / "testaspekte").rglob("TA_*.adoc"):
        text = path.read_text(encoding="utf-8")
        ta_id = parse_anchor(text) or path.stem
        out[ta_id] = {"path": path, "title": first_heading(text), "parent": path.parent.name}
    return out


def parent_afo_from_ta(ta_id: str) -> str:
    """Derive the parent AFO id from a TA id."""
    parent = ta_id[3:] if ta_id.upper().startswith("TA_") else ta_id
    parent = re.sub(r"_[0-9]{2,}$", "", parent)
    return parent


def build_blob_url(base_url: str, project_path: str, path: Path, ref: str = "main", repo_root: Path | None = None) -> str:
    """Construct a GitLab blob URL for a file."""
    if not isinstance(path, Path):
        path = Path(path)
    rel_path = path
    if repo_root is not None:
        try:
            rel_path = path.relative_to(repo_root)
        except ValueError:
            rel_path = path
    rel = rel_path.as_posix().lstrip("./")
    return f"{base_url}/{project_path}/-/blob/{ref}/{urllib.parse.quote(rel, safe='/')}"





def issue_desc_afo(afo_id: str, title: str, url: str) -> str:
    """Format the description for an AFO issue."""
    return f"AFO-ID: {afo_id}\n\nTitel: {title}\n\nQuelle: {url}"


def issue_desc_ta(ta_id: str, title: str, ta_url: str, parent: str, afo_issue: str, afo_file: str) -> str:
    """Format the description for a Testaspekt issue."""
    return (
        f"Testaspekt-ID: {ta_id}\n\n"
        f"Titel: {title}\n\n"
        f"Quelle: {ta_url}\n\n"
        f"\u00dcbergeordnete Anforderung: {parent}\n"
        f"AFO-Issue: {afo_issue}\n"
        f"AFO-Datei: {afo_file}"
    )


def update_description_title(desc: str, desired_title: str) -> str:
    """Replace or insert the Titel line in an issue description."""
    if desc is None:
        return desc
    if "Titel:" in desc:
        return re.sub(r"^Titel:.*$", f"Titel: {desired_title}", desc, flags=re.M)
    match = re.search(r"^Testaspekt-ID:.*$", desc, flags=re.M)
    if match:
        insert = match.group(0) + "\n\n" + f"Titel: {desired_title}"
        return desc[: match.start()] + insert + desc[match.end() :]
    return desc


def main():
    """CLI entry point for GitLab issue synchronization."""
    parser = argparse.ArgumentParser(description="Batch GitLab issue/TA sync.")
    parser.add_argument("--base-url", default="https://gitlab.com")
    parser.add_argument("--project-path", default="achelos-zeta/testsuite")
    parser.add_argument("--token-file")
    parser.add_argument("--apply", action="store_true", help="Apply changes (default: dry-run).")
    parser.add_argument("--include-mr-ta", action="store_true", help="Create TA issues from open MR diffs.")
    parser.add_argument("--mr-state", default="opened")
    parser.add_argument("--issue-state", default="all")
    parser.add_argument(
        "--skip-mr-comments",
        action="store_true",
        help="Skip adding MR link comments to issues.",
    )
    parser.add_argument(
        "--process-mrs",
        action="store_true",
        help="Process merge requests (disabled by default).",
    )
    parser.add_argument(
        "--skip-mrs",
        action="store_true",
        help="Skip all MR processing (deprecated; use --process-mrs to enable).",
    )
    parser.add_argument("--use-graphql", action="store_true", help="Use GraphQL for listing issues/MRs.")
    parser.add_argument("--request-timeout", type=float, default=30.0, help="HTTP timeout in seconds.")
    parser.add_argument("--max-retries", type=int, default=3, help="Max retries for transient HTTP errors.")
    parser.add_argument("--retry-backoff", type=float, default=1.0, help="Base delay in seconds for retries.")
    parser.add_argument("--ref", help="Git ref/branch for blob links (default: project default branch).")
    parser.add_argument("--sleep", type=float, default=0.05)
    args = parser.parse_args()

    repo_root = Path.cwd()
    token = read_token(args.token_file)
    gl = GitLabClient(
        args.base_url,
        args.project_path,
        token,
        request_timeout=args.request_timeout,
        use_graphql=args.use_graphql,
        max_retries=args.max_retries,
        retry_backoff=args.retry_backoff,
    )
    ref = args.ref or gl.project_info().get("default_branch") or "main"

    issues = gl.list_issues(state=args.issue_state)
    ta_issues = {}
    afo_issues = {}
    for issue in issues:
        issue_type = issue.get("issue_type")
        if issue_type and issue_type != "issue":
            continue
        title = issue.get("title", "")
        if title.startswith("TA_"):
            ta_id = title.split(" - ", 1)[0].strip()
            ta_issues[ta_id] = issue
        else:
            match = re.match(r"(?i)^(A_\d+(?:-\d+)?|GS-A_\d+(?:-\d+)?|TIP1-A_\d+(?:-\d+)?)(\s|-|$)", title)
            if match:
                afo_id = title.split(" - ", 1)[0].strip()
                afo_issues[afo_id] = issue

    afo_docs = parse_afo_docs(repo_root)
    ta_docs = parse_ta_docs(repo_root)

    created_afo = 0
    created_ta = 0
    created_links = 0
    updated_titles = 0
    scenario_closed = 0
    scenario_commented = 0
    scenario_reopened = 0
    reopened_afo = 0
    closed_afo = 0
    updated_mrs = 0
    mr_comments = 0

    for afo_id, info in sorted(afo_docs.items()):
        if afo_id in afo_issues:
            continue
        title = info["title"] or afo_id
        issue_title = f"{afo_id} - {title}" if title else afo_id
        url = build_blob_url(args.base_url, args.project_path, info["path"], ref=ref, repo_root=repo_root)
        desc = issue_desc_afo(afo_id, title or afo_id, url)
        if args.apply:
            issue = gl.request(
                "POST",
                gl.api_url(f"projects/{gl.project_id()}/issues"),
                {"title": issue_title, "description": desc, "labels": "Anforderung", "issue_type": "issue"},
            )
            afo_issues[afo_id] = issue
            time.sleep(args.sleep)
        created_afo += 1

    for ta_id, info in sorted(ta_docs.items()):
        if ta_id in ta_issues:
            continue
        title = info["title"] or ta_id
        issue_title = f"{ta_id} - {title}" if title else ta_id
        ta_url = build_blob_url(args.base_url, args.project_path, info["path"], ref=ref, repo_root=repo_root)
        parent = parent_afo_from_ta(ta_id)
        parent_issue = afo_issues.get(parent)
        if not parent_issue and f"{parent}-01" in afo_issues:
            parent = f"{parent}-01"
            parent_issue = afo_issues.get(parent)
        afo_issue_url = parent_issue["web_url"] if parent_issue else "(nicht gefunden)"
        afo_path = afo_docs.get(parent, {}).get("path")
        if not afo_path and parent.endswith("-01"):
            afo_path = afo_docs.get(parent[:-3], {}).get("path")
        afo_file_url = (
            build_blob_url(args.base_url, args.project_path, afo_path, ref=ref, repo_root=repo_root) if afo_path else "(nicht gefunden)"
        )
        desc = issue_desc_ta(ta_id, title or ta_id, ta_url, parent, afo_issue_url, afo_file_url)
        if args.apply:
            issue = gl.request(
                "POST",
                gl.api_url(f"projects/{gl.project_id()}/issues"),
                {"title": issue_title, "description": desc, "labels": "Testaspekt", "issue_type": "issue"},
            )
            ta_issues[ta_id] = issue
            if parent_issue:
                params = {
                    "target_project_id": gl.project_id(),
                    "target_issue_iid": parent_issue["iid"],
                    "link_type": "relates_to",
                }
                gl.request("POST", gl.api_url(f"projects/{gl.project_id()}/issues/{issue['iid']}/links", params))
                created_links += 1
            time.sleep(args.sleep)
        created_ta += 1

    for ta_id, issue in ta_issues.items():
        title = issue.get("title", "")
        if not title.startswith("TA_"):
            continue
        if title == ta_id or title == f"{ta_id} - {ta_id}":
            desired = ta_docs.get(ta_id, {}).get("title")
            if not desired:
                continue
            new_desc = update_description_title(issue.get("description", ""), desired)
            new_title = f"{ta_id} - {desired}"
            if args.apply:
                gl.request(
                    "PUT",
                    gl.api_url(f"projects/{gl.project_id()}/issues/{issue['iid']}"),
                    {"title": new_title, "description": new_desc},
                )
                time.sleep(args.sleep)
            updated_titles += 1

    tags = collect_testaspect_tag_locations(
        repo_root / "src" / "test" / "resources" / "features",
        include_feature_tags=False,
    )
    tagged_ids = set(tags.keys())
    for ta_id, locations in sorted(tags.items()):
        issue = ta_issues.get(ta_id)
        if not issue:
            continue
        iid = issue["iid"]
        urls = [
            f"{build_blob_url(args.base_url, args.project_path, path, ref=ref, repo_root=repo_root)}#L{line}"
            for path, line in locations
        ]
        note_body = "Feature:\n" + "\n".join(urls)
        notes = gl.issue_notes(iid)
        if not any(any(url in (n.get("body") or "") for url in urls) for n in notes):
            if args.apply:
                gl.request("POST", gl.api_url(f"projects/{gl.project_id()}/issues/{iid}/notes"), {"body": note_body})
                time.sleep(args.sleep)
            scenario_commented += 1
        if issue.get("state") != "closed":
            if args.apply:
                gl.request("PUT", gl.api_url(f"projects/{gl.project_id()}/issues/{iid}"), {"state_event": "close"})
                time.sleep(args.sleep)
                issue["state"] = "closed"
            scenario_closed += 1
    for ta_id, issue in sorted(ta_issues.items()):
        if ta_id in tagged_ids:
            continue
        if issue.get("state") == "closed":
            if args.apply:
                gl.request("PUT", gl.api_url(f"projects/{gl.project_id()}/issues/{issue['iid']}"), {"state_event": "reopen"})
                time.sleep(args.sleep)
                issue["state"] = "opened"
            scenario_reopened += 1

    ta_parent = {ta_id: info["parent"] for ta_id, info in ta_docs.items()}
    ta_state = {ta_id: issue.get("state") for ta_id, issue in ta_issues.items()}
    afo_counts = {}
    for ta_id, state in ta_state.items():
        parent = ta_parent.get(ta_id)
        if not parent:
            continue
        if parent not in afo_issues and f"{parent}-01" in afo_issues:
            parent = f"{parent}-01"
        parent_issue = afo_issues.get(parent)
        if not parent_issue:
            continue
        info = afo_counts.setdefault(parent_issue["iid"], {"total": 0, "open": 0, "closed": 0})
        info["total"] += 1
        if state == "closed":
            info["closed"] += 1
        else:
            info["open"] += 1

    for iid, info in afo_counts.items():
        current = None
        for issue in afo_issues.values():
            if issue.get("iid") == iid:
                current = issue
                break
        if not current:
            continue
        if info["open"] > 0 and current.get("state") == "closed":
            if args.apply:
                gl.request("PUT", gl.api_url(f"projects/{gl.project_id()}/issues/{iid}"), {"state_event": "reopen"})
                time.sleep(args.sleep)
            reopened_afo += 1
        if info["open"] == 0 and current.get("state") == "opened":
            if args.apply:
                gl.request("PUT", gl.api_url(f"projects/{gl.project_id()}/issues/{iid}"), {"state_event": "close"})
                time.sleep(args.sleep)
            closed_afo += 1

    issue_notes_cache = {}
    skip_mrs = not args.process_mrs or args.skip_mrs
    if not skip_mrs:
        mrs = gl.list_mrs(state=args.mr_state)
        for mr in mrs:
            detail = gl.get(f"projects/{gl.project_id()}/merge_requests/{mr['iid']}")
            if not detail:
                continue
            text = " ".join([detail.get("title", ""), detail.get("description", "") or "", detail.get("source_branch", "")])
            ids = set(m.group(1).upper() for m in ID_RE.finditer(text))
            missing_relates = []
            matched = []
            for id_ in sorted(ids):
                issue = ta_issues.get(id_) or afo_issues.get(id_)
                if not issue:
                    continue
                matched.append(issue)
                if f"#{issue['iid']}" not in (detail.get("description", "") or ""):
                    missing_relates.append(issue["iid"])
            if missing_relates:
                lines = [f"Relates to #{iid}" for iid in sorted(set(missing_relates))]
                new_desc = (detail.get("description", "") or "").rstrip() + "\n\n" + "\n".join(lines)
                if args.apply:
                    gl.request(
                        "PUT",
                        gl.api_url(f"projects/{gl.project_id()}/merge_requests/{detail['iid']}"),
                        {"description": new_desc},
                    )
                    time.sleep(args.sleep)
                updated_mrs += 1
            if not args.skip_mr_comments:
                for issue in matched:
                    iid = issue["iid"]
                    if iid not in issue_notes_cache:
                        issue_notes_cache[iid] = [note.get("body", "") for note in gl.issue_notes(iid)]
                    if not any(detail["web_url"] in body for body in issue_notes_cache[iid]):
                        if args.apply:
                            gl.request(
                                "POST",
                                gl.api_url(f"projects/{gl.project_id()}/issues/{iid}/notes"),
                                {"body": f"MR: {detail['web_url']}"},
                            )
                            time.sleep(args.sleep)
                        mr_comments += 1

    if args.include_mr_ta and not skip_mrs:
        for mr in mrs:
            changes = gl.mr_changes(mr["iid"])
            if not changes:
                continue
            sha = changes.get("diff_refs", {}).get("head_sha")
            for change in changes.get("changes", []):
                if not change.get("new_file"):
                    continue
                path = change.get("new_path")
                if not path or "/TA_" not in path:
                    continue
                content = gl.fetch_file(path, sha)
                ta_id = parse_anchor(content) or Path(path).stem
                if ta_id in ta_issues:
                    continue
                title = first_heading(content) or ta_id
                ta_url = f"{args.base_url}/{args.project_path}/-/blob/{sha}/{urllib.parse.quote(path, safe='/')}"
                parent = parent_afo_from_ta(ta_id)
                parent_issue = afo_issues.get(parent)
                if not parent_issue and f"{parent}-01" in afo_issues:
                    parent = f"{parent}-01"
                    parent_issue = afo_issues.get(parent)
                afo_issue_url = parent_issue["web_url"] if parent_issue else "(nicht gefunden)"
                afo_path = afo_docs.get(parent, {}).get("path")
                if not afo_path and parent.endswith("-01"):
                    afo_path = afo_docs.get(parent[:-3], {}).get("path")
                afo_file_url = (
                    build_blob_url(args.base_url, args.project_path, afo_path, ref=ref, repo_root=repo_root)
                    if afo_path
                    else "(nicht gefunden)"
                )
                desc = issue_desc_ta(ta_id, title, ta_url, parent, afo_issue_url, afo_file_url)
                if args.apply:
                    issue = gl.request(
                        "POST",
                        gl.api_url(f"projects/{gl.project_id()}/issues"),
                        {"title": f"{ta_id} - {title}", "description": desc, "labels": "Testaspekt", "issue_type": "issue"},
                    )
                    ta_issues[ta_id] = issue
                    if parent_issue:
                        params = {
                            "target_project_id": gl.project_id(),
                            "target_issue_iid": parent_issue["iid"],
                            "link_type": "relates_to",
                        }
                        gl.request("POST", gl.api_url(f"projects/{gl.project_id()}/issues/{issue['iid']}/links", params))
                    time.sleep(args.sleep)
                created_ta += 1

    print(
        json.dumps(
            {
                "created_afo": created_afo,
                "created_ta": created_ta,
                "created_links": created_links,
                "updated_titles": updated_titles,
                "scenario_commented": scenario_commented,
                "scenario_closed": scenario_closed,
                "scenario_reopened": scenario_reopened,
                "reopened_afo": reopened_afo,
                "closed_afo": closed_afo,
                "updated_mrs": updated_mrs,
                "mr_comments": mr_comments,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
