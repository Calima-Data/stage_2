#!/usr/bin/env python3
"""Time and log volume of publishing one book, by insertion order and SQLite page cache.

    python scripts/diagnose_publish.py <search.db of a stopped deployment> <work folder outside OneDrive>

Works on a copy, and inserts the postings of the first book again under new IDs. It explains why
publishing gets slower as the index grows, and it is the evidence for decision D8c.
"""
import random
import shutil
import sqlite3
import statistics
import sys
import time
from pathlib import Path

REPEATS = 6
CACHES = [(0, "default cache"), (64 * 1024, "64 MB cache"), (256 * 1024, "256 MB cache")]


def run(source, work, postings, cache_kb, order):
    copy = work / "copy.db"
    for suffix in ("", "-wal", "-shm"):
        (work / f"copy.db{suffix}").unlink(missing_ok=True)
    shutil.copy(source, copy)
    db = sqlite3.connect(copy, isolation_level=None)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=FULL")
    db.execute("PRAGMA wal_autocheckpoint=1000")
    if cache_kb:
        db.execute(f"PRAGMA cache_size=-{cache_kb}")
    rows = sorted(postings) if order == "sorted" else random.Random(1).sample(postings, len(postings))
    times, log_sizes = [], []
    for i in range(REPEATS):
        new_id = 9_000_000 + i
        started = time.perf_counter()
        db.execute("BEGIN IMMEDIATE")
        db.executemany("INSERT INTO postings (term, book_id, tf) VALUES (?, ?, ?)", [(t, new_id, f) for t, f in rows])
        db.execute("COMMIT")
        times.append((time.perf_counter() - started) * 1000)
        log_sizes.append((work / "copy.db-wal").stat().st_size / 1048576)
    db.close()
    return times, log_sizes


def main():
    source, work = Path(sys.argv[1]), Path(sys.argv[2])
    work.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(f"file:{source.as_posix()}?mode=ro", uri=True)
    books = connection.execute("SELECT COUNT(*) FROM books").fetchone()[0]
    pages = connection.execute("PRAGMA page_count").fetchone()[0]
    page_size = connection.execute("PRAGMA page_size").fetchone()[0]
    book = connection.execute("SELECT book_id FROM books ORDER BY book_id LIMIT 1").fetchone()[0]
    postings = connection.execute("SELECT term, tf FROM postings WHERE book_id = ?", (book,)).fetchall()
    connection.close()
    print(f"index: {books} books, {pages * page_size / 1048576:.0f} MB, pages of {page_size} bytes; one book of {len(postings)} postings; {REPEATS} repeats")
    for cache_kb, name in CACHES:
        for order in ("shuffled", "sorted"):
            times, log_sizes = run(source, work, postings, cache_kb, order)
            print(f"{name:14s} {order:9s} median {statistics.median(times):6.0f} ms per book; each: {[round(t) for t in times]}; log after each, MB: {[round(s) for s in log_sizes]}")


if __name__ == "__main__":
    main()
