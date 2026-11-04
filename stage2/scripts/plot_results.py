#!/usr/bin/env python3
"""Builds every chart and summary table of the report from the raw benchmark files.

    python scripts/plot_results.py

Reads results/stage2/integration/*.csv and results/stage2/jmh/all_results.csv, and writes
results/stage2/charts/*.png and results/stage2/summary_tables.md. Averages and standard deviations
are computed across repeated runs; every table row states its client concurrency, replicas and threads.
"""
import csv
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt

import chart_style

STAGE2 = Path(__file__).resolve().parent.parent
RESULTS = STAGE2.parent / "results" / "stage2"
INTEGRATION = RESULTS / "integration"
CHARTS = RESULTS / "charts"
OUTPUT_TABLES = RESULTS / "summary_tables.md"


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def number(row, key):
    value = row.get(key)
    return float(value) if value not in (None, "") else 0.0


def group(rows, keys):
    groups = defaultdict(list)
    for row in rows:
        groups[tuple(row[k] for k in keys)].append(row)
    return groups


def mean_sd(rows, key):
    values = [number(r, key) for r in rows]
    return statistics.mean(values), statistics.stdev(values) if len(values) > 1 else 0.0


def fmt(rows, key, digits=1):
    mean, sd = mean_sd(rows, key)
    return f"{mean:.{digits}f} ± {sd:.{digits}f}"


def table(headers, rows):
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines) + "\n"


# --- pipeline ------------------------------------------------------------------------------------

def pipeline_tables(rows):
    out = ["## Pipeline: ingestion + indexing + publication\n",
           "Complete pipeline. One Search replica, one Control Module, default HTTP threads, -Xmx1g per JVM. "
           "Mean ± standard deviation across repeated runs.\n"]
    keys = ["experiment", "control_workers", "ingestion_replicas", "indexing_replicas", "sqlite_synchronous", "upstream_latency_ms"]
    body = []
    for key, group_rows in group(rows, keys).items():
        experiment, workers, ingestion, indexing, sync, latency = key
        failed = sum(int(number(r, "failed")) for r in group_rows)
        checksums = {r["index_checksum"] for r in group_rows}
        body.append([experiment, workers, ingestion, indexing, sync, latency, len(group_rows), fmt(group_rows, "throughput_books_per_s", 2),
                     fmt(group_rows, "avg_ingest_ms"), fmt(group_rows, "avg_index_ms"), fmt(group_rows, "avg_publish_check_ms"),
                     fmt(group_rows, "avg_coordination_ms"), fmt(group_rows, "avg_queue_wait_ms", 0),
                     fmt(group_rows, "wal_peak_mb", 0), fmt(group_rows, "index_size_MB", 0), failed, len(checksums)])
    out.append(table(["Experiment", "Control workers", "Ingestion replicas", "Indexing replicas", "synchronous", "Upstream delay (ms)", "Runs",
                      "Books/s", "Ingest call (ms)", "Index call (ms)", "Visibility check (ms)", "Coordination (ms/book)", "Queue wait (ms)",
                      "Peak WAL (MB)", "Index + WAL at the end (MB)", "Failed", "Distinct index checksums"], body))
    resources = []
    for key, group_rows in group(rows, keys).items():
        experiment, workers, ingestion, indexing, *_ = key
        resources.append([experiment, workers, ingestion, indexing, fmt(group_rows, "host_cpu_percent_mean"),
                          fmt(group_rows, "ingestion_cpu_percent_mean", 0), fmt(group_rows, "indexing_cpu_percent_mean", 0),
                          fmt(group_rows, "control_cpu_percent_mean", 0), fmt(group_rows, "ingestion_rss_mb_max", 0),
                          fmt(group_rows, "indexing_rss_mb_max", 0), fmt(group_rows, "control_rss_mb_max", 0)])
    out.append("\nResource use. CPU is the mean during the run, where 100% is one logical core; the host total is relative to all its cores.\n")
    out.append(table(["Experiment", "Control workers", "Ingestion replicas", "Indexing replicas", "Host CPU %", "Ingestion CPU %", "Indexing CPU %",
                      "Control CPU %", "Ingestion RSS MB", "Indexing RSS MB", "Control RSS MB"], resources))
    return out


