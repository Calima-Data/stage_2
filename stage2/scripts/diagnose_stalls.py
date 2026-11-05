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

With --security-counters, a second sampler records each ~0.7 s the CPU and disk traffic of the Windows
security and indexing processes (MsMpEng is Microsoft Defender's engine; OneDrive and SearchIndexer are the
other usual suspects) from the WMI performance classes, whose names do not depend on the language of Windows.
Each window then also shows their mean CPU and traffic, to see whether the stalls coincide with their activity.
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
WATCHED = ("MsMpEng", "OneDrive", "SearchIndexer")
SECURITY_SCRIPT = (
    "while ($true) { $t = [DateTimeOffset]::Now.ToUnixTimeMilliseconds(); "
    "Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -Filter \"Name='MsMpEng' OR Name='OneDrive' OR Name='SearchIndexer'\" | "
    "ForEach-Object { \"$t,$($_.Name),$($_.PercentProcessorTime),$($_.IOWriteBytesPersec),$($_.IOReadBytesPersec)\" }; "
    "Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk | Where-Object { $_.Name -ne '_Total' } | "
    "ForEach-Object { \"$t,DISK:$($_.Name),$($_.AvgDiskSecPerWrite),$($_.CurrentDiskQueueLength),$($_.PercentDiskTime)\" }; "
    "Start-Sleep -Milliseconds 300 }"
)


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
                "epoch_s": round(now, 2),
                "t_s": round(now - started, 2),
                "books_published": published(),
                "fsync_worst_ms": round(worst, 2),
                "cpu_work_ms": round(cpu_ms, 2),
                "host_cpu_percent": psutil.cpu_percent(),
                "disk_write_mb_s": round((io.write_bytes - last_io.write_bytes) / 1048576 / elapsed, 1),
                "disk_read_mb_s": round((io.read_bytes - last_io.read_bytes) / 1048576 / elapsed, 1),
            })
            last_io, last_t = io, now


def read_security(path):
    """Rows (epoch_s, process, cpu_percent, write_bytes_s, read_bytes_s) written by the PowerShell sampler."""
    rows = []
    if path is None or not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.strip().split(",")
        if len(parts) == 5 and parts[0].isdigit():
            try:
                rows.append((int(parts[0]) / 1000, parts[1], float(parts[2]), float(parts[3]), float(parts[4])))
            except ValueError:
                continue
    return rows


def security_columns(rows, begin, end):
    """Mean CPU and write traffic of each watched process between two epoch times."""
    columns = {}
    for name in WATCHED:
        mine = [r for r in rows if r[1] == name and begin <= r[0] <= end]
        # One reading per instant: a process can show several instances, which are added up.
        by_time = {}
        for r in mine:
            cpu, write = by_time.get(r[0], (0.0, 0.0))
            by_time[r[0]] = (cpu + r[2], write + r[3])
        columns[f"{name}_cpu_percent"] = round(statistics.mean(v[0] for v in by_time.values()), 1) if by_time else ""
        columns[f"{name}_write_mb_s"] = round(statistics.mean(v[1] for v in by_time.values()) / 1048576, 2) if by_time else ""
    # The physical disk is measured below the file system and its filters: if its own latency rises during a
    # stall, the device is slow; if it does not, the wait is above it. The latency is a relative figure here,
    # because the units of this formatted class are not converted.
    disk = {}
    for r in rows:
        if r[1].startswith("DISK:") and "C:" in r[1] and begin <= r[0] <= end:
            disk[r[0]] = (r[2], r[3], r[4])
    columns["disk_avg_write_latency"] = round(statistics.mean(v[0] for v in disk.values()), 1) if disk else ""
    columns["disk_queue_length"] = round(statistics.mean(v[1] for v in disk.values()), 2) if disk else ""
    columns["disk_busy_percent"] = round(statistics.mean(v[2] for v in disk.values()), 1) if disk else ""
    return columns


def windows(samples, security=()):
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
            **(security_columns(security, running[start]["epoch_s"], running[end]["epoch_s"]) if security else {}),
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
    parser.add_argument("--security-counters", action="store_true", help="also record Defender, OneDrive and SearchIndexer activity (Windows)")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    samples, stop = [], threading.Event()
    thread = threading.Thread(target=probe, args=(args.data_dir / "stall_probe.bin", samples, stop), daemon=True)
    thread.start()
    security_file = args.out / "security_counters.csv" if args.security_counters else None
    security_process = None
    if security_file:
        security_process = subprocess.Popen(["powershell", "-NoProfile", "-Command", SECURITY_SCRIPT],
                                            stdout=open(security_file, "w", encoding="utf-8"), stderr=subprocess.DEVNULL)
    env = dict(os.environ, BENCH_RESULTS_DIR=str(args.out.resolve()))
    result = subprocess.run([sys.executable, "scripts/bench_integration.py", "growth", "--data-dir", str(args.data_dir), "--growth-books", args.books],
                            cwd=STAGE2, env=env, check=False)
    stop.set()
    thread.join(timeout=10)
    if security_process:
        security_process.kill()
        security_process.wait()

    write_csv(args.out / "probe.csv", samples)
    rows = windows(samples, read_security(security_file))
    write_csv(args.out / "windows.csv", rows)
    print(" ".join(rows[0]))
    for row in rows:
        print(" ".join(str(value) for value in row.values()))
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
