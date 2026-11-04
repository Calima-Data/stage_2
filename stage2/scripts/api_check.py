#!/usr/bin/env python3
"""Validates the running services against api/openapi.yaml, without any front-end.

Deploys all services against the local Gutenberg stand-in and the fixture corpus, runs the books
through the Control Module, and then checks every documented operation: status codes, expected
answers, and each response against the schema of the specification.

    python scripts/api_check.py --data-dir <folder outside OneDrive>
    python scripts/api_check.py --data-dir <folder> --keep-running    # then try api/requests.http

Exit status 0 means every check passed. The report goes to results/stage2/api_validation.json.
"""
import argparse
import json
import re
import shutil
import sys
import time
import traceback
from pathlib import Path

import jsonschema
import requests
import yaml
from openapi_spec_validator import validate as validate_spec

from deploy import STAGE2, Deployment, Topology

SPEC_PATH = STAGE2 / "api" / "openapi.yaml"
CORPUS = STAGE2 / "fixtures" / "corpus"
RESULTS = STAGE2.parent / "results" / "stage2"
PUBLISHABLE_BOOKS = [1342, 84, 11, 2000, 17489, 9001, 9002, 9003]
MISSING_BOOK = 99999  # beyond the highest ID the stand-in serves
SEARCH = "http://localhost:7003"
INGESTION = "http://localhost:7001"
INDEXING = "http://localhost:7002"
CONTROL = "http://localhost:7000"


class Contract:
    """Looks up the response schema of an operation and records which operations were exercised."""

    def __init__(self):
        self.spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
        validate_spec(self.spec)
        self.operations = {}
        for route, item in self.spec["paths"].items():
            for method, operation in item.items():
                pattern = "^" + re.sub(r"\{[^}]+\}", "[^/]+", route) + "$"
                self.operations[(method.upper(), route)] = (re.compile(pattern), operation)
        self.exercised = set()

    def _deref(self, node):
        while isinstance(node, dict) and "$ref" in node:
            current = self.spec
            for part in node["$ref"].lstrip("#/").split("/"):
                current = current[part]
            node = current
        return node

    def _to_json_schema(self, node):
        """OpenAPI 3.0 schemas are JSON Schema, except for $ref resolution and 'nullable'."""
        node = self._deref(node)
        if isinstance(node, list):
            return [self._to_json_schema(item) for item in node]
        if not isinstance(node, dict):
            return node
        converted = {key: self._to_json_schema(value) if key in ("items", "oneOf", "additionalProperties") else value
                     for key, value in node.items() if key not in ("nullable", "description", "example", "format")}
        if "properties" in node:
            converted["properties"] = {k: self._to_json_schema(v) for k, v in node["properties"].items()}
        if node.get("nullable"):
            return {"anyOf": [converted, {"type": "null"}]}
        return converted

    def check(self, method, url, response):
        path = re.sub(r"^https?://[^/]+", "", url).split("?")[0]
        matches = [(route, op) for (m, route), (pattern, op) in self.operations.items() if m == method and pattern.match(path)]
        # A literal route such as /ingest/list wins over a template that also matches it.
        matches.sort(key=lambda item: "{" in item[0])
        if not matches:
            raise AssertionError(f"{method} {path} is not in the specification")
        route, operation = matches[0]
        self.exercised.add((method, route))
        documented = operation["responses"].get(str(response.status_code))
        if documented is None:
            raise AssertionError(f"{method} {route} answered {response.status_code}, which is not documented")
        media = self._deref(documented).get("content", {}).get("application/json")
        if media is not None:
            jsonschema.validate(response.json(), self._to_json_schema(media["schema"]))

    def missing(self):
        return sorted(f"{m} {r}" for m, r in self.operations if (m, r) not in self.exercised)


