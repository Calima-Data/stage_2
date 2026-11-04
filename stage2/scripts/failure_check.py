#!/usr/bin/env python3
"""Checks coordination under failures and concurrency, with real processes.

Each scenario runs on a clean deployment. Crashes use FAULT_POINT, which halts the process without
cleanup at an exact line, so the dangerous moment between completing work and recording it is hit
every time rather than by luck.

    python scripts/failure_check.py --data-dir <folder outside OneDrive>

Exit status 0 means every scenario passed. The report goes to results/stage2/failure_checks.json.
"""
import argparse
import json
import shutil
import sqlite3
import sys
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

from deploy import STAGE2, Deployment, Topology

CORPUS = (STAGE2 / "fixtures" / "corpus").resolve()
RESULTS = STAGE2.parent / "results" / "stage2"
BOOKS = [1342, 84, 11, 2000, 17489, 9001, 9002, 9003]
SEARCH = "http://localhost:7003"
SEARCH_2 = "http://localhost:7013"
INGESTION = "http://localhost:7001"
INGESTION_2 = "http://localhost:7011"
INDEXING = "http://localhost:7002"
INDEXING_2 = "http://localhost:7012"
CONTROL = "http://localhost:7000"
LEASE_MS = 6000  # longer than a JVM restart, so the checks run while the dead claim is still leased
FAST_RETRIES = {"RETRY_BASE_MS": 50, "RETRY_MAX_MS": 500, "MAX_ATTEMPTS": 200}


def json_get(url):
    return requests.get(url, timeout=30).json()


def post(url, **kwargs):
    return requests.post(url, timeout=60, **kwargs)


def post_expecting_crash(url):
    try:
        response = requests.post(url, timeout=30)
    except requests.RequestException:
        return
    raise AssertionError(f"Expected the process to crash, but {url} answered {response.status_code}")


def wait_published(books, timeout=90):
    deadline = time.time() + timeout
    states = {}
    while time.time() < deadline:
        states = {book: json_get(f"{CONTROL}/control/books/{book}")["state"] for book in books}
        if all(state in ("PUBLISHED", "FAILED") for state in states.values()):
            break
        time.sleep(0.1)
    assert all(state == "PUBLISHED" for state in states.values()), f"Not all published: {states}"


def book_files(data_dir, book):
    return sorted(p.name for p in (data_dir / "datalake").rglob(f"{book}.*"))


def assert_one_complete_pair(data_dir, book):
    files = book_files(data_dir, book)
    assert files == [f"{book}.body.txt", f"{book}.header.txt"], f"Book {book} has files {files}"


def datamart_counts(data_dir):
    conn = sqlite3.connect(f"file:{(data_dir / 'datamart' / 'search.db').as_posix()}?mode=ro", uri=True)
    try:
        rows = conn.execute("SELECT (SELECT COUNT(*) FROM books), (SELECT COUNT(*) FROM postings), "
                            "(SELECT COUNT(DISTINCT book_id) FROM postings), (SELECT version FROM index_state)").fetchone()
        return {"books": rows[0], "postings": rows[1], "books_with_postings": rows[2], "version": rows[3]}
    finally:
        conn.close()


class Scenario:
    """A clean deployment for one scenario, with the local Gutenberg stand-in already running."""

    def __init__(self, root, name, corpus=CORPUS, **topology):
        self.dir = root / name
        self.corpus = corpus
        shutil.rmtree(self.dir, ignore_errors=True)
        self.dir.mkdir(parents=True)
        self.topology = Topology(data_dir=self.dir, heap="384m", extra_env={"INGEST_LEASE_MS": LEASE_MS, **FAST_RETRIES}, **topology)
        self.deployment = Deployment(self.topology)

    def __enter__(self):
        self.deployment.start_mock(self.corpus)
        return self

    def __exit__(self, *exc):
        self.deployment.down()


def ingestion_crash(root, point, expect_held_at_restart):
    with Scenario(root, f"ingest_{point}") as s:
        d = s.deployment
        d.start_service("ingestion", FAULT_POINT=point)
        d.start_service("indexing")
        post_expecting_crash(f"{INGESTION}/ingest/1342")
        assert d.wait_exit("ingestion-0") == 137

        d.start_service("ingestion")
        status = json_get(f"{INGESTION}/ingest/status/1342")
        if expect_held_at_restart:
            assert status["status"] == "in_progress", status
            assert json_get(f"{INGESTION}/ingest/list")["count"] == 0
            refused = post(f"{INDEXING}/index/update/1342")
            assert refused.status_code == 409 and refused.json()["error"] == "book_not_ready", refused.text
            assert post(f"{INGESTION}/ingest/1342").status_code == 409, "the lease must still protect the claim"
            time.sleep(LEASE_MS / 1000 + 0.2)
        retried = post(f"{INGESTION}/ingest/1342")
        assert retried.status_code == 200, retried.text
        assert_one_complete_pair(s.dir, 1342)
        assert not [p for p in (s.dir / "datalake").rglob("*") if ".tmp-" in p.name], "temporary files remain"
        assert post(f"{INDEXING}/index/update/1342").json()["index"] == "updated"
        return retried.json()["status"]


