#!/usr/bin/env python3
"""Runs the JMH component benchmarks and stores their raw results and exact commands.

    python scripts/run_jmh.py                  # the published configuration
    python scripts/run_jmh.py --smoke          # one short iteration of each, to test the setup

Run it on an idle machine, after `mvn package`. Results go to results/stage2/jmh/.
"""
import argparse
import csv
import json
import subprocess
import sys
import time
from pathlib import Path

STAGE2 = Path(__file__).resolve().parent.parent
RESULTS = STAGE2.parent / "results" / "stage2" / "jmh"

# (module, benchmark regex, thread counts). The threads vary only where concurrency is intended:
# parsing and queries run in parallel in production, and publication shows lock contention.
PLAN = [
    ("indexing-service", "TextProcessingBenchmark.tokenizeBook", [1, 2, 4, 8, 16]),
    ("indexing-service", "TextProcessingBenchmark.extractMetadata", [1]),
    ("indexing-service", "DatamartPublishBenchmark", [1, 2, 4]),
    ("ingestion-service", "IngestionBenchmark.splitHeaderAndBody", [1]),
    ("ingestion-service", "IngestionBenchmark.writeBookFiles", [1, 4]),
    ("ingestion-service", "IngestionBenchmark.claimAndCompleteInLedger", [1, 4]),
    ("search-service", "SearchEngineBenchmark", [1, 2, 4, 8, 16]),
]
HEAP = "-Xmx2G"
# JMH formats its CSV with the JVM's locale; a Spanish one would write decimal commas.
LOCALE = "-Duser.language=en -Duser.country=US"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    iterations = ["-wi", "1", "-i", "1", "-w", "200ms", "-r", "200ms", "-f", "1"] if args.smoke else \
        ["-wi", "5", "-i", "10", "-w", "1s", "-r", "1s", "-f", "2"]
    out_dir = RESULTS / "smoke" if args.smoke else RESULTS
    out_dir.mkdir(parents=True, exist_ok=True)

    combined, commands = [], []
    for module, pattern, thread_counts in PLAN:
        jar = STAGE2 / module / "target" / f"{module}-1.0.0-benchmarks.jar"
        if not jar.exists():
            sys.exit(f"{jar} is missing: run `mvn package` first")
        for threads in thread_counts:
            raw = out_dir / f"{pattern}.t{threads}.csv"
            command = ["java", HEAP, *LOCALE.split(), "-jar", str(jar), pattern, *iterations, "-t", str(threads),
                       "-jvmArgs", f"{HEAP} {LOCALE}", "-rf", "csv", "-rff", str(raw)]
            printable = " ".join(["java", HEAP, LOCALE, "-jar", f"{module}/target/{jar.name}", pattern, *iterations, "-t", str(threads),
                                  "-jvmArgs", f'"{HEAP} {LOCALE}"', "-rf", "csv", "-rff", f"results/stage2/jmh/{raw.name}"])
            commands.append(printable)
            print(f"== {pattern} threads={threads}", flush=True)
            started = time.time()
            done = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
            if done.returncode != 0 or not raw.exists():
                print(done.stdout[-3000:], done.stderr[-2000:])
                sys.exit(f"Benchmark failed: {pattern}")
            with open(raw, newline="", encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    row["Threads"] = threads
                    row["Module"] = module
                    combined.append(row)
                    print(f"   {row['Benchmark'].rsplit('.', 1)[-1]} "
                          f"{float(row['Score']):.2f} ± {row['Score Error (99.9%)']} {row['Unit']}", flush=True)
            print(f"   ({time.time() - started:.0f}s)", flush=True)

    fields = sorted({key for row in combined for key in row})
    with open(out_dir / "all_results.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(combined)
    (out_dir / "commands.json").write_text(json.dumps({"iterations": iterations, "heap": HEAP, "commands": commands}, indent=2))


if __name__ == "__main__":
    main()
