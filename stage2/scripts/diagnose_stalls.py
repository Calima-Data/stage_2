#!/usr/bin/env python3
"""Tells whether the stalls of a long indexing run come from the services or from the host.

Runs the `growth` benchmark next to a probe that shares only the machine with it. Each second the
probe records the books published so far and:

- the worst of ten fsyncs of a small file of its own, on the same disk;
- the time of a fixed amount of pure CPU work;
- the bytes the whole disk wrote and read.

If publishing stalls while the probe's fsyncs slow down and its CPU work does not, the wait is in the
storage path. The writes come partly from this system, so this locates the wait, not who caused it.

    python scripts/diagnose_stalls.py --data-dir C:/tmp/bench --out ../results/stage2/evidence/stalls --books 500

--data-dir must hold the corpus that bench_integration.py prepared in it.
"""
import argparse
import csv
import os
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

import psutil
import requests

STAGE2 = Path(__file__).resolve().parent.parent
CONTROL_STATUS = "http://localhost:7000/control/status"
WINDOW_S = 10


def timed_fsync_ms(handle):
    start = time.perf_counter()
    handle.seek(0)
    handle.write(os.urandom(4096))
    handle.flush()
    os.fsync(handle.fileno())
    return (time.perf_counter() - start) * 1000


def cpu_work_ms():
    start = time.perf_counter()
    total = 0
    for i in range(200_000):
        total += i * i
    return (time.perf_counter() - start) * 1000


def published():
    try:
        return requests.get(CONTROL_STATUS, timeout=5).json()["states"]["PUBLISHED"]
    except (requests.RequestException, KeyError, ValueError):
        return None


def probe(probe_file, samples, stop):
    started = time.time()
    last_io, last_t = psutil.disk_io_counters(), started
    with open(probe_file, "wb") as handle:
        while not stop.is_set():
            worst = 0.0
            for _ in range(10):
                worst = max(worst, timed_fsync_ms(handle))
                time.sleep(0.1)
            cpu_ms = cpu_work_ms()
            now, io = time.time(), psutil.disk_io_counters()
            elapsed = now - last_t
            samples.append({
                "t_s": round(now - started, 2),
                "books_published": published(),
                "fsync_worst_ms": round(worst, 2),
                "cpu_work_ms": round(cpu_ms, 2),
                "host_cpu_percent": psutil.cpu_percent(),
                "disk_write_mb_s": round((io.write_bytes - last_io.write_bytes) / 1048576 / elapsed, 1),
                "disk_read_mb_s": round((io.read_bytes - last_io.read_bytes) / 1048576 / elapsed, 1),
            })
            last_io, last_t = io, now


def windows(samples):
    """Rows of WINDOW_S seconds from the first book published to the last."""
    running = [s for s in samples if s["books_published"] is not None]
    first = next(i for i, s in enumerate(running) if s["books_published"] > 0)
    rows = []
    start = max(first - 1, 0)
    while start < len(running) - 1:
        end = start + 1
        while end < len(running) - 1 and running[end]["t_s"] - running[start]["t_s"] < WINDOW_S:
            end += 1
        part = running[start + 1:end + 1]
        elapsed = running[end]["t_s"] - running[start]["t_s"]
        rows.append({
            "from_s": running[start]["t_s"],
            "to_s": running[end]["t_s"],
            "books_at_end": running[end]["books_published"],
            "books_per_s": round((running[end]["books_published"] - running[start]["books_published"]) / elapsed, 2),
            "fsync_median_ms": round(statistics.median(s["fsync_worst_ms"] for s in part), 1),
            "fsync_max_ms": round(max(s["fsync_worst_ms"] for s in part), 1),
            "cpu_work_median_ms": round(statistics.median(s["cpu_work_ms"] for s in part), 1),
            "disk_write_mb_s": round(statistics.mean(s["disk_write_mb_s"] for s in part), 1),
        })
        start = end
    return rows


def write_csv(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", type=Path, required=True, help="working folder of the benchmark, outside OneDrive")
    parser.add_argument("--out", type=Path, required=True, help="folder for the results")
    parser.add_argument("--books", default="500")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    samples, stop = [], threading.Event()
    thread = threading.Thread(target=probe, args=(args.data_dir / "stall_probe.bin", samples, stop), daemon=True)
    thread.start()
    env = dict(os.environ, BENCH_RESULTS_DIR=str(args.out.resolve()))
    result = subprocess.run([sys.executable, "scripts/bench_integration.py", "growth", "--data-dir", str(args.data_dir), "--growth-books", args.books],
                            cwd=STAGE2, env=env, check=False)
    stop.set()
    thread.join(timeout=10)

    write_csv(args.out / "probe.csv", samples)
    rows = windows(samples)
    write_csv(args.out / "windows.csv", rows)
    print(" ".join(rows[0]))
    for row in rows:
        print(" ".join(str(value) for value in row.values()))
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
