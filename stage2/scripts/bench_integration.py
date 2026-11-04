#!/usr/bin/env python3
"""End-to-end benchmarks of the whole system on this machine.

    python scripts/bench_integration.py all --data-dir <folder outside OneDrive>
    python scripts/bench_integration.py pipeline --data-dir <folder> --repeats 3
    python scripts/bench_integration.py search --data-dir <folder> --repeats 3
    python scripts/bench_integration.py real-gutenberg --data-dir <folder>

Books come from a local stand-in serving a corpus of real Gutenberg texts, so the numbers describe
this system rather than the Internet and do not load a public service. `real-gutenberg` runs a few
books through the actual site. Every experiment changes one factor from the baseline; client
concurrency, replicas and threads are recorded separately in each row. Each run also verifies
correctness: a configuration only counts if it produced the same index or the same answers.
"""
import argparse
import csv
import json
import os
import platform
import shutil
import sqlite3
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

import psutil
import requests

from deploy import JARS, STAGE2, Deployment, Topology

RESULTS = Path(os.environ.get("BENCH_RESULTS_DIR", STAGE2.parent / "results" / "stage2" / "integration"))
CORPUS_IDS_FILE = STAGE2 / "fixtures" / "benchmark_corpus_ids.txt"
FIRST_BOOK_ID = 100001
PIPELINE_BOOKS = 200
SEARCH_INDEX_BOOKS = 500
SAMPLE_SECONDS = 0.5
WARMUP_S = 5
MEASURE_S = 15

# Terms from real books, chosen to span postings lists of different lengths, with filters.
QUERY_TEMPLATES = [
    "q=the", "q=whale", "q=rabbit", "q=monster", "q=love", "q=king%20queen", "q=sea%20ship", "q=night%20day",
    "q=the&language=en", "q=the&language=es", "q=castle&language=en", "q=king&author=shakespeare",
    "q=love&author=austen", "q=the&year=1851", "q=sea&year=1851&language=en", "q=the&limit=5&offset=10",
    "q=captain", "q=garden", "q=amor&language=es", "q=dragon",
]


def corpus_ids():
    return [int(line.split("#")[0]) for line in CORPUS_IDS_FILE.read_text().splitlines() if line.split("#")[0].strip()]


def fetch_corpus(root):
    """Downloads each real book once, with a courtesy pause."""
    corpus = root / "corpus"
    corpus.mkdir(parents=True, exist_ok=True)
    for book_id in corpus_ids():
        target = corpus / f"{book_id}.txt"
        if not target.exists():
            response = requests.get(f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt", timeout=120,
                                    headers={"User-Agent": "calima-search-engine/1.0 (benchmark corpus)"})
            response.raise_for_status()
            target.write_bytes(response.content)
            print(f"   downloaded {book_id}", flush=True)
            time.sleep(2)
    return corpus


class ResourceSampler:
    """Samples CPU and memory per process and for the host while an experiment runs."""

    def __init__(self, deployment, client_processes=()):
        self.deployment = deployment
        self.client_processes = client_processes
        self.samples = []
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    @staticmethod
    def role(name):
        return name.rsplit("-", 1)[0] if name[-1].isdigit() else name

    def _run(self):
        processes = {}
        for name, entry in self.deployment.processes.items():
            try:
                process = psutil.Process(entry["process"].pid)
                process.cpu_percent(None)
                processes[name] = process
            except psutil.Error:
                pass
        psutil.cpu_percent(None)
        start = time.time()
        while not self.stop_event.wait(SAMPLE_SECONDS):
            sample = {"t": round(time.time() - start, 2), "host_cpu_percent": psutil.cpu_percent(None),
                      "host_memory_percent": psutil.virtual_memory().percent}
            for name, process in processes.items():
                try:
                    sample[f"{name}.cpu"] = process.cpu_percent(None)
                    sample[f"{name}.rss_mb"] = process.memory_info().rss / 1048576
                except psutil.Error:
                    pass
            self.samples.append(sample)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.stop_event.set()
        self.thread.join()

    def summary(self):
        """Mean host CPU, plus mean CPU (100 = one core) and peak memory per service, over all replicas."""
        result = {}
        if not self.samples:
            return result
        result["host_cpu_percent_mean"] = round(statistics.mean(s["host_cpu_percent"] for s in self.samples), 1)
        result["host_memory_percent_max"] = max(s["host_memory_percent"] for s in self.samples)
        names = {key.rsplit(".", 1)[0] for s in self.samples for key in s if key.endswith(".cpu")}
        roles = {}
        for name in names:
            roles.setdefault(self.role(name), []).append(name)
        for role, members in sorted(roles.items()):
            cpu = [sum(s.get(f"{n}.cpu", 0) for n in members) for s in self.samples]
            rss = [sum(s.get(f"{n}.rss_mb", 0) for n in members) for s in self.samples]
            result[f"{role}_cpu_percent_mean"] = round(statistics.mean(cpu), 1)
            result[f"{role}_rss_mb_max"] = round(max(rss), 1)
        return result

    def write_timeseries(self, path):
        fields = sorted({key for sample in self.samples for key in sample})
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fields)
            writer.writeheader()
            writer.writerows(self.samples)