def scenario_ingest_crash_after_claim(root):
    assert ingestion_crash(root, "ingest_after_claim", True) == "downloaded"


def scenario_ingest_crash_between_files(root):
    assert ingestion_crash(root, "ingest_after_header", True) == "downloaded"


def scenario_ingest_crash_before_commit(root):
    assert ingestion_crash(root, "ingest_before_commit", True) == "downloaded"


def scenario_ingest_crash_after_commit(root):
    # The work was recorded but the answer was lost: the retry must not download again.
    assert ingestion_crash(root, "ingest_after_commit", False) == "already_available"


def scenario_index_crash_before_commit(root):
    with Scenario(root, "index_before_commit") as s:
        d = s.deployment
        d.start_service("ingestion")
        d.start_service("indexing", FAULT_POINT="index_before_commit")
        d.start_service("search")
        assert post(f"{INGESTION}/ingest/1342").status_code == 200
        post_expecting_crash(f"{INDEXING}/index/update/1342")
        assert d.wait_exit("indexing-0") == 137

        counts = datamart_counts(s.dir)
        assert counts == {"books": 0, "postings": 0, "books_with_postings": 0, "version": 0}, counts
        assert requests.get(f"{SEARCH}/books/1342", timeout=10).status_code == 404
        assert json_get(f"{SEARCH}/search?q=prejudice")["total"] == 0

        d.start_service("indexing")
        assert post(f"{INDEXING}/index/update/1342").json()["index"] == "updated"
        assert json_get(f"{SEARCH}/search?q=prejudice")["total"] == 1


def scenario_rebuild_crash_keeps_published_index(root):
    with Scenario(root, "rebuild_before_commit") as s:
        d = s.deployment
        d.start_service("ingestion")
        d.start_service("indexing", FAULT_POINT="rebuild_before_commit")
        d.start_service("search")
        for book in BOOKS:
            assert post(f"{INGESTION}/ingest/{book}").status_code == 200
            assert post(f"{INDEXING}/index/update/{book}").status_code == 200
        before = datamart_counts(s.dir)
        answer = json_get(f"{SEARCH}/search?q=adventure")

        post_expecting_crash(f"{INDEXING}/index/rebuild")
        assert d.wait_exit("indexing-0") == 137
        assert datamart_counts(s.dir) == before
        assert json_get(f"{SEARCH}/search?q=adventure") == answer

        d.start_service("indexing")
        assert post(f"{INDEXING}/index/rebuild").json()["books_processed"] == len(BOOKS)
        assert json_get(f"{SEARCH}/search?q=adventure") == answer


def control_crash(root, point):
    with Scenario(root, f"control_{point}") as s:
        d = s.deployment
        for service in ("ingestion", "indexing", "search"):
            d.start_service(service)
        d.start_control(FAULT_POINT=point, CONTROL_WORKERS=1)
        post(f"{CONTROL}/control/books", json={"book_ids": BOOKS})
        assert d.wait_exit("control") == 137

        d.start_control()
        wait_published(BOOKS)
        repeated = post(f"{CONTROL}/control/books", json={"book_ids": BOOKS}).json()
        assert repeated["enqueued"] == 0
        for book in BOOKS:
            assert_one_complete_pair(s.dir, book)
        counts = datamart_counts(s.dir)
        assert counts["books"] == len(BOOKS) and counts["books_with_postings"] == len(BOOKS), counts
        assert counts["version"] == len(BOOKS), f"each book must be published once: {counts}"


def scenario_control_crash_after_ingest_before_recording(root):
    control_crash(root, "control_before_record_pending")


def scenario_control_crash_after_index_before_recording(root):
    control_crash(root, "control_before_record_ingested")


def scenario_control_crash_after_check_before_recording(root):
    control_crash(root, "control_before_record_indexed")


def scenario_outage_of_indexing_is_retried(root):
    with Scenario(root, "indexing_outage") as s:
        d = s.deployment
        d.start_service("ingestion")
        d.start_service("search")
        d.start_control()  # Indexing is down: tasks must wait, not fail
        post(f"{CONTROL}/control/books", json={"book_ids": BOOKS})
        deadline = time.time() + 30
        while time.time() < deadline:
            if json_get(f"{CONTROL}/control/status")["states"]["INGESTED"] == len(BOOKS):
                break
            time.sleep(0.1)
        status = json_get(f"{CONTROL}/control/status")["states"]
        assert status["INGESTED"] == len(BOOKS) and status["FAILED"] == 0, status
        assert json_get(f"{CONTROL}/control/books/1342")["last_error"] is not None

        d.start_service("indexing")
        wait_published(BOOKS)


