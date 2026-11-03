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

"""Regression tests for GitLab API timeout handling."""

import io
import json
import urllib.error
import urllib.parse
from unittest.mock import MagicMock, Mock, call

import pytest

from testsuite_docs import gitlab_issue_sync


@pytest.fixture
def transport(monkeypatch):
    """Replace HTTP requests and retry delays with controllable mocks."""
    urlopen = Mock()
    sleep = Mock()
    monkeypatch.setattr(gitlab_issue_sync.urllib.request, "urlopen", urlopen)
    monkeypatch.setattr(gitlab_issue_sync.time, "sleep", sleep)
    return urlopen, sleep


@pytest.fixture
def client():
    """Return a client with a known project and retry configuration."""
    client = gitlab_issue_sync.GitLabClient(
        "https://gitlab.example.test", "group/testsuite", "test-token",
        request_timeout=12.5, retry_backoff=0.25,
    )
    client._project_id = 123
    return client


def json_response(payload):
    """Encode a payload as a context-managed HTTP response body."""
    return io.BytesIO(json.dumps(payload).encode("utf-8"))


@pytest.mark.parametrize("stage", ["open", "read"])
def test_issue_notes_recovers_from_socket_timeouts(client, transport, stage):
    """Retry socket timeouts before headers or while reading the body."""
    urlopen, sleep = transport
    error = TimeoutError("The read operation timed out")
    failed_response = MagicMock()
    failed_response.__enter__.return_value.read.side_effect = error
    failure = error if stage == "open" else failed_response
    notes = [{"body": "Feature:\nhttps://gitlab.example.test/feature"}]
    urlopen.side_effect = [failure, failure, json_response(notes)]

    assert client.issue_notes(456) == notes

    assert urlopen.call_count == 3
    assert sleep.call_args_list == [call(0.25), call(0.5)]
    for args, kwargs in urlopen.call_args_list:
        assert args[0].get_method() == "GET"
        assert "/projects/123/issues/456/notes?" in args[0].full_url
        assert kwargs == {"timeout": 12.5}
    if stage == "read":
        assert failed_response.__exit__.call_count == 2


@pytest.mark.parametrize("max_retries", [0, 3])
def test_timeout_exhaustion_raises_without_caching_partial_results(client, transport, max_retries):
    """Persistent timeouts fail after the configured number of attempts."""
    urlopen, sleep = transport
    client.max_retries = max_retries
    error = TimeoutError("The read operation timed out")
    urlopen.side_effect = error

    with pytest.raises(TimeoutError) as caught:
        client.issue_notes(456)

    assert caught.value is error
    assert urlopen.call_count == max_retries + 1
    assert sleep.call_args_list == [call(0.25 * 2**attempt) for attempt in range(max_retries)]
    assert client._cache == {}


@pytest.mark.parametrize("method", ["POST", "PUT"])
def test_write_timeout_is_not_replayed(client, transport, method):
    """A response timeout must not replay a possibly completed mutation."""
    urlopen, sleep = transport
    error = TimeoutError("The read operation timed out")
    response = MagicMock()
    response.__enter__.return_value.read.side_effect = error
    urlopen.return_value = response

    with pytest.raises(TimeoutError) as caught:
        client.request(method, client.api_url("projects/123/issues"), {"title": "Test"})

    assert caught.value is error
    assert urlopen.call_count == 1
    response.__exit__.assert_called_once()
    sleep.assert_not_called()


def test_paginated_notes_retry_keeps_all_pages(client, transport):
    """Retry the failed page without losing or duplicating earlier notes."""
    urlopen, sleep = transport
    first_page = [{"id": index, "body": "Note"} for index in range(100)]
    last_page = [{"id": 100, "body": "Feature: existing reference"}]
    urlopen.side_effect = [
        json_response(first_page), TimeoutError("timed out"), json_response(last_page),
    ]

    assert client.issue_notes(456) == first_page + last_page
    assert client.issue_notes(456) == first_page + last_page

    assert urlopen.call_count == 3
    pages = [
        urllib.parse.parse_qs(urllib.parse.urlsplit(args[0].full_url).query)["page"]
        for args, _ in urlopen.call_args_list
    ]
    assert pages == [["1"], ["2"], ["2"]]
    sleep.assert_called_once_with(0.25)


@pytest.mark.parametrize("status", [401, 403, 404])
def test_permanent_http_errors_fail_without_retry(client, transport, status):
    """Authentication and missing-resource failures remain immediate errors."""
    urlopen, sleep = transport
    error = urllib.error.HTTPError(client.api_url("issues"), status, "Failure", {}, None)
    urlopen.side_effect = error

    with pytest.raises(urllib.error.HTTPError) as caught:
        client.issue_notes(456)

    assert caught.value is error
    assert urlopen.call_count == 1
    sleep.assert_not_called()


@pytest.mark.parametrize("error", [
    urllib.error.URLError("Temporary connection failure"),
    urllib.error.HTTPError("https://gitlab.example.test", 429, "Rate limited", {"Retry-After": "2"}, None),
    urllib.error.HTTPError("https://gitlab.example.test", 503, "Unavailable", {}, None),
])
def test_existing_transient_error_retries_are_preserved(client, transport, error):
    """URL and transient HTTP failures retain backoff and Retry-After handling."""
    urlopen, sleep = transport
    urlopen.side_effect = [error, json_response([])]

    assert client.issue_notes(456) == []

    assert urlopen.call_count == 2
    delay = 2.0 if isinstance(error, urllib.error.HTTPError) and error.code == 429 else 0.25
    sleep.assert_called_once_with(delay)
