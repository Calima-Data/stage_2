#!/usr/bin/env python3
"""Does this disk stall by itself, with none of the services running?

Writes a file in 16 MB blocks, calling fsync after each, and records every second the rate and the slowest
fsync of that second. Next to it, a second thread does what the stall probe does: a tiny 4 KB write plus
fsync on another file, the worst of ten per second. No Java, no SQLite, no Defender exclusions.

    python scripts/diagnose_disk_raw.py --data-dir C:/tmp/bench --gb 24

If the rate collapses and the small-write fsync slows for tens of seconds, the behaviour belongs to the
disk or the operating system, whatever the services do. The test file is deleted at the end.
"""
import argparse
import csv
import os
import threading
import time
from pathlib import Path

BLOCK = 16 * 1024 * 1024


def small_probe(path, samples, stop, started):
    with open(path, "wb") as handle:
        while not stop.is_set():
            worst = 0.0
            for _ in range(10):
                t0 = time.perf_counter()
                handle.seek(0)
                handle.write(os.urandom(4096))
                handle.flush()
                os.fsync(handle.fileno())
                worst = max(worst, (time.perf_counter() - t0) * 1000)
                time.sleep(0.1)
            samples.append((round(time.time() - started, 1), round(worst, 1)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--gb", type=int, default=24)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.data_dir.mkdir(parents=True, exist_ok=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    target = args.data_dir / "disk_raw_test.bin"
    block = os.urandom(BLOCK)
    started = time.time()
    samples, stop = [], threading.Event()
    thread = threading.Thread(target=small_probe, args=(args.data_dir / "disk_raw_probe.bin", samples, stop, started), daemon=True)
    thread.start()
    rows, written, window_bytes, window_worst, window_start = [], 0, 0, 0.0, time.time()
    try:
        with open(target, "wb") as handle:
            while written < args.gb * 1024**3:
                t0 = time.perf_counter()
                handle.write(block)
                handle.flush()
                os.fsync(handle.fileno())
                window_worst = max(window_worst, (time.perf_counter() - t0) * 1000)
                written += BLOCK
                window_bytes += BLOCK
                now = time.time()
                if now - window_start >= 1.0:
                    rows.append({"t_s": round(now - started, 1), "written_gb": round(written / 1024**3, 2),
                                 "mb_s": round(window_bytes / 1048576 / (now - window_start), 1),
                                 "block_fsync_worst_ms": round(window_worst, 1)})
                    window_bytes, window_worst, window_start = 0, 0.0, now
    finally:
        stop.set()
        thread.join(timeout=15)
        target.unlink(missing_ok=True)
        (args.data_dir / "disk_raw_probe.bin").unlink(missing_ok=True)
    small = dict(samples)
    for row in rows:
        near = [v for t, v in samples if abs(t - row["t_s"]) <= 1.0]
        row["small_fsync_worst_ms"] = max(near) if near else ""
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    slow = [r for r in rows if r["mb_s"] < 50]
    print(f"{len(rows)} s, {written / 1024**3:.1f} GB; seconds below 50 MB/s: {len(slow)}; "
          f"median {sorted(r['mb_s'] for r in rows)[len(rows) // 2]} MB/s; worst small fsync {max(v for _, v in samples)} ms")


if __name__ == "__main__":
    main()
