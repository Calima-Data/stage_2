"""Regenera las dos gráficas de Stage 1 (corrección 10) a partir de una revisión identificada.

Exporta la revisión con `git archive` a un directorio fuera de OneDrive, ejecuta allí las tres
suites de benchmark con sus comandos exactos, guarda los datos crudos en
results/stage1/charts_raw/ y dibuja las gráficas solo a partir de esos ficheros.

Uso:
    python stage1_reproduce_charts.py run --workdir <directorio>   # unos 15 minutos
    python stage1_reproduce_charts.py plot                         # solo redibuja
"""
import argparse
import csv
import io
import json
import re
import shutil
import subprocess
import sys
import tarfile
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RAW = REPO / "results" / "stage1" / "charts_raw"
CHARTS = REPO / "results" / "stage1" / "charts"
# La revisión del Stage 1 entregado. Las escrituras atómicas añadidas después cambian el coste en
# Python y no en Java ni Node.js, de modo que la comparación entre lenguajes exige esta revisión.
DEFAULT_REVISION = "b2224d7"

sys.path.insert(0, str(REPO / "stage2" / "scripts"))

JMH_NAMES = {"timeBased": "time_based", "bookBased": "book_based", "rangeBased": "range_based",
             "sqlitePostings": "sqlite_postings", "monolithicJson": "json_monolithic", "folderHierarchy": "folder_hierarchy"}
PY_NAMES = {"test_time_based": "time_based", "test_book_based": "book_based", "test_range_based": "range_based",
            "test_index_sqlite": "sqlite_postings", "test_index_json": "json_monolithic", "test_index_folders": "folder_hierarchy"}
NODE_NAMES = {"time_based": "time_based", "book_based": "book_based", "range_based": "range_based",
              "index_sqlite": "sqlite_postings", "index_json": "json_monolithic", "index_folders": "folder_hierarchy"}
WRITE = ("time_based", "book_based", "range_based")
UPDATE = ("sqlite_postings", "json_monolithic", "folder_hierarchy")
LANGUAGES = ("Python", "Java", "Node.js")


def shell_run(command, cwd, output=None):
    print("$", " ".join(command), flush=True)
    done = subprocess.run(command, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                          shell=sys.platform == "win32" and command[0] == "mvn")
    if output:
        Path(output).write_text(done.stdout, encoding="utf-8")
    if done.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed:\n{done.stdout[-1500:]}\n{done.stderr[-1500:]}")
    return done.stdout