class Checker:
    def __init__(self):
        self.contract = Contract()
        self.results = []

    def request(self, method, url, expect, **kwargs):
        response = requests.request(method, url, timeout=60, **kwargs)
        if response.status_code != expect:
            raise AssertionError(f"{method} {url}: expected {expect}, got {response.status_code}: {response.text[:300]}")
        self.contract.check(method, url, response)
        return response

    def get(self, url, expect=200):
        return self.request("GET", url, expect)

    def post(self, url, expect=200, **kwargs):
        return self.request("POST", url, expect, **kwargs)

    def error(self, method, url, expect, code, **kwargs):
        body = self.request(method, url, expect, **kwargs).json()
        assert body["error"] == code, f"{url}: expected error {code}, got {body}"

    def case(self, name, function):
        started = time.perf_counter()
        try:
            function()
            self.results.append({"check": name, "passed": True, "ms": round((time.perf_counter() - started) * 1000, 1)})
            print(f"PASS  {name}")
        except Exception as error:  # a failed check must not hide the following ones
            detail = f"{type(error).__name__}: {error}"
            self.results.append({"check": name, "passed": False, "detail": detail[:800]})
            print(f"FAIL  {name}\n      {detail[:800]}")
            if not isinstance(error, (AssertionError, jsonschema.ValidationError)):
                traceback.print_exc()


def ids(answer):
    return [result["book_id"] for result in answer["results"]]