def plot_pipeline(rows):
    chart_style.apply()
    workers_rows = [r for r in rows if r["experiment"] == "workers"]
    grouped = sorted(group(workers_rows, ["control_workers"]).items(), key=lambda item: int(item[0][0]))
    workers = [int(key[0]) for key, _ in grouped]

    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    values = [mean_sd(g, "throughput_books_per_s") for _, g in grouped]
    axis.errorbar(workers, [v[0] for v in values], yerr=[v[1] for v in values], marker="o", color=chart_style.SERIES[0], capsize=3)
    for x, (y, sd) in zip(workers, values):
        axis.annotate(f"{y:.2f}", (x, y + sd), xytext=(0, 6), textcoords="offset points", ha="center", fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xscale("log", base=2)
    axis.set_xticks(workers, workers)
    axis.set_ylim(bottom=0)
    axis.set_xlabel("Control Module worker threads (1 replica of each service)")
    axis.set_ylabel("Books per second")
    axis.set_title("Complete pipeline throughput")
    chart_style.save(figure, CHARTS / "pipeline_throughput_workers.png")

    # Lines on a log axis: stacked, the index call hides all the other steps.
    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    steps = [("avg_index_ms", "Index call (includes waiting for the lock)"), ("avg_ingest_ms", "Ingest call"),
             ("avg_publish_check_ms", "Visibility check"), ("avg_coordination_ms", "Coordination")]
    for i, (key, label) in enumerate(steps):
        series = [mean_sd(g, key)[0] for _, g in grouped]
        axis.plot(workers, series, marker=chart_style.MARKERS[i], color=chart_style.SERIES[i], label=label)
    axis.set_xscale("log", base=2)
    axis.set_yscale("log")
    axis.set_xticks(workers, workers)
    axis.set_xlabel("Control Module worker threads")
    axis.set_ylabel("Mean time per book (ms, log scale)")
    axis.set_title("Where each book's time goes")
    axis.legend(ncols=2, loc="upper center", bbox_to_anchor=(0.5, -0.17), fontsize=8)
    chart_style.save(figure, CHARTS / "pipeline_breakdown.png")

    figure, axis = plt.subplots(figsize=(7.5, 3.6))
    comparison = [r for r in rows if r["experiment"] == "indexing_replicas" or (r["experiment"] == "workers" and r["control_workers"] == "8")]
    replica_groups = sorted(group(comparison, ["indexing_replicas"]).items(), key=lambda item: int(item[0][0]))
    labels = [key[0] for key, _ in replica_groups]
    means = [mean_sd(g, "throughput_books_per_s") for _, g in replica_groups]
    bars = axis.bar(labels, [m[0] for m in means], 0.5, yerr=[m[1] for m in means], color=chart_style.SERIES[0], capsize=3,
                    ecolor=chart_style.TEXT_SECONDARY)
    axis.bar_label(bars, fmt="%.2f", padding=4, fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xlabel("Indexing Service replicas (8 Control workers)")
    axis.set_ylabel("Books per second")
    axis.set_title("Effect of indexing replicas on throughput")
    axis.grid(axis="x", visible=False)
    chart_style.save(figure, CHARTS / "pipeline_indexing_replicas.png")


def growth_rows(samples, step=100):
    """Publication rate over each `step` books, from the samples of the long run."""
    rows = []
    for lower in range(0, int(number(samples[-1], "books_published")), step):
        window = [s for s in samples if lower <= number(s, "books_published") <= lower + step]
        if len(window) > 1 and number(window[-1], "books_published") > number(window[0], "books_published"):
            books = number(window[-1], "books_published") - number(window[0], "books_published")
            rows.append((lower, lower + step, books / (number(window[-1], "t_s") - number(window[0], "t_s")),
                         number(window[-1], "index_plus_wal_mb"), max(number(s, "wal_mb") for s in window)))
    return rows


def growth_table(samples):
    out = ["\n## Publication rate as the index grows\n\nOne run of 1000 books, 8 Control workers, 1 replica of each service.\n"]
    out.append(table(["Books in the index", "Books/s", "Index + WAL at the end (MB)", "Peak WAL in the window (MB)"],
                     [[f"{a}-{b}", f"{rate:.2f}", f"{size:.0f}", f"{wal:.0f}"] for a, b, rate, size, wal in growth_rows(samples)]))
    return out


def plot_growth(samples):
    chart_style.apply()
    rows = growth_rows(samples)
    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    x = [(a + b) / 2 for a, b, *_ in rows]
    y = [row[2] for row in rows]
    axis.plot(x, y, marker="o", color=chart_style.SERIES[0])
    for px, py in zip(x, y):
        axis.annotate(f"{py:.1f}", (px, py), xytext=(0, 7), textcoords="offset points", ha="center", fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_ylim(bottom=0)
    axis.set_xlabel("Books already in the index")
    axis.set_ylabel("Books published per second")
    axis.set_title("Publishing slows down as the index grows")
    chart_style.save(figure, CHARTS / "pipeline_index_growth.png")


# --- search --------------------------------------------------------------------------------------

def search_tables(rows, mixed):
    out = ["\n## Search under concurrent load\n",
           "Closed-loop clients on the same host, against a published index. -Xmx1g per JVM. Mean ± standard deviation across repeated runs.\n"]
    keys = ["experiment", "client_concurrency", "search_replicas", "http_threads", "db_connections_per_replica", "cache_entries"]
    body = []
    for key, group_rows in group(rows, keys).items():
        experiment, clients, replicas, threads, connections, cache = key
        errors = sum(int(number(r, "errors")) for r in group_rows)
        mismatches = sum(int(number(r, "mismatches")) for r in group_rows)
        body.append([experiment, clients, replicas, threads, connections, cache, len(group_rows), fmt(group_rows, "throughput_rps", 0),
                     fmt(group_rows, "latency_mean_ms", 2), fmt(group_rows, "latency_p50_ms", 2), fmt(group_rows, "latency_p95_ms", 2),
                     fmt(group_rows, "latency_p99_ms", 2), fmt(group_rows, "host_cpu_percent_mean"), fmt(group_rows, "search_cpu_percent_mean", 0),
                     fmt(group_rows, "load-tester_cpu_percent_mean", 0), fmt(group_rows, "search_rss_mb_max", 0), errors, mismatches])
    out.append(table(["Experiment", "Clients", "Search replicas", "HTTP threads per replica", "DB connections per replica", "Cache entries", "Runs",
                      "Requests/s", "Mean ms", "p50 ms", "p95 ms", "p99 ms", "Host CPU %", "Search CPU %", "Load generator CPU %", "Search RSS MB",
                      "Errors", "Wrong totals"], body))
    if mixed:
        out.append("\n## Reads and writes on the same datamart\n\nEach side alone, then both, on a 500-book index. 8 clients, cache disabled, "
                   "4 Control workers. Totals change during the run, so they are not asserted.\n")
        rows_mixed = []
        for (mode,), group_rows in group(mixed, ["mode"]).items():
            rows_mixed.append([mode, len(group_rows), fmt(group_rows, "throughput_rps", 0), fmt(group_rows, "latency_p50_ms", 2),
                               fmt(group_rows, "latency_p95_ms", 2), fmt(group_rows, "latency_p99_ms", 2), fmt(group_rows, "books_per_s", 2),
                               fmt(group_rows, "wal_peak_mb", 0), fmt(group_rows, "host_cpu_percent_mean"),
                               sum(int(number(r, "errors")) for r in group_rows), sum(int(number(r, "failed")) for r in group_rows)])
        out.append(table(["Mode", "Runs", "Requests/s", "p50 ms", "p95 ms", "p99 ms", "Books/s published", "Peak WAL (MB)", "Host CPU %",
                          "Search errors", "Books failed"], rows_mixed))
    return out


def plot_search(rows):
    chart_style.apply()
    concurrency = sorted(group([r for r in rows if r["experiment"] == "client_concurrency"], ["client_concurrency"]).items(), key=lambda item: int(item[0][0]))
    clients = [int(key[0]) for key, _ in concurrency]

    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    values = [mean_sd(g, "throughput_rps") for _, g in concurrency]
    axis.errorbar(clients, [v[0] for v in values], yerr=[v[1] for v in values], marker="o", color=chart_style.SERIES[0], capsize=3)
    for x, (y, sd) in zip(clients, values):
        axis.annotate(f"{y:.0f}", (x, y + sd), xytext=(0, 6), textcoords="offset points", ha="center", fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xscale("log", base=2)
    axis.set_xticks(clients, clients)
    axis.set_ylim(bottom=0)
    axis.set_xlabel("Client concurrency (1 Search replica, default threads, cache off)")
    axis.set_ylabel("Requests per second")
    axis.set_title("Search throughput as concurrency grows")
    chart_style.save(figure, CHARTS / "search_throughput_concurrency.png")

    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    for i, (key, label) in enumerate([("latency_p50_ms", "p50"), ("latency_p95_ms", "p95"), ("latency_p99_ms", "p99")]):
        series = [mean_sd(g, key) for _, g in concurrency]
        axis.errorbar(clients, [s[0] for s in series], yerr=[s[1] for s in series], marker=chart_style.MARKERS[i], color=chart_style.SERIES[i], capsize=3, label=label)
        axis.annotate(label, (clients[-1], series[-1][0]), xytext=(8, -3), textcoords="offset points", fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xscale("log", base=2)
    axis.set_yscale("log")
    axis.set_xticks(clients, clients)
    axis.set_xlabel("Client concurrency (1 Search replica, default threads, cache off)")
    axis.set_ylabel("Latency (ms, log scale)")
    axis.set_title("Search latency as concurrency grows, across repeated runs")
    axis.legend(ncols=3, loc="upper center", bbox_to_anchor=(0.5, -0.17))
    chart_style.save(figure, CHARTS / "search_latency_concurrency.png")

    figure, axis = plt.subplots(figsize=(7.5, 3.6))
    threads = sorted(group([r for r in rows if r["experiment"] == "search_threads"], ["http_threads"]).items(), key=lambda item: int(item[0][0]))
    labels = [key[0] for key, _ in threads]
    means = [mean_sd(g, "throughput_rps") for _, g in threads]
    bars = axis.bar(labels, [m[0] for m in means], 0.55, yerr=[m[1] for m in means], color=chart_style.SERIES[0], capsize=3, ecolor=chart_style.TEXT_SECONDARY)
    axis.bar_label(bars, fmt="%.0f", padding=4, fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xlabel("HTTP threads and database connections in the replica (32 clients)")
    axis.set_ylabel("Requests per second")
    axis.set_title("Effect of request threads on one Search replica")
    axis.grid(axis="x", visible=False)
    chart_style.save(figure, CHARTS / "search_threads.png")

    figure, axis = plt.subplots(figsize=(7.5, 3.6))
    replicas = sorted(group([r for r in rows if r["experiment"] == "search_replicas"], ["search_replicas"]).items(), key=lambda item: int(item[0][0]))
    labels = [key[0] for key, _ in replicas]
    means = [mean_sd(g, "throughput_rps") for _, g in replicas]
    bars = axis.bar(labels, [m[0] for m in means], 0.5, yerr=[m[1] for m in means], color=chart_style.SERIES[0], capsize=3, ecolor=chart_style.TEXT_SECONDARY)
    axis.bar_label(bars, fmt="%.0f", padding=4, fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xlabel("Search Service replicas (32 clients, all on the same host)")
    axis.set_ylabel("Requests per second")
    axis.set_title("Effect of Search replicas on one machine")
    axis.grid(axis="x", visible=False)
    chart_style.save(figure, CHARTS / "search_replicas.png")

    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    for i, (key, label) in enumerate([("search_cpu_percent_mean", "Search Service"), ("load-tester_cpu_percent_mean", "Load generator")]):
        series = [mean_sd(g, key)[0] for _, g in concurrency]
        axis.plot(clients, series, marker=chart_style.MARKERS[i], color=chart_style.SERIES[i], label=label)
        axis.annotate(label, (clients[-1], series[-1]), xytext=(-4, 6), textcoords="offset points", ha="right", fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xscale("log", base=2)
    axis.set_xticks(clients, clients)
    axis.set_ylim(bottom=0)
    axis.set_xlabel("Client concurrency")
    axis.set_ylabel("Mean CPU (100% = one logical core)")
    axis.set_title("CPU per process as concurrency grows")
    axis.legend(ncols=2, loc="upper center", bbox_to_anchor=(0.5, -0.17))
    chart_style.save(figure, CHARTS / "search_cpu_concurrency.png")


def plot_timeseries(path, roles, title, target):
    if not path.exists():
        return
    chart_style.apply()
    rows = read_csv(path)
    times = [number(r, "t") for r in rows]
    figure, (cpu_axis, memory_axis) = plt.subplots(2, 1, figsize=(7.5, 5), sharex=True)
    for i, (column, label) in enumerate(roles):
        cpu_axis.plot(times, [number(r, f"{column}.cpu") for r in rows], color=chart_style.SERIES[i], label=label, linewidth=1.5)
        memory_axis.plot(times, [number(r, f"{column}.rss_mb") for r in rows], color=chart_style.SERIES[i], label=label, linewidth=1.5)
    cpu_axis.set_ylabel("CPU (100% = one core)")
    cpu_axis.set_ylim(bottom=0)
    cpu_axis.set_title(title)
    memory_axis.set_ylabel("Resident memory (MB)")
    memory_axis.set_ylim(bottom=0)
    memory_axis.set_xlabel("Seconds since the experiment started")
    memory_axis.legend(ncols=len(roles), loc="upper center", bbox_to_anchor=(0.5, -0.28))
    chart_style.save(figure, target)


# --- JMH -----------------------------------------------------------------------------------------

def jmh_tables(rows):
    out = ["\n## Component benchmarks (JMH)\n",
           "Isolated components, no HTTP. 5 warm-up and 10 measurement iterations of 1 s, 2 forks, -Xmx2G. "
           "Error is JMH's 99.9% confidence half-width.\n"]
    body = []
    for row in rows:
        parameter = row.get("Param: synchronous") or row.get("Param: queryKind") or ""
        body.append([row["Benchmark"].rsplit(".", 2)[-2] + "." + row["Benchmark"].rsplit(".", 1)[-1], parameter, row["Threads"],
                     f"{float(row['Score']):.1f}", f"{float(row['Score Error (99.9%)']):.1f}", row["Unit"]])
    out.append(table(["Benchmark", "Parameter", "Threads", "Score", "± Error", "Unit"], body))
    return out


def plot_jmh(rows):
    chart_style.apply()
    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    tokenize = sorted((r for r in rows if r["Benchmark"].endswith("tokenizeBook")), key=lambda r: int(r["Threads"]))
    threads = [int(r["Threads"]) for r in tokenize]
    throughput = [t * 1e6 / float(r["Score"]) for t, r in zip(threads, tokenize)]
    axis.plot(threads, throughput, marker="o", color=chart_style.SERIES[0], label="Measured")
    axis.plot(threads, [throughput[0] * t for t in threads], linestyle="--", linewidth=1.2, color=chart_style.TEXT_SECONDARY, label="Linear scaling from 1 thread")
    axis.set_xscale("log", base=2)
    axis.set_yscale("log", base=2)
    axis.set_xticks(threads, threads)
    axis.set_xlabel("Threads")
    axis.set_ylabel("Books tokenized per second (log scale)")
    axis.set_title("Tokenizing throughput as threads grow")
    axis.legend(ncols=2, loc="upper center", bbox_to_anchor=(0.5, -0.17))
    chart_style.save(figure, CHARTS / "jmh_tokenizer_scaling.png")

    figure, axis = plt.subplots(figsize=(7.5, 3.8))
    for i, mode in enumerate(["FULL", "NORMAL"]):
        points = sorted((r for r in rows if r.get("Param: synchronous") == mode), key=lambda r: int(r["Threads"]))
        x = [int(r["Threads"]) for r in points]
        y = [t * 1000 / float(r["Score"]) for t, r in zip(x, points)]
        axis.plot(x, y, marker=chart_style.MARKERS[i], color=chart_style.SERIES[i], label=f"synchronous={mode}")
    axis.set_xticks([1, 2, 4], [1, 2, 4])
    axis.set_ylim(bottom=0)
    axis.set_xlabel("Threads competing to publish")
    axis.set_ylabel("Books published per second")
    axis.set_title("Publishing does not scale with threads: one writer at a time")
    axis.legend(ncols=2, loc="upper center", bbox_to_anchor=(0.5, -0.17))
    chart_style.save(figure, CHARTS / "jmh_publish_contention.png")


def main():
    tables = ["# Stage 2 summary tables\n\nGenerated by `scripts/plot_results.py` from the raw files in this folder.\n"]
    jmh_path = RESULTS / "jmh" / "all_results.csv"
    if jmh_path.exists():
        rows = read_csv(jmh_path)
        tables += jmh_tables(rows)
        plot_jmh(rows)
    pipeline_path = INTEGRATION / "pipeline.csv"
    if pipeline_path.exists():
        rows = read_csv(pipeline_path)
        tables += pipeline_tables(rows)
        plot_pipeline(rows)
    search_path = INTEGRATION / "search.csv"
    if search_path.exists():
        rows = read_csv(search_path)
        mixed_path = INTEGRATION / "mixed.csv"
        tables += search_tables(rows, read_csv(mixed_path) if mixed_path.exists() else None)
        plot_search(rows)
    growth_path = INTEGRATION / "index_growth.csv"
    if growth_path.exists():
        samples = read_csv(growth_path)
        tables += growth_table(samples)
        plot_growth(samples)
    plot_timeseries(INTEGRATION / "timeseries_pipeline_workers8.csv",
                    [("ingestion-0", "Ingestion"), ("indexing-0", "Indexing"), ("control", "Control"), ("search-0", "Search")],
                    "Resources over time: pipeline, 8 Control workers", CHARTS / "timeseries_pipeline.png")
    plot_timeseries(INTEGRATION / "timeseries_search_clients32.csv",
                    [("search-0", "Search"), ("load-tester", "Load generator")],
                    "Resources over time: search, 32 clients", CHARTS / "timeseries_search.png")
    OUTPUT_TABLES.write_text("\n".join(tables), encoding="utf-8")
    print(f"Wrote {OUTPUT_TABLES} and charts in {CHARTS}")


if __name__ == "__main__":
    main()