def scenario_outage_of_search_delays_only_the_final_check(root):
    with Scenario(root, "search_outage") as s:
        d = s.deployment
        d.start_service("ingestion")
        d.start_service("indexing")
        d.start_control()
        post(f"{CONTROL}/control/books", json={"book_ids": BOOKS})
        deadline = time.time() + 30
        while time.time() < deadline:
            if json_get(f"{CONTROL}/control/status")["states"]["INDEXED"] == len(BOOKS):
                break
            time.sleep(0.1)
        assert json_get(f"{INDEXING}/index/status")["books_indexed"] == len(BOOKS)
        assert json_get(f"{CONTROL}/control/status")["states"]["PUBLISHED"] == 0
        d.start_service("search")
        wait_published(BOOKS)


def scenario_search_keeps_serving_when_the_pipeline_is_down(root):
    with Scenario(root, "search_independent") as s:
        d = s.deployment
        d.start_service("ingestion")
        d.start_service("indexing")
        d.start_service("search")
        for book in BOOKS:
            post(f"{INGESTION}/ingest/{book}")
            post(f"{INDEXING}/index/update/{book}")
        d.kill("ingestion-0")
        d.kill("indexing-0")
        assert json_get(f"{SEARCH}/search?q=adventure")["total"] == 4


def scenario_replica_failover(root):
    with Scenario(root, "replica_failover", ingestion=2, indexing=2) as s:
        d = s.deployment
        d.up()
        d.kill("ingestion-1")
        d.kill("indexing-0")
        post(f"{CONTROL}/control/books", json={"book_ids": BOOKS})
        wait_published(BOOKS)
        assert json_get(f"{CONTROL}/control/status")["states"]["FAILED"] == 0


def scenario_concurrent_duplicates_across_replicas(root):
    with Scenario(root, "duplicates", ingestion=2, indexing=2) as s:
        d = s.deployment
        for service, count in (("ingestion", 2), ("indexing", 2)):
            for replica in range(count):
                d.start_service(service, replica)
        calls = 16
        with ThreadPoolExecutor(calls) as pool:
            ingests = list(pool.map(lambda i: post(f"{INGESTION if i % 2 else INGESTION_2}/ingest/1342"), range(calls)))
        codes = [r.status_code for r in ingests]
        assert set(codes) <= {200, 409} and 200 in codes, codes
        downloaded = [r for r in ingests if r.status_code == 200 and r.json()["status"] == "downloaded"]
        assert len(downloaded) == 1, f"one replica must download: {[r.text for r in ingests]}"
        assert_one_complete_pair(s.dir, 1342)

        with ThreadPoolExecutor(calls) as pool:
            updates = list(pool.map(lambda i: post(f"{INDEXING if i % 2 else INDEXING_2}/index/update/1342"), range(calls)))
        assert all(r.status_code == 200 for r in updates), [r.text for r in updates]
        assert sum(r.json()["index"] == "updated" for r in updates) == 1, [r.text for r in updates]
        counts = datamart_counts(s.dir)
        assert counts["books"] == 1 and counts["version"] == 1, counts


def scenario_concurrent_distinct_books_across_replicas(root):
    with Scenario(root, "concurrent_books", indexing=3) as s:
        d = s.deployment
        d.start_service("ingestion")
        for replica in range(3):
            d.start_service("indexing", replica)
        for book in BOOKS:
            assert post(f"{INGESTION}/ingest/{book}").status_code == 200
        urls = [f"http://localhost:{7002 + 10 * i}" for i in range(3)]
        with ThreadPoolExecutor(len(BOOKS)) as pool:
            updates = list(pool.map(lambda pair: post(f"{urls[pair[0] % 3]}/index/update/{pair[1]}"), enumerate(BOOKS)))
        assert all(r.status_code == 200 and r.json()["index"] == "updated" for r in updates), [r.text for r in updates]
        counts = datamart_counts(s.dir)
        assert counts["books"] == len(BOOKS) and counts["books_with_postings"] == len(BOOKS), counts
        assert counts["version"] == len(BOOKS), counts


def large_corpus(root, books, kilobytes):
    """Books large enough that a rebuild takes seconds, so reads really overlap with it."""
    corpus = root / "large_corpus"
    shutil.rmtree(corpus, ignore_errors=True)
    corpus.mkdir(parents=True)
    for book in range(1, books + 1):
        words = " ".join(f"word{(book * 7 + i) % 40000}" for i in range(kilobytes * 100))
        (corpus / f"{book}.txt").write_text(
            f"Title: Large Book {book}\nAuthor: Author {book}\nLanguage: English\n\n"
            f"*** START OF THE PROJECT GUTENBERG EBOOK LARGE {book} ***\nadventure {words}\n"
            f"*** END OF THE PROJECT GUTENBERG EBOOK LARGE {book} ***\n", encoding="utf-8")
    return corpus


