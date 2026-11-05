#!/usr/bin/env python3
"""Is the read drop in the mixed workload a database effect, or just competition for CPU?

In the mixed workload, reads fell 24% (4897 -> 3721 requests/s) and the slowest request went from about 30 ms to
320-480 ms, while the host CPU stayed at 65-66% in both cases. While writing, the indexer used about one core.
Hypothesis: the host is 8 physical cores with 2 threads each, so "66%" is close to full for this work, and any
extra busy thread takes capacity from the readers. If so, replacing the writers with processes that only loop
(no SQLite, no locks, no disk) should cost the readers about the same.

    python scripts/diagnose_mixed_cpu.py --data-dir C:/tmp/bench --repeats 3

Variants, interleaved: reads alone; reads + 1 spinner; reads + 2 spinners. Same index, 8 clients, 30 s, no cache,
as in the mixed benchmark. A spinner is a separate Python process that does nothing but loop.
"""
import argparse
import csv
from pathlib import Path

from bench_integration import build_search_index, expected_answers, fetch_corpus, load
from deploy import STAGE2, Deployment, Topology
from diagnose_one_client import start_spinners, stop_spinners

OUT = STAGE2.parent / "results" / "stage2" / "evidence" / "mixed_cpu"
VARIANTS = [("reads_alone", 0), ("reads_1_spinner", 1), ("reads_2_spinners", 2)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--search-books", type=int, default=500)
    args = parser.parse_args()
    root = args.data_dir
    corpus = fetch_corpus(root)
    index_dir = build_search_index(root, corpus, args.search_books)
    work = root / "mixed_cpu_run"
    work.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for repeat in range(args.repeats):
        for name, spinners in VARIANTS:
            topology = Topology(data_dir=index_dir, heap="1g", search_cache=0)
            with Deployment(topology) as deployment:
                deployment.start_service("search", 0)
                query_file = work / "queries.tsv"
                expected_answers(topology, query_file)
                busy = start_spinners(spinners)
                try:
                    summary, resources = load(topology, query_file, 8, work / "load.json", deployment, warmup=2, measure=30)
                finally:
                    stop_spinners(busy)
            row = {"variant": name, "repeat": repeat, "spinners": spinners,
                   "throughput_rps": round(summary["throughput_rps"], 1), "p95_ms": round(summary["latency_p95_ms"], 3),
                   "max_ms": round(summary["latency_max_ms"], 2), "errors": summary["errors"], "mismatches": summary["mismatches"],
                   **resources}
            rows.append(row)
            print(f"{name} #{repeat}: {row['throughput_rps']} rps, p95 {row['p95_ms']} ms, max {row['max_ms']} ms, "
                  f"host cpu {resources.get('host_cpu_percent_mean')}", flush=True)
    fields = []
    for row in rows:
        for key in row:
            if key not in fields:
                fields.append(key)
    with open(OUT / "mixed_cpu.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
