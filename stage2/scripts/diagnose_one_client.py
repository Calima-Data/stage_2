#!/usr/bin/env python3
"""Why does one client get so little out of Search?

In the search campaign, 1 client reached 699 requests/s and 2 clients 2.6 times as much, and each request
cost 36% more of Search's CPU with one client. Hypothesis: with one client the cores are idle between
requests, so every request pays to wake a thread (and the CPU) up. If so, keeping other cores busy with
unrelated work should make the single client faster, and a client that is not waiting should not change it.

    python scripts/diagnose_one_client.py --data-dir C:/tmp/bench --repeats 3

Runs, interleaved to cancel drift: 1 client; 1 client with background spinners; 2 clients; 2 clients with spinners.
The spinners are separate Python processes that do nothing but loop. Output: one CSV row per run.
"""
import argparse
import csv
import os
import subprocess
import sys
from pathlib import Path

import requests

from bench_integration import QUERY_TEMPLATES, build_search_index, expected_answers, fetch_corpus, load
from deploy import STAGE2, Deployment, Topology

OUT = STAGE2.parent / "results" / "stage2" / "evidence" / "one_client"
SPINNERS = 6
VARIANTS = [("1_client", 1, 0), ("1_client_spinners", 1, SPINNERS), ("2_clients", 2, 0), ("2_clients_spinners", 2, SPINNERS)]


def start_spinners(count):
    return [subprocess.Popen([sys.executable, "-I", "-c", "while True: pass"]) for _ in range(count)]


def stop_spinners(processes):
    for process in processes:
        process.kill()
    for process in processes:
        process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--search-books", type=int, default=500)
    args = parser.parse_args()
    root = args.data_dir
    corpus = fetch_corpus(root)
    index_dir = build_search_index(root, corpus, args.search_books)
    work = root / "one_client_run"
    work.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for repeat in range(args.repeats):
        for name, clients, spinners in VARIANTS:
            topology = Topology(data_dir=index_dir, heap="1g", search_cache=0)
            with Deployment(topology) as deployment:
                deployment.start_service("search", 0)
                query_file = work / "queries.tsv"
                expected_answers(topology, query_file)
                busy = start_spinners(spinners)
                try:
                    summary, resources = load(topology, query_file, clients, work / "load.json", deployment, warmup=10)
                finally:
                    stop_spinners(busy)
            row = {"variant": name, "repeat": repeat, "clients": clients, "spinners": spinners,
                   "throughput_rps": round(summary["throughput_rps"], 1), "p50_ms": round(summary["latency_p50_ms"], 3),
                   "p95_ms": round(summary["latency_p95_ms"], 3), "errors": summary["errors"], "mismatches": summary["mismatches"],
                   **resources}
            rows.append(row)
            print(f"{name} #{repeat}: {row['throughput_rps']} rps, p50 {row['p50_ms']} ms, p95 {row['p95_ms']} ms, "
                  f"search cpu {resources.get('search_cpu_percent_mean')}", flush=True)
    fields = []
    for row in rows:
        for key in row:
            if key not in fields:
                fields.append(key)
    with open(OUT / "one_client.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