def run(args):
    revision = subprocess.run(["git", "-C", str(REPO), "rev-parse", args.revision], capture_output=True, text=True, check=True).stdout.strip()
    export = args.workdir.resolve() / f"export_{revision[:7]}"
    shutil.rmtree(export, ignore_errors=True)
    export.mkdir(parents=True)
    archive = subprocess.run(["git", "-C", str(REPO), "archive", revision], capture_output=True, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(export, filter="data")

    RAW.mkdir(parents=True, exist_ok=True)
    # exec.args se divide por espacios, y la ruta del repositorio los tiene: JMH escribe en el
    # directorio de trabajo y el fichero se copia después.
    jmh_csv = export / "java_jmh.csv"
    commands = [
        {"cwd": "worker_python", "command": ["python", "-m", "pytest", "test_benchmark.py", "test_index_benchmark.py", "-q",
                                             "-p", "no:cacheprovider", f"--benchmark-json={RAW / 'python_pytest_benchmark.json'}"]},
        {"cwd": "worker_java", "command": ["mvn", "-q", "-B", "test-compile", "exec:exec",
                                           f"-Dexec.args=-classpath %classpath org.openjdk.jmh.Main -rf csv -rff {jmh_csv}"]},
        {"cwd": "worker_node", "command": ["node", "datalakeBenchmark.js"], "output": RAW / "node_datalake.txt"},
        {"cwd": "worker_node", "command": ["node", "indexBenchmark.js"], "output": RAW / "node_index.txt"},
    ]
    for step in commands:
        shell_run(step["command"], export / step["cwd"], step.get("output"))
    shutil.copy(jmh_csv, RAW / "java_jmh.csv")

    manifest = {
        "revision": revision,
        "export": f"git archive {revision}",
        "commands": [{"cwd": s["cwd"], "command": " ".join(map(str, s["command"])).replace(str(REPO), "<repo>").replace(str(export), "<export>")}
                     for s in commands],
        "parameters": {
            "write_chart": "ingest 1000 synthetic books into an empty datalake; python 2+5 rounds, node 2+5 rounds, JMH per its annotations",
            "update_chart": "add one 500-term book (450 existing, 50 new) to a 5000-term index; python 3+8 rounds, node 3+8 rounds, JMH per its annotations",
        },
        "host": "see results/stage1/environment.json",
    }
    (RAW / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    tidy()
    plot()


def tidy():
    rows = []
    report = json.loads((RAW / "python_pytest_benchmark.json").read_text(encoding="utf-8"))
    for bench in report["benchmarks"]:
        rows.append(("Python", PY_NAMES[bench["name"]], bench["stats"]["mean"] * 1000, bench["stats"]["stddev"] * 1000, "stddev"))
    with open(RAW / "java_jmh.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            name = row["Benchmark"].rsplit(".", 1)[-1]
            factor = {"ms/op": 1, "us/op": 0.001, "s/op": 1000}[row["Unit"]]
            # JMH usa el locale de la JVM, que aquí escribe la coma como separador decimal.
            score = float(row["Score"].replace(",", "."))
            error = float(row["Score Error (99.9%)"].replace(",", "."))
            rows.append(("Java", JMH_NAMES[name], score * factor, error * factor, "99.9% CI half-width"))
    for file_name in ("node_datalake.txt", "node_index.txt"):
        for line in (RAW / file_name).read_text(encoding="utf-8").splitlines():
            match = re.match(r"(\w+)\s+min=.*mean=\s*([\d.]+).*stddev=\s*([\d.]+)", line)
            if match:
                rows.append(("Node.js", NODE_NAMES[match.group(1)], float(match.group(2)), float(match.group(3)), "stddev"))
    with open(RAW / "chart_data.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["language", "structure", "mean_ms", "error_ms", "error_kind"])
        writer.writerows(rows)


def _read_chart_data():
    data = {}
    with open(RAW / "chart_data.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            data[(row["language"], row["structure"])] = (float(row["mean_ms"]), float(row["error_ms"]))
    return data


def plot():
    import chart_style
    import matplotlib.pyplot as plt

    chart_style.apply()
    data = _read_chart_data()

    # Gráfica 1: las diferencias son pequeñas, así que barras con base en cero son honestas.
    figure, axis = plt.subplots(figsize=(7.5, 4))
    width = 0.26
    for i, language in enumerate(LANGUAGES):
        positions = [x + (i - 1) * (width + 0.01) for x in range(len(WRITE))]
        values = [data[(language, s)][0] for s in WRITE]
        errors = [data[(language, s)][1] for s in WRITE]
        bars = axis.bar(positions, values, width, yerr=errors, label=language, color=chart_style.SERIES[i],
                        ecolor=chart_style.TEXT_SECONDARY, capsize=3, error_kw={"linewidth": 1})
        axis.bar_label(bars, fmt="%.0f", padding=4, fontsize=8, color=chart_style.TEXT_SECONDARY)
    axis.set_xticks(range(len(WRITE)), [s.replace("_", "-") for s in WRITE])
    axis.set_ylabel("Mean time to ingest 1000 books (ms)")
    axis.set_title("Datalake write time by layout and language")
    axis.grid(axis="x", visible=False)
    axis.legend(ncols=3, loc="upper center", bbox_to_anchor=(0.5, -0.1))
    chart_style.save(figure, CHARTS / "write_time.png")

    # Gráfica 2: abarca dos órdenes de magnitud, de ahí los puntos sobre eje logarítmico.
    figure, axis = plt.subplots(figsize=(7.5, 3.6))
    for i, language in enumerate(LANGUAGES):
        values = [data[(language, s)][0] for s in UPDATE]
        axis.plot(values, [y + (i - 1) * 0.16 for y in range(len(UPDATE))], linestyle="none", marker=chart_style.MARKERS[i],
                  color=chart_style.SERIES[i], label=language)
        for value, y in zip(values, range(len(UPDATE))):
            axis.annotate(f"{value:.1f}", (value, y + (i - 1) * 0.16), xytext=(7, -3), textcoords="offset points", fontsize=8,
                          color=chart_style.TEXT_SECONDARY)
    axis.set_xscale("log")
    axis.set_yticks(range(len(UPDATE)), [s.replace("_", " ") for s in UPDATE])
    axis.set_ylim(-0.5, len(UPDATE) - 0.5)
    axis.invert_yaxis()
    axis.set_xlabel("Mean time to add one 500-term book (ms, log scale)")
    axis.set_title("Inverted-index update time by structure and language")
    axis.grid(axis="y", visible=False)
    axis.legend(ncols=3, loc="upper center", bbox_to_anchor=(0.5, -0.2))
    chart_style.save(figure, CHARTS / "update_time.png")
    plot_scaling()


def plot_scaling():
    """Corrección 6: una gráfica por operación, con el tamaño inicial en el eje x."""
    import chart_style
    import matplotlib.pyplot as plt

    path = REPO / "results" / "stage1" / "scaling.csv"
    if not path.exists():
        return
    chart_style.apply()
    series = defaultdict(list)
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            series[(row["experiment"], row["structure"])].append((int(row["existing_size"]), float(row["mean_ms"]), float(row["stdev_ms"])))
    panels = [("add_100_books", "Adding 100 books to a datalake of growing size", "Books already in the datalake", "scaling_write.png", WRITE),
              ("add_one_500_term_book", "Adding one 500-term book to an index of growing size", "Terms already in the index", "scaling_update.png", UPDATE)]
    for experiment, title, x_label, file_name, structures in panels:
        figure, axis = plt.subplots(figsize=(7.5, 4))
        for i, structure in enumerate(structures):
            points = sorted(series[(experiment, structure)])
            axis.errorbar([p[0] for p in points], [p[1] for p in points], yerr=[p[2] for p in points], marker=chart_style.MARKERS[i],
                          color=chart_style.SERIES[i], label=structure.replace("_", " "), capsize=3)
            axis.annotate(structure.replace("_", " "), (points[-1][0], points[-1][1]), xytext=(6, 4), textcoords="offset points", fontsize=8,
                          color=chart_style.TEXT_SECONDARY)
        if experiment == "add_one_500_term_book":
            axis.set_xscale("log")
            axis.set_yscale("log")
            axis.set_ylabel("Mean time (ms, log scale)")
        else:
            axis.set_ylim(bottom=0)
            axis.set_ylabel("Mean time (ms)")
        axis.set_xlabel(x_label)
        axis.set_title(title)
        axis.legend(ncols=3, loc="upper center", bbox_to_anchor=(0.5, -0.17))
        chart_style.save(figure, CHARTS / file_name)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("command", choices=["run", "plot"])
    parser.add_argument("--workdir", type=Path)
    parser.add_argument("--revision", default=DEFAULT_REVISION)
    args = parser.parse_args()
    if args.command == "run":
        if args.workdir is None or "onedrive" in str(args.workdir).lower():
            parser.error("run necesita un --workdir fuera de OneDrive")
        run(args)
    else:
        tidy()
        plot()


if __name__ == "__main__":
    main()