def wait_until_settled(checker, book_ids, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        states = [requests.get(f"{CONTROL}/control/books/{book}", timeout=10).json()["state"] for book in book_ids]
        if all(state in ("PUBLISHED", "FAILED") for state in states):
            return dict(zip(book_ids, states))
        time.sleep(0.1)
    raise AssertionError(f"Books did not settle: {dict(zip(book_ids, states))}")


def pipeline_checks(c):
    def before_any_book_is_published():
        c.error("GET", f"{SEARCH}/books/1342", 404, "book_not_found")
        c.error("POST", f"{INDEXING}/index/update/1342", 409, "book_not_ready")
        assert c.get(f"{INGESTION}/ingest/status/1342").json()["status"] == "not_ingested"
        assert c.get(f"{INDEXING}/index/status/1342").json()["status"] == "not_indexed"
        assert c.get(f"{SEARCH}/search?q=adventure").json()["total"] == 0
    c.case("before ingestion: indexing is refused and search is empty", before_any_book_is_published)

    def submit():
        result = c.post(f"{CONTROL}/control/books", 202, json={"book_ids": PUBLISHABLE_BOOKS + [9050, MISSING_BOOK]}).json()
        assert result == {"requested": 10, "enqueued": 10, "already_known": 0}
        states = wait_until_settled(c, PUBLISHABLE_BOOKS + [9050, MISSING_BOOK])
        assert all(states[book] == "PUBLISHED" for book in PUBLISHABLE_BOOKS), states
        assert states[9050] == "FAILED" and states[MISSING_BOOK] == "FAILED", states
    c.case("orchestration: 8 books published; 404 and invalid books FAILED", submit)

    def control_api():
        task = c.get(f"{CONTROL}/control/books/1342").json()
        assert task["state"] == "PUBLISHED" and task["ingest_ms"] >= 0 and task["coordination_ms"] > 0, task
        status = c.get(f"{CONTROL}/control/status").json()
        assert status["states"]["PUBLISHED"] == 8 and status["states"]["FAILED"] == 2, status
        assert status["published_tasks"]["count"] == 8
        c.error("GET", f"{CONTROL}/control/books/777777", 404, "task_not_found")
        c.error("GET", f"{CONTROL}/control/books/abc", 400, "invalid_book_id")
    c.case("control: task state, progress summary and errors", control_api)

    def submit_is_idempotent_and_validated():
        repeated = c.post(f"{CONTROL}/control/books", 202, json={"book_ids": [1342, 84]}).json()
        assert repeated == {"requested": 2, "enqueued": 0, "already_known": 2}
        c.error("POST", f"{CONTROL}/control/books", 400, "invalid_body", json={"book_ids": "x"})
        c.error("POST", f"{CONTROL}/control/books", 400, "invalid_body", data="not json")
        c.error("POST", f"{CONTROL}/control/books", 400, "invalid_body", json={"from": 9, "to": 1})
    c.case("control: submission is idempotent and validated", submit_is_idempotent_and_validated)

    def retry_failed():
        c.error("POST", f"{CONTROL}/control/books/1342/retry", 409, "task_not_failed")
        assert c.post(f"{CONTROL}/control/books/{MISSING_BOOK}/retry", 202).json()["state"] == "PENDING"
        assert wait_until_settled(c, [MISSING_BOOK])[MISSING_BOOK] == "FAILED"
        c.error("POST", f"{CONTROL}/control/books/abc/retry", 400, "invalid_book_id")
    c.case("control: a FAILED book can be retried, other states refuse", retry_failed)


def search_checks(c):
    def keyword():
        answer = c.get(f"{SEARCH}/search?q=adventure").json()
        assert answer["query"] == "adventure" and answer["filters"] == {} and answer["offset"] == 0 and answer["limit"] == 20
        assert answer["total"] == 4 and set(ids(answer)) == {11, 84, 1342, 9001}, answer
        scores = [r["score"] for r in answer["results"]]
        assert scores == sorted(scores, reverse=True), scores
    c.case("search: keyword query returns exactly the expected books, by score", keyword)

    def ranking():
        assert ids(c.get(f"{SEARCH}/search?q=adventure").json())[0] == 11
    c.case("search: the book that repeats the term most ranks first", ranking)

    def each_filter():
        assert set(ids(c.get(f"{SEARCH}/search?q=adventure&language=en").json())) == {11, 84, 1342, 9001}
        assert ids(c.get(f"{SEARCH}/search?q=aventura&language=es").json()) == [2000]
        assert ids(c.get(f"{SEARCH}/search?q=adventure&author=CARROLL").json()) == [11]
        assert ids(c.get(f"{SEARCH}/search?q=adventure&year=1865").json()) == [11]
    c.case("search: each filter works alone", each_filter)

    def combined():
        answer = c.get(f"{SEARCH}/search?q=adventure&author=carroll&language=EN&year=1865").json()
        assert ids(answer) == [11] and answer["filters"] == {"author": "carroll", "language": "en", "year": 1865}, answer
        assert c.get(f"{SEARCH}/search?q=adventure&author=carroll&year=1813").json()["total"] == 0
        assert ids(c.get(f"{SEARCH}/search?q=adventure&language=en&year=1813").json()) == [1342]
    c.case("search: filters combine, and a contradiction gives no results", combined)

    def year_sources():
        assert ids(c.get(f"{SEARCH}/search?q=adventure&year=1925").json()) == [9001]
        assert c.get(f"{SEARCH}/search?q=adventure&year=2001").json()["total"] == 0
    c.case("search: year comes from the header or the curated list, never the release date", year_sources)

    def multiple_terms():
        assert ids(c.get(f"{SEARCH}/search?q=white%20rabbit").json()) == [11]
        assert c.get(f"{SEARCH}/search?q=white%20unicorn").json()["total"] == 0
    c.case("search: several terms must all match", multiple_terms)

    def normalization():
        assert ids(c.get(f"{SEARCH}/search?q=espana").json()) == [2000]
        assert ids(c.get(f"{SEARCH}/search?q=ESPAÑA").json()) == [2000]
        assert ids(c.get(f"{SEARCH}/search?q=misere").json()) == [17489]
    c.case("search: non-ASCII words match with or without diacritics", normalization)

    def indexing_scope():
        assert c.get(f"{SEARCH}/search?q=footerword").json()["total"] == 0
        assert ids(c.get(f"{SEARCH}/search?q=quokka").json()) == [9003]
    c.case("search: the license footer is not indexed; older THIS markers are parsed", indexing_scope)

    def empty():
        answer = c.get(f"{SEARCH}/search?q=unicorn").json()
        assert answer["total"] == 0 and answer["results"] == []
        assert c.get(f"{SEARCH}/search?q=adventure&language=fi").json()["results"] == []
    c.case("search: no matches is 200 with an empty list", empty)

    def pagination():
        everything = ids(c.get(f"{SEARCH}/search?q=adventure").json())
        first = c.get(f"{SEARCH}/search?q=adventure&limit=2").json()
        second = c.get(f"{SEARCH}/search?q=adventure&limit=2&offset=2").json()
        beyond = c.get(f"{SEARCH}/search?q=adventure&offset=100").json()
        assert first["total"] == second["total"] == beyond["total"] == 4
        assert ids(first) + ids(second) == everything
        assert beyond["results"] == [] and beyond["offset"] == 100
    c.case("search: pages are disjoint, ordered, and total counts all matches", pagination)

    def invalid():
        for query, code in [("", "missing_query"), ("?q=", "missing_query"), ("?q=!!!", "invalid_query"),
                            ("?q=" + "a" * 201, "invalid_query"), ("?q=x&limit=0", "invalid_limit"),
                            ("?q=x&limit=101", "invalid_limit"), ("?q=x&limit=two", "invalid_limit"),
                            ("?q=x&offset=-1", "invalid_offset"), ("?q=x&year=18x3", "invalid_year"),
                            ("?q=x&language=english", "invalid_language"), ("?q=x&author=%20", "invalid_author"),
                            ("?q=x&lang=en", "unknown_parameter"), ("?q=x&q=y", "repeated_parameter")]:
            c.error("GET", f"{SEARCH}/search{query}", 400, code)
    c.case("search: invalid parameters return documented errors", invalid)

    def details():
        book = c.get(f"{SEARCH}/books/1342").json()
        assert (book["title"], book["author"], book["language"], book["year"]) == ("Pride and Prejudice", "Jane Austen", "en", 1813), book
        anonymous = c.get(f"{SEARCH}/books/9002").json()
        assert anonymous["author"] is None and anonymous["year"] is None, anonymous
        assert c.get(f"{SEARCH}/books/17489").json()["language"] == "fr"
        c.error("GET", f"{SEARCH}/books/999999", 404, "book_not_found")
        c.error("GET", f"{SEARCH}/books/abc", 400, "invalid_book_id")
    c.case("books: metadata extraction, missing fields, and errors", details)

    def filter_values():
        filters = c.get(f"{SEARCH}/search/filters").json()
        languages = {item["value"]: item["books"] for item in filters["languages"]}
        assert languages == {"en": 6, "es": 1, "fr": 1}, languages
        assert {item["value"] for item in filters["years"]} >= {1813, 1818, 1865, 1925}, filters["years"]
        assert any(item["value"] == "Jane Austen" for item in filters["authors"])
    c.case("filters: values and counts reflect the published books", filter_values)

    def unknown_route():
        response = requests.get(f"{SEARCH}/nothing", timeout=10)
        assert response.status_code == 404 and response.json()["error"] == "route_not_found"
    c.case("errors: an unknown route answers JSON", unknown_route)


def internal_checks(c):
    def ingestion():
        repeated = c.post(f"{INGESTION}/ingest/1342").json()
        assert repeated["status"] == "already_available", repeated
        assert re.fullmatch(r"datalake/\d{8}/\d{2}/1342", repeated["path"]), repeated
        status = c.get(f"{INGESTION}/ingest/status/1342").json()
        assert status["status"] == "available" and status["path"] == repeated["path"] and len(status["body_sha256"]) == 64
        assert c.get(f"{INGESTION}/ingest/status/8888").json()["status"] == "not_ingested"
        listed = c.get(f"{INGESTION}/ingest/list").json()
        assert listed["count"] == 8 and listed["books"] == sorted(PUBLISHABLE_BOOKS), listed
    c.case("ingestion: repeat is idempotent; status and list expose only complete books", ingestion)

    def ingestion_errors():
        c.error("POST", f"{INGESTION}/ingest/9050", 422, "invalid_book_format")
        c.error("POST", f"{INGESTION}/ingest/{MISSING_BOOK}", 404, "book_not_found")
        c.error("POST", f"{INGESTION}/ingest/abc", 400, "invalid_book_id")
        c.error("GET", f"{INGESTION}/ingest/status/-1", 400, "invalid_book_id")
        assert c.get(f"{INGESTION}/ingest/status/9050").json()["status"] == "failed"
    c.case("ingestion: permanent and validation errors", ingestion_errors)

    def indexing():
        before = c.get(f"{INDEXING}/index/status").json()
        assert before["books_indexed"] == 8 and before["last_update"] is not None, before
        assert c.post(f"{INDEXING}/index/update/1342").json() == {"book_id": 1342, "index": "unchanged"}
        assert c.get(f"{INDEXING}/index/status").json()["index_version"] == before["index_version"]
        assert c.get(f"{INDEXING}/index/status/1342").json()["status"] == "indexed"
        c.error("POST", f"{INDEXING}/index/update/8888", 409, "book_not_ready")
        c.error("POST", f"{INDEXING}/index/update/abc", 400, "invalid_book_id")
        c.error("GET", f"{INDEXING}/index/status/abc", 400, "invalid_book_id")
    c.case("indexing: repeat changes nothing; an unready book is refused", indexing)

    def rebuild():
        before = c.get(f"{SEARCH}/search?q=adventure").json()
        version = c.get(f"{INDEXING}/index/status").json()["index_version"]
        result = c.post(f"{INDEXING}/index/rebuild").json()
        assert result["books_processed"] == 8 and result["elapsed_time"].endswith("s"), result
        assert c.get(f"{INDEXING}/index/status").json()["index_version"] == version + 1
        assert c.get(f"{SEARCH}/search?q=adventure").json() == before
    c.case("indexing: rebuild publishes a new version with identical answers", rebuild)

    def statuses():
        for url, service in [(SEARCH, "search-service"), (INGESTION, "ingestion-service"), (INDEXING, "indexing-service"),
                             (CONTROL, "control-module")]:
            status = c.get(f"{url}/status").json()
            assert status["service"] == service and status["status"] == "running", status
    c.case("operations: every service reports its status and thread configuration", statuses)

    def complete_coverage():
        missing = c.contract.missing()
        assert not missing, f"Operations never exercised: {missing}"
    c.case("specification: every documented operation was exercised", complete_coverage)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--data-dir", type=Path, required=True, help="working folder, emptied first; outside OneDrive")
    parser.add_argument("--keep-running", action="store_true", help="leave the services up to try api/requests.http")
    args = parser.parse_args()

    data_dir = args.data_dir.resolve()
    if "onedrive" in str(data_dir).lower():
        parser.error("--data-dir must be outside OneDrive")
    shutil.rmtree(data_dir, ignore_errors=True)
    data_dir.mkdir(parents=True)

    topology = Topology(data_dir=data_dir, control_workers=4, heap="512m", extra_env={"RETRY_BASE_MS": 20, "RETRY_MAX_MS": 200})
    deployment = Deployment(topology)
    checker = Checker()
    try:
        deployment.start_mock(CORPUS.resolve())
        deployment.up()
        pipeline_checks(checker)
        search_checks(checker)
        internal_checks(checker)
    finally:
        if args.keep_running:
            print("Services left running. Stop them with: python scripts/deploy.py down --data-dir", data_dir)
        else:
            deployment.down()

    RESULTS.mkdir(parents=True, exist_ok=True)
    report = {"specification": checker.contract.spec["info"]["version"], "topology": topology.describe(),
              "total": len(checker.results), "passed": sum(r["passed"] for r in checker.results), "checks": checker.results}
    (RESULTS / "api_validation.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n{report['passed']}/{report['total']} checks passed")
    sys.exit(0 if report["passed"] == report["total"] else 1)


if __name__ == "__main__":
    main()