def datamart_checksum(data_dir):
    """A fingerprint of the published index, compared across configurations."""
    conn = sqlite3.connect(f"file:{(data_dir / 'datamart' / 'search.db').as_posix()}?mode=ro", uri=True)
    try:
        return "/".join(str(v) for v in conn.execute(
            "SELECT (SELECT COUNT(*) FROM books), COUNT(*), SUM(tf), SUM(book_id * tf) FROM postings").fetchone())
    finally:
        conn.close()


def fresh_dir(root, name):
    path = root / name
    shutil.rmtree(path, ignore_errors=True)
    path.mkdir(parents=True)
    return path


def append_row(file_name, row):
    RESULTS.mkdir(parents=True, exist_ok=True)
    path = RESULTS / file_name
    rows = []
    if path.exists():
        with open(path, newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
    rows.append({key: str(value) for key, value in row.items()})
    fields = []
    for existing in rows:
        for key in existing:
            if key not in fields:
                fields.append(key)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


# --- pipeline: ingestion + indexing + publication ----------------------------------------------

PIPELINE_CONFIGS = [
    ("workers", dict(control_workers=1)),
    ("workers", dict(control_workers=2)),
    ("workers", dict(control_workers=4)),
    ("workers", dict(control_workers=8)),
    ("workers", dict(control_workers=16)),
    ("indexing_replicas", dict(control_workers=8, indexing=2)),
    ("indexing_replicas", dict(control_workers=8, indexing=4)),
    ("ingestion_replicas", dict(control_workers=8, ingestion=2)),
    ("durability", dict(control_workers=8, synchronous="NORMAL")),
    ("upstream_latency_100ms", dict(control_workers=1, latency_ms=100)),
    ("upstream_latency_100ms", dict(control_workers=8, latency_ms=100)),
]


def run_pipeline_once(root, corpus, experiment, config, repeat, books, write_series=False):
    settings = dict(config)
    latency = settings.pop("latency_ms", 0)
    data_dir = fresh_dir(root, "pipeline_run")
    topology = Topology(data_dir=data_dir, heap="1g", **settings)
    with Deployment(topology) as deployment:
        deployment.start_mock(corpus, max_book_id=FIRST_BOOK_ID + books, latency_ms=latency)
        deployment.up()
        with ResourceSampler(deployment) as sampler:
            started = time.time()
            requests.post(f"{topology.control_url}/control/books", timeout=30,
                          json={"from": FIRST_BOOK_ID, "to": FIRST_BOOK_ID + books - 1}).raise_for_status()
            while True:
                status = requests.get(f"{topology.control_url}/control/status", timeout=30).json()
                states = status["states"]
                if states["PUBLISHED"] + states["FAILED"] >= books:
                    break
                time.sleep(0.05)
            elapsed = time.time() - started
        metrics = status["published_tasks"]
        checksum = datamart_checksum(data_dir)
        size_mb = requests.get(f"{topology.urls('indexing')[0]}/index/status", timeout=30).json()["index_size_MB"]
    if write_series:
        RESULTS.mkdir(parents=True, exist_ok=True)
        sampler.write_timeseries(RESULTS / f"timeseries_pipeline_workers{settings.get('control_workers')}.csv")

    row = {
        "experiment": experiment, "repeat": repeat, "books": books,
        "control_workers": settings.get("control_workers", 4), "ingestion_replicas": settings.get("ingestion", 1),
        "indexing_replicas": settings.get("indexing", 1), "search_replicas": 1,
        "http_threads": "default", "sqlite_synchronous": settings.get("synchronous", "FULL"), "upstream_latency_ms": latency,
        "elapsed_s": round(elapsed, 3), "throughput_books_per_s": round(books / elapsed, 3),
        "published": states["PUBLISHED"], "failed": states["FAILED"], "retries": metrics["total_retries"],
        "avg_queue_wait_ms": round(metrics["avg_queue_wait_ms"], 1), "avg_ingest_ms": round(metrics["avg_ingest_ms"], 2),
        "avg_index_ms": round(metrics["avg_index_ms"], 2), "avg_publish_check_ms": round(metrics["avg_publish_ms"], 2),
        "avg_coordination_ms": round(metrics["avg_coordination_ms"], 2), "avg_book_total_ms": round(metrics["avg_total_ms"], 1),
        "index_checksum": checksum, "index_size_MB": size_mb, **sampler.summary(),
    }
    append_row("pipeline.csv", row)
    print(f"   {experiment} {config} #{repeat}: {row['throughput_books_per_s']} books/s, "
          f"index {row['avg_index_ms']} ms, retries {row['retries']}, failed {row['failed']}, {checksum}", flush=True)


def cmd_pipeline(args, root, corpus):
    for experiment, config in PIPELINE_CONFIGS:
        for repeat in range(args.repeats):
            run_pipeline_once(root, corpus, experiment, config, repeat, args.pipeline_books,
                              write_series=experiment == "workers" and config["control_workers"] == 8 and repeat == 0)


def cmd_real_gutenberg(args, root, corpus):
    """A few real downloads through the whole system, at a courteous concurrency."""
    data_dir = fresh_dir(root, "real_gutenberg")
    ids = [1342, 84, 11, 16, 2701, 1661, 2000, 64317, 98, 74, 345, 1952]
    topology = Topology(data_dir=data_dir, heap="1g", control_workers=2)
    with Deployment(topology) as deployment:
        deployment.up()
        started = time.time()
        requests.post(f"{topology.control_url}/control/books", timeout=30, json={"book_ids": ids}).raise_for_status()
        while True:
            status = requests.get(f"{topology.control_url}/control/status", timeout=30).json()
            if status["states"]["PUBLISHED"] + status["states"]["FAILED"] >= len(ids):
                break
            time.sleep(0.2)
        elapsed = time.time() - started
        search = topology.urls("search")[0]
        queries = {}
        for query in ("q=whale", "q=whale&year=1851", "q=rabbit&author=carroll", "q=espana&language=es", "q=vampire", "q=prejudice&year=1813"):
            answer = requests.get(f"{search}/search?{query}", timeout=30).json()
            queries[query] = {"total": answer["total"], "top": [r["title"] for r in answer["results"][:2]]}
        books = {str(i): requests.get(f"{search}/books/{i}", timeout=30).json() for i in ids}
    report = {"books_requested": len(ids), "states": status["states"], "elapsed_s": round(elapsed, 2),
              "books_per_s": round(len(ids) / elapsed, 3), "control_workers": 2,
              "avg_ingest_ms": status["published_tasks"]["avg_ingest_ms"],
              "avg_index_ms": status["published_tasks"]["avg_index_ms"], "queries": queries, "books": books}
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "real_gutenberg.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("states", "elapsed_s", "books_per_s", "queries")}, indent=1, ensure_ascii=False)[:1500], flush=True)


# --- search under load -------------------------------------------------------------------------

SEARCH_CONFIGS = (
    [("client_concurrency", dict(clients=c, search_cache=0)) for c in (1, 2, 4, 8, 16, 32, 64)]
    + [("search_threads", dict(clients=32, http_threads=t, search_pool=t, search_cache=0)) for t in (1, 2, 4, 8, 16, 32)]
    + [("search_replicas", dict(clients=32, search=r, search_cache=0)) for r in (1, 2, 4)]
    + [("cache", dict(clients=c, search_cache=1000)) for c in (1, 8, 32)]
)


def build_search_index(root, corpus, books):
    """Builds once the index that every search experiment reads."""
    data_dir = root / "search_index"
    marker = data_dir / f"complete_{books}"
    if marker.exists():
        return data_dir
    data_dir = fresh_dir(root, "search_index")
    topology = Topology(data_dir=data_dir, heap="1g", control_workers=8, synchronous="NORMAL")
    with Deployment(topology) as deployment:
        deployment.start_mock(corpus, max_book_id=FIRST_BOOK_ID + books)
        deployment.up()
        requests.post(f"{topology.control_url}/control/books", timeout=30,
                      json={"from": FIRST_BOOK_ID, "to": FIRST_BOOK_ID + books - 1}).raise_for_status()
        while True:
            states = requests.get(f"{topology.control_url}/control/status", timeout=30).json()["states"]
            if states["PUBLISHED"] + states["FAILED"] >= books:
                break
            time.sleep(0.2)
        assert states["PUBLISHED"] == books, states
    marker.write_text(datamart_checksum(data_dir))
    return data_dir


def expected_answers(topology, out_file):
    """The baseline answer to each query; the load tester counts any different total as a mismatch."""
    url = topology.urls("search")[0]
    lines = []
    for template in QUERY_TEMPLATES:
        answer = requests.get(f"{url}/search?{template}", timeout=60)
        answer.raise_for_status()
        lines.append(f"{template}\t{answer.json()['total']}")
    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return lines


def load(topology, query_file, clients, out_json, deployment, warmup=WARMUP_S, measure=MEASURE_S, sampler_series=None):
    urls = ",".join(topology.urls("search"))
    command = ["java", "-Xmx1g", "-jar", str(JARS["tools"]), urls, str(query_file), str(clients), str(warmup), str(measure), str(out_json)]
    client = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deployment.processes["load-tester"] = {"process": client, "port": 0}
    with ResourceSampler(deployment) as sampler:
        code = client.wait()
    deployment.processes.pop("load-tester")
    if code != 0:
        raise RuntimeError(f"Load tester exited with {code}")
    if sampler_series:
        sampler.write_timeseries(sampler_series)
    return json.loads(out_json.read_text(encoding="utf-8")), sampler.summary()


def cmd_search(args, root, corpus):
    index_dir = build_search_index(root, corpus, args.search_books)
    work = root / "search_run"
    query_file = work / "queries.tsv"
    work.mkdir(parents=True, exist_ok=True)
    checksum = (index_dir / f"complete_{args.search_books}").read_text()
    for experiment, config in SEARCH_CONFIGS:
        settings = dict(config)
        clients = settings.pop("clients")
        for repeat in range(args.repeats):
            topology = Topology(data_dir=index_dir, heap="1g", **settings)
            with Deployment(topology) as deployment:
                for replica in range(topology.search):
                    deployment.start_service("search", replica)
                # Calculated with a fresh process, and compared by the load tester on every response.
                expected_answers(topology, query_file)
                out = work / "load.json"
                series = RESULTS / f"timeseries_search_clients{clients}.csv" if (experiment == "client_concurrency" and repeat == 0 and clients == 32) else None
                RESULTS.mkdir(parents=True, exist_ok=True)
                summary, resources = load(topology, query_file, clients, out, deployment, sampler_series=series)
            row = {"experiment": experiment, "repeat": repeat, "client_concurrency": clients,
                   "search_replicas": settings.get("search", 1),
                   "http_threads": settings.get("http_threads") or "default",
                   "db_connections_per_replica": settings.get("search_pool", 16),
                   "cache_entries": settings.get("search_cache", 1000), "books_in_index": args.search_books,
                   "index_checksum": checksum,
                   **{k: (round(v, 3) if isinstance(v, float) else v) for k, v in summary.items() if k not in ("client_concurrency", "target_replicas")},
                   **resources}
            append_row("search.csv", row)
            print(f"   {experiment} clients={clients} {settings} #{repeat}: {summary['throughput_rps']:.0f} rps, "
                  f"p95 {summary['latency_p95_ms']:.2f} ms, errors {summary['errors']}, mismatches {summary['mismatches']}", flush=True)


def cmd_mixed(args, root, corpus):
    """Search latency with and without a concurrent pipeline writing to the same datamart."""
    extra_books = 100
    for mode in ("idle_index", "while_indexing"):
        for repeat in range(args.repeats):
            source = build_search_index(root, corpus, args.search_books)
            data_dir = fresh_dir(root, "mixed_run")
            shutil.copytree(source / "datamart", data_dir / "datamart")
            shutil.copytree(source / "datalake", data_dir / "datalake")
            shutil.copytree(source / "state", data_dir / "state")
            topology = Topology(data_dir=data_dir, heap="1g", control_workers=4, search_cache=0)
            with Deployment(topology) as deployment:
                deployment.start_mock(corpus, max_book_id=FIRST_BOOK_ID + args.search_books + extra_books)
                deployment.up()
                query_file = data_dir / "queries.tsv"
                # While the index changes the totals change too, so they are not asserted here.
                query_file.write_text("\n".join(QUERY_TEMPLATES) + "\n", encoding="utf-8")
                if mode == "while_indexing":
                    first = FIRST_BOOK_ID + args.search_books
                    requests.post(f"{topology.control_url}/control/books", timeout=30,
                                  json={"from": first, "to": first + extra_books - 1}).raise_for_status()
                summary, resources = load(topology, query_file, 8, data_dir / "load.json", deployment, warmup=2, measure=15)
                states = requests.get(f"{topology.control_url}/control/status", timeout=30).json()["states"]
            append_row("mixed.csv", {"mode": mode, "repeat": repeat, "client_concurrency": 8, "control_workers": 4,
                                     "books_published_during_or_before": states["PUBLISHED"], "failed": states["FAILED"],
                                     **{k: (round(v, 3) if isinstance(v, float) else v) for k, v in summary.items() if k != "client_concurrency"},
                                     **resources})
            print(f"   {mode} #{repeat}: {summary['throughput_rps']:.0f} rps, p95 {summary['latency_p95_ms']:.2f} ms, "
                  f"errors {summary['errors']}, published {states['PUBLISHED']}", flush=True)


def write_host_info():
    info = {"os": platform.platform(), "logical_cpus": psutil.cpu_count(), "physical_cpus": psutil.cpu_count(logical=False),
            "ram_gb": round(psutil.virtual_memory().total / 1024 ** 3, 1),
            "java": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.strip().splitlines(),
            "python": sys.version, "commit": subprocess.run(["git", "-C", str(STAGE2), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip(),
            "working_tree_dirty": bool(subprocess.run(["git", "-C", str(STAGE2), "status", "--porcelain", "--", "."], capture_output=True, text=True).stdout.strip()),
            "topology_note": "All processes, including the load generator and the Gutenberg stand-in, run on this host.",
            "warmup_s": WARMUP_S, "measure_s": MEASURE_S, "sample_seconds": SAMPLE_SECONDS,
            "heap_per_service": "-Xmx1g", "default_http_threads": "Javalin default: Jetty pool of at most 250 threads",
            "started_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    if os.name == "nt":
        cpu = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name"], capture_output=True, text=True).stdout.strip()
        disk = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-PhysicalDisk | ForEach-Object { $_.FriendlyName + ' ' + $_.MediaType + ' ' + $_.BusType }"], capture_output=True, text=True).stdout.strip()
        info.update({"cpu": cpu, "storage": disk})
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "host.json").write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8")


COMMANDS = {"real-gutenberg": cmd_real_gutenberg, "pipeline": cmd_pipeline, "search": cmd_search, "mixed": cmd_mixed}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("command", choices=[*COMMANDS, "all"])
    parser.add_argument("--data-dir", type=Path, required=True, help="working folder, outside OneDrive")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--pipeline-books", type=int, default=PIPELINE_BOOKS)
    parser.add_argument("--search-books", type=int, default=SEARCH_INDEX_BOOKS)
    args = parser.parse_args()
    root = args.data_dir.resolve()
    if "onedrive" in str(root).lower():
        parser.error("--data-dir must be outside OneDrive")
    root.mkdir(parents=True, exist_ok=True)

    write_host_info()
    corpus = fetch_corpus(root)
    for name, command in COMMANDS.items():
        if args.command in (name, "all"):
            print(f"== {name}", flush=True)
            command(args, root, corpus)


if __name__ == "__main__":
    main()