def scenario_search_sees_complete_states_during_rebuild(root):
    books = 40
    with Scenario(root, "read_consistency", corpus=large_corpus(root, books, 500), search=2, search_cache=0) as s:
        d = s.deployment
        d.start_service("ingestion")
        d.start_service("indexing")
        for replica in range(2):
            d.start_service("search", replica)
        for book in range(1, books + 1):
            assert post(f"{INGESTION}/ingest/{book}").status_code == 200
            assert post(f"{INDEXING}/index/update/{book}").status_code == 200
        expected = json_get(f"{SEARCH}/search?q=adventure&limit=100")
        assert expected["total"] == books, expected["total"]

        stop = threading.Event()
        problems, answers = [], [0]
        answers_lock = threading.Lock()

        def reader(url):
            last_version = -1
            # A persistent connection: one per request would exhaust the host's ephemeral ports.
            with requests.Session() as session:
                try:
                    while not stop.is_set():
                        response = session.get(f"{url}/search?q=adventure&limit=100", timeout=30)
                        if response.status_code != 200:
                            problems.append(response.text[:200])
                            continue
                        version = int(response.headers["X-Index-Version"])
                        if response.json() != expected:
                            problems.append(f"partial answer: total {response.json()['total']}")
                        if version < last_version:
                            problems.append(f"version went back from {last_version} to {version}")
                        last_version = version
                        with answers_lock:
                            answers[0] += 1
                except Exception as error:  # a reader that dies must fail the check, not reduce its evidence
                    problems.append(f"reader failed: {type(error).__name__}: {error}")

        threads = [threading.Thread(target=reader, args=(url,)) for url in (SEARCH, SEARCH_2) for _ in range(3)]
        for thread in threads:
            thread.start()
        started = time.time()
        rebuilds = 5
        try:
            for _ in range(rebuilds):
                assert post(f"{INDEXING}/index/rebuild").status_code == 200
        finally:
            elapsed = time.time() - started
            stop.set()
            for thread in threads:
                thread.join()
        assert not problems, problems[:3]
        assert answers[0] > 0
        return {"responses_checked": answers[0], "rebuilds": rebuilds, "seconds_rebuilding": round(elapsed, 1), "books": books}


def scenario_book_appears_only_when_complete(root):
    with Scenario(root, "publish_visibility", search_cache=0) as s:
        d = s.deployment
        d.start_service("ingestion")
        d.start_service("indexing")
        d.start_service("search")
        assert post(f"{INGESTION}/ingest/11").status_code == 200
        assert requests.get(f"{SEARCH}/books/11", timeout=10).status_code == 404
        assert json_get(f"{SEARCH}/search?q=rabbit")["total"] == 0
        assert post(f"{INDEXING}/index/update/11").status_code == 200
        assert requests.get(f"{SEARCH}/books/11", timeout=10).status_code == 200
        assert json_get(f"{SEARCH}/search?q=rabbit")["total"] == 1


SCENARIOS = [value for name, value in list(globals().items()) if name.startswith("scenario_")]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--data-dir", type=Path, required=True, help="working folder, outside OneDrive")
    parser.add_argument("--only", help="run only the scenarios whose name contains this text")
    args = parser.parse_args()
    root = args.data_dir.resolve()
    if "onedrive" in str(root).lower():
        parser.error("--data-dir must be outside OneDrive")
    root.mkdir(parents=True, exist_ok=True)

    results = []
    for scenario in SCENARIOS:
        name = scenario.__name__.removeprefix("scenario_")
        if args.only and args.only not in name:
            continue
        started = time.perf_counter()
        try:
            detail = scenario(root)
            results.append({"scenario": name, "passed": True, "seconds": round(time.perf_counter() - started, 1), "detail": detail})
            print(f"PASS  {name}" + (f"  {detail}" if detail else ""), flush=True)
        except Exception as error:
            results.append({"scenario": name, "passed": False, "detail": f"{type(error).__name__}: {error}"[:800]})
            print(f"FAIL  {name}\n      {type(error).__name__}: {str(error)[:800]}", flush=True)
            if not isinstance(error, AssertionError):
                traceback.print_exc()

    passed = sum(r["passed"] for r in results)
    print(f"\n{passed}/{len(results)} scenarios passed")
    if not args.only:
        RESULTS.mkdir(parents=True, exist_ok=True)
        (RESULTS / "failure_checks.json").write_text(json.dumps(
            {"total": len(results), "passed": passed, "scenarios": results}, indent=2, ensure_ascii=False), encoding="utf-8")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
