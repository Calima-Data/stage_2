"""Experimentos que completan la evidencia de Stage 1 (correcciones 2 a 9).

Cada subcomando escribe sus datos crudos en results/stage1/. Todo se ejecuta en un
directorio de trabajo fuera de OneDrive, porque la sincronización falsearía las medidas de
disco.

Uso:
    python stage1_experiments.py all --workdir <directorio>
    python stage1_experiments.py discovery --workdir <directorio>

Subcomandos: environment, discovery, storage, queries, footprint, scaling, recovery,
consistency.
"""
import argparse
import csv
import hashlib
import json
import math
import os
import platform
import random
import re
import shutil
import sqlite3
import statistics
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
RESULTS = REPO / "results" / "stage1"
QUERIES_FILE = REPO / "config" / "stage1_queries.json"
CORPUS_FILE = REPO / "config" / "stage1_corpus_ids.txt"
CLUSTER_BYTES = 4096  # tamaño de clúster NTFS del host (ver environment.json)
SEED = 20261104


def write_csv(name, rows):
    RESULTS.mkdir(parents=True, exist_ok=True)
    with open(RESULTS / name, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def write_json(name, data):
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / name).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def revision():
    head = subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain", "--", "worker_python", "worker_java",
                            "worker_node", "config"], capture_output=True, text=True).stdout.strip()
    return {"commit": head, "dirty": bool(dirty)}


def summarize(samples_ms):
    return {
        "rounds": len(samples_ms),
        "mean_ms": round(statistics.mean(samples_ms), 4),
        "stdev_ms": round(statistics.stdev(samples_ms), 4) if len(samples_ms) > 1 else 0.0,
        "min_ms": round(min(samples_ms), 4),
        "max_ms": round(max(samples_ms), 4),
    }


def timed_ms(fn):
    start = time.perf_counter()
    result = fn()
    return (time.perf_counter() - start) * 1000, result


# --- entorno (corrección 9) ----------------------------------------------------------------

def _command_version(*command):
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=120, shell=os.name == "nt")
        lines = (done.stdout + done.stderr).strip().splitlines()
        return lines[:3]
    except (OSError, subprocess.SubprocessError) as error:
        return [f"unavailable: {error}"]


def _powershell_json(script):
    done = subprocess.run(["powershell", "-NoProfile", "-Command", f"{script} | ConvertTo-Json"],
                          capture_output=True, text=True, timeout=120)
    return json.loads(done.stdout) if done.stdout.strip() else None


def cmd_environment(args):
    import importlib.metadata as metadata
    info = {
        "revision": revision(),
        "os": platform.platform(),
        "python": sys.version,
        "python_sqlite_library": sqlite3.sqlite_version,
        "python_packages": {name: metadata.version(name) for name in ("requests", "pytest", "pytest-benchmark", "psutil")},
        "java_runtime": _command_version("java", "-version"),
        "maven": _command_version("mvn", "-version"),
        "node": _command_version("node", "--version"),
        "worker_java_target": "maven.compiler.release=11 (the bytecode target, not the JVM that runs it)",
        "worker_java_libraries": {"jmh": "1.35", "sqlite-jdbc": "3.46.1.3"},
        "workdir": str(args.workdir),
    }
    if os.name == "nt":
        info["cpu"] = _powershell_json("Get-CimInstance Win32_Processor | Select Name,NumberOfCores,NumberOfLogicalProcessors,MaxClockSpeed")
        info["ram_bytes"] = _powershell_json("(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory")
        info["storage"] = _powershell_json("Get-PhysicalDisk | Select FriendlyName,@{n='MediaType';e={$_.MediaType.ToString()}},@{n='BusType';e={$_.BusType.ToString()}},Size")
        info["filesystem"] = _powershell_json("Get-Volume -DriveLetter C | Select FileSystem,AllocationUnitSize,Size")
    write_json("environment.json", info)
    print(json.dumps(info, indent=2)[:800])


# --- datos sintéticos -------------------------------------------------------------------------

HEADER = "Title: Benchmark Test\nAuthor: Data Team\nLanguage: English"
BODY = "Small simulated text to focus purely on filesystem I/O overhead. " * 20
DAYS, HOURS = 7, 24


def time_dir(root, book_id):
    """Los libros sintéticos se reparten en 7 días x 24 horas (ingesta durante una semana)."""
    slot = book_id % (DAYS * HOURS)
    return root / f"2026010{slot // HOURS + 1}" / f"{slot % HOURS:02d}"


def layout_paths(layout, root, book_id):
    if layout == "time_based":
        folder = time_dir(root, book_id)
        return folder / f"{book_id}.header.txt", folder / f"{book_id}.body.txt"
    if layout == "book_based":
        folder = root / "books" / str(book_id)
        return folder / "header.txt", folder / "body.txt"
    lower = ((book_id - 1) // 1000) * 1000 + 1
    folder = root / f"batch_{lower}_{lower + 999}"
    return folder / f"{book_id}.header.txt", folder / f"{book_id}.body.txt"


def seed_layout(layout, root, book_ids):
    for book_id in book_ids:
        header, body = layout_paths(layout, root, book_id)
        header.parent.mkdir(parents=True, exist_ok=True)
        header.write_text(HEADER, encoding="utf-8")
        body.write_text(BODY, encoding="utf-8")


def has_book(layout, root, book_id):
    """Comprueba si un libro está, usando solo la propia estructura (sin el fichero de control)."""
    if layout == "book_based":
        return (root / "books" / str(book_id) / "body.txt").exists()
    if layout == "range_based":
        return layout_paths(layout, root, book_id)[1].exists()
    # time-based: el ID no determina la carpeta, hay que recorrer las horas
    for day in root.iterdir():
        for hour in day.iterdir():
            if (hour / f"{book_id}.body.txt").exists():
                return True
    return False


def list_ids(layout, root):
    """Todos los libros completos de la estructura, recorriéndola."""
    if layout == "book_based":
        return {int(d.name) for d in (root / "books").iterdir() if (d / "body.txt").exists()}
    pattern = "*/*/*.body.txt" if layout == "time_based" else "*/*.body.txt"
    return {int(p.name.split(".")[0]) for p in root.glob(pattern)}


LAYOUTS = ("time_based", "book_based", "range_based")


# --- búsqueda fallida y descubrimiento de libros nuevos (corrección 2) ---------------------------

def cmd_discovery(args):
    known = list(range(1, 1001))
    new_books = list(range(1001, 1011))
    missing = list(range(900001, 900101))
    rows, checks = [], []
    for layout in LAYOUTS:
        root = args.workdir / "discovery" / layout
        shutil.rmtree(root, ignore_errors=True)
        seed_layout(layout, root, known)

        missing_samples, hit_samples, listing_samples, discovery_samples = [], [], [], []
        for _ in range(args.rounds):
            elapsed, answers = timed_ms(lambda: [has_book(layout, root, b) for b in missing])
            assert not any(answers)
            missing_samples.append(elapsed / len(missing))
            elapsed, answers = timed_ms(lambda: [has_book(layout, root, b) for b in known[:100]])
            assert all(answers)
            hit_samples.append(elapsed / 100)

        seed_layout(layout, root, new_books)
        discovered = []
        for _ in range(args.rounds):
            elapsed, found = timed_ms(lambda: list_ids(layout, root) - set(known))
            discovery_samples.append(elapsed)
            discovered = sorted(found)
        checks.append({"layout": layout, "books_in_layout": len(known) + len(new_books),
                       "expected_new_ids": new_books, "discovered_ids": discovered,
                       "match": discovered == new_books})
        for operation, samples in (("lookup_missing_per_book", missing_samples), ("lookup_present_per_book", hit_samples),
                                   ("discover_new_books_full_scan", discovery_samples)):
            rows.append({"layout": layout, "operation": operation, "books": 1010, **summarize(samples)})
        shutil.rmtree(root, ignore_errors=True)
    write_csv("discovery.csv", rows)
    write_json("discovery_checks.json", {"revision": revision(), "checks": checks,
                                         "missing_ids": f"{missing[0]}..{missing[-1]}"})
    print("discovery: all match =", all(c["match"] for c in checks))


# --- tamaños en disco (correcciones 3 y 5) --------------------------------------------------

def tree_stats(path):
    files = dirs = logical = allocated = 0
    if path.is_file():
        size = path.stat().st_size
        return {"files": 1, "dirs": 0, "logical_bytes": size,
                "allocated_bytes_est": math.ceil(size / CLUSTER_BYTES) * CLUSTER_BYTES}
    for entry in path.rglob("*"):
        if entry.is_dir():
            dirs += 1
        else:
            files += 1
            size = entry.stat().st_size
            logical += size
            allocated += math.ceil(size / CLUSTER_BYTES) * CLUSTER_BYTES
    return {"files": files, "dirs": dirs, "logical_bytes": logical, "allocated_bytes_est": allocated}


class _CorpusHandler(BaseHTTPRequestHandler):
    directory = None

    def do_GET(self):
        path = self.directory / f"{self.path.strip('/')}.txt"
        if not path.exists():
            self.send_error(404)
            return
        payload = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *a):
        pass


class CorpusServer:
    """Sirve una copia local del corpus: los reintentos no vuelven a cargar a Project Gutenberg."""

    def __enter__(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _CorpusHandler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return f"http://127.0.0.1:{self.server.server_address[1]}/{{id}}"

    def __exit__(self, *exc):
        self.server.shutdown()


def corpus_ids():
    return [int(line.split("#")[0]) for line in CORPUS_FILE.read_text().splitlines() if line.split("#")[0].strip()]


def fetch_corpus(workdir):
    """Descarga una vez cada libro real del corpus declarado, con una pausa de cortesía."""
    import requests
    cache = workdir / "corpus_cache"
    cache.mkdir(parents=True, exist_ok=True)
    for book_id in corpus_ids():
        target = cache / f"{book_id}.txt"
        if not target.exists():
            url = f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt"
            response = requests.get(url, timeout=60, headers={"User-Agent": "stage1-experiments"})
            response.raise_for_status()
            target.write_bytes(response.content)
            time.sleep(2)
    _CorpusHandler.directory = cache
    return cache


def run_pipeline(base, url, *argv, crash=None, script="coordinator.py", fake_now=None):
    env = dict(os.environ, STAGE1_BASE_DIR=str(base), GUTENBERG_URL_TEMPLATE=url, PYTHONIOENCODING="utf-8")
    env.pop("STAGE1_CRASH_POINT", None)
    if crash:
        env["STAGE1_CRASH_POINT"] = crash
    if fake_now:
        env["STAGE1_FAKE_NOW"] = fake_now
    return subprocess.run([sys.executable, str(HERE / script), *map(str, argv)], env=env, capture_output=True,
                          text=True, encoding="utf-8")


def clean_pipeline_base(workdir, url, name="pipeline"):
    base = workdir / name
    shutil.rmtree(base, ignore_errors=True)
    base.mkdir(parents=True)
    done = run_pipeline(base, url, *corpus_ids())
    if done.returncode != 0:
        raise RuntimeError(done.stderr)
    return base


def cmd_storage(args):
    fetch_corpus(args.workdir)
    rows = []
    with CorpusServer() as url:
        base = clean_pipeline_base(args.workdir, url)
    control = tree_stats(base / "control" / "downloaded_books.txt")
    book_count = len(corpus_ids())
    for layout in LAYOUTS:
        stats = tree_stats(base / "datalake" / layout)
        rows.append({"corpus": f"real_{book_count}_books", "layout": layout, **stats,
                     "aux_files": control["files"], "aux_bytes": control["logical_bytes"]})
    for layout in LAYOUTS:
        root = args.workdir / "storage_synth" / layout
        shutil.rmtree(root, ignore_errors=True)
        seed_layout(layout, root, range(1, 1001))
        rows.append({"corpus": "synthetic_1000_books", "layout": layout, **tree_stats(root),
                     "aux_files": 0, "aux_bytes": 0})
        shutil.rmtree(root, ignore_errors=True)
    for row in rows:
        row["overhead_bytes_est"] = row["allocated_bytes_est"] - row["logical_bytes"]
    write_csv("storage_layouts.csv", rows)
    print("storage: ok")


# --- consultas sobre las tres estructuras (corrección 4) y comprobaciones (corrección 8) ---------

def ground_truth(base):
    """Respuesta esperada calculada de forma independiente, directamente de los cuerpos."""
    truth = {}
    for body in (base / "datalake" / "book_based" / "books").glob("*/body.txt"):
        book_id = int(body.parent.name)
        words = re.split(rb"[^a-z0-9]+", body.read_text(encoding="utf-8").lower().encode("ascii", "replace"))
        for word in set(words):
            if word:
                truth.setdefault(word.decode(), []).append(book_id)
    return {term: sorted(ids) for term, ids in truth.items()}


def _lookup_module(base):
    os.environ["STAGE1_BASE_DIR"] = str(base)
    for name in ("pipeline_common", "lookup"):
        sys.modules.pop(name, None)
    sys.path.insert(0, str(HERE))
    import lookup
    return lookup


def cmd_queries(args):
    fetch_corpus(args.workdir)
    with CorpusServer() as url:
        base = clean_pipeline_base(args.workdir, url)
    lookup = _lookup_module(base)
    truth = ground_truth(base)
    terms = json.loads(QUERIES_FILE.read_text(encoding="utf-8"))["terms"]
    with open(lookup.INDEX_JSON_PATH, encoding="utf-8") as f:
        loaded_index = json.load(f)
    variants = {
        "json_reload_per_query": lookup.lookup_json,
        "json_loaded_in_memory": lambda t: sorted(loaded_index.get(t, [])),
        "folder": lookup.lookup_folder,
        "sqlite": lookup.lookup_sqlite,
    }
    rows, answers = [], []
    for term in terms:
        expected = truth.get(term, [])
        entry = {"term": term, "expected_ids": expected, "answers": {}}
        for name, fn in variants.items():
            repeats = args.rounds if name == "json_reload_per_query" else args.rounds * 20
            samples = []
            for _ in range(repeats):
                elapsed, answer = timed_ms(lambda: fn(term))
                samples.append(elapsed)
            entry["answers"][name] = answer
            rows.append({"structure": name, "term": term, "matches": len(answer), **summarize(samples)})
        entry["all_match_expected"] = all(a == expected for a in entry["answers"].values())
        answers.append(entry)
    write_csv("query_latency.csv", rows)
    write_json("query_answers.json", {"revision": revision(), "corpus_ids": corpus_ids(), "queries": answers})
    print("queries: all match =", all(a["all_match_expected"] for a in answers))


def _state_digest(base):
    """Huella de todo el estado de salida: metadatos, postings de las tres estructuras."""
    conn = sqlite3.connect(base / "datamarts" / "inverted_index.db")
    digest = {"books": hashlib.sha256(repr(sorted(conn.execute("select * from books"))).encode()).hexdigest(),
              "sqlite_postings": hashlib.sha256(repr(sorted(conn.execute("select term, book_id from postings"))).encode()).hexdigest()}
    conn.close()
    digest["json"] = hashlib.sha256(_canonical_json(base).encode()).hexdigest()
    digest["folder"] = hashlib.sha256(_canonical_folder(base).encode()).hexdigest()
    return digest


def _json_map(base):
    return json.loads((base / "datamarts" / "inverted_index.json").read_text(encoding="utf-8"))


def _folder_map(base):
    folder = base / "datamarts" / "inverted_index_folder"
    return {p.stem: sorted(int(x) for x in p.read_text().split()) for p in folder.glob("*/*.txt")}


def _sqlite_map(base):
    conn = sqlite3.connect(base / "datamarts" / "inverted_index.db")
    mapping = {}
    for term, book_id in conn.execute("select term, book_id from postings order by term, book_id"):
        mapping.setdefault(term, []).append(book_id)
    conn.close()
    return mapping


def _canonical_json(base):
    return json.dumps(_json_map(base), sort_keys=True)


def _canonical_folder(base):
    return json.dumps(_folder_map(base), sort_keys=True)


def _header_field(text, name):
    match = re.search(rf"^{name}:\s*(.*)$", text, re.MULTILINE)
    return match.group(1).strip() if match else "Desconocido"


def cmd_consistency(args):
    fetch_corpus(args.workdir)
    with CorpusServer() as url:
        base = clean_pipeline_base(args.workdir, url)
        truth = ground_truth(base)
        json_map, folder_map, sqlite_map = _json_map(base), _folder_map(base), _sqlite_map(base)

        conn = sqlite3.connect(base / "datamarts" / "inverted_index.db")
        rows = {r[0]: r[1:] for r in conn.execute("select book_id, title, author, language from books")}
        conn.close()
        metadata_issues = []
        for book_id in corpus_ids():
            header = (base / "datalake" / "book_based" / "books" / str(book_id) / "header.txt").read_text(encoding="utf-8")
            expected = tuple(_header_field(header, field) for field in ("Title", "Author", "Language"))
            if rows.get(book_id) != expected:
                metadata_issues.append({"book_id": book_id, "expected": expected, "stored": rows.get(book_id)})

        first = _state_digest(base)
        run_pipeline(base, url, *corpus_ids())
        after_rerun = _state_digest(base)
        for name in ("indexed_books.txt", "indexed_books_sqlite.txt", "indexed_books_folder.txt"):
            (base / "control" / name).unlink()
        done = run_pipeline(base, url, *corpus_ids())
        if done.returncode != 0:
            raise RuntimeError(done.stderr)
        after_cleared_tracking = _state_digest(base)
        downloaded = (base / "control" / "downloaded_books.txt").read_text().split()

    report = {
        "revision": revision(),
        "corpus_ids": corpus_ids(),
        "books_in_metadata": len(rows),
        "metadata_issues": metadata_issues,
        "distinct_terms": {"json": len(json_map), "folder": len(folder_map), "sqlite": len(sqlite_map), "ground_truth": len(truth)},
        "postings_total": {"json": sum(map(len, json_map.values())), "folder": sum(map(len, folder_map.values())),
                           "sqlite": sum(map(len, sqlite_map.values())), "ground_truth": sum(map(len, truth.values()))},
        "json_equals_folder": json_map == folder_map,
        "json_equals_sqlite": json_map == sqlite_map,
        "all_equal_ground_truth": json_map == truth,
        "digest_first_run": first,
        "rerun_preserves_state": after_rerun == first,
        "reindex_after_clearing_tracking_preserves_state": after_cleared_tracking == first,
        "downloaded_control_has_no_duplicates": len(downloaded) == len(set(downloaded)),
    }
    write_json("consistency.json", report)
    print("consistency:", {k: report[k] for k in ("json_equals_folder", "json_equals_sqlite", "all_equal_ground_truth",
                                                  "rerun_preserves_state", "reindex_after_clearing_tracking_preserves_state")})


# --- recuperación tras una interrupción (corrección 7) --------------------------------------------

def _layout_inventory(base):
    """Para cada estructura: IDs con par completo, e IDs con ficheros repetidos o sueltos."""
    root = base / "datalake"
    report = {}
    for layout in LAYOUTS:
        counts = {}
        layout_root = root / layout
        if not layout_root.exists():
            report[layout] = {"complete_ids": [], "duplicates_or_orphans": []}
            continue
        for path in layout_root.rglob("*.txt"):
            if layout == "book_based":
                book_id, kind = int(path.parent.name), path.name.split(".")[0]
            else:
                book_id, kind = int(path.name.split(".")[0]), path.name.split(".")[1]
            counts.setdefault(book_id, {"header": 0, "body": 0})[kind] += 1
        report[layout] = {
            "complete_ids": sorted(b for b, c in counts.items() if c == {"header": 1, "body": 1}),
            "duplicates_or_orphans": sorted(b for b, c in counts.items() if c != {"header": 1, "body": 1}),
        }
    return report


def cmd_recovery(args):
    fetch_corpus(args.workdir)
    book_ids = corpus_ids()
    rows = []
    with CorpusServer() as url:
        for point in ("ingest_after_header", "ingest_before_mark"):
            base = args.workdir / "recovery" / point
            shutil.rmtree(base, ignore_errors=True)
            base.mkdir(parents=True)
            crashed = run_pipeline(base, url, *book_ids, crash=point, script="datalake.py")
            control_file = base / "control" / "downloaded_books.txt"
            marked_after_crash = control_file.read_text().split() if control_file.exists() else []
            inventory_after_crash = _layout_inventory(base)
            # se reanuda una hora "distinta": es el peor caso para la estructura time-based
            resumed = run_pipeline(base, url, *book_ids, script="datalake.py", fake_now="2031-01-02T23:00:00")
            marked = sorted(int(x) for x in control_file.read_text().split())
            inventory = _layout_inventory(base)
            rows.append({
                "crash_point": point,
                "crash_exit_code": crashed.returncode,
                "resume_exit_code": resumed.returncode,
                "books_requested": len(book_ids),
                "marked_after_crash": len(marked_after_crash),
                "partial_after_crash": sum(len(inventory_after_crash[l]["duplicates_or_orphans"]) for l in LAYOUTS),
                "marked_after_resume": len(marked),
                "control_duplicates": len(marked) - len(set(marked)),
                **{f"{l}_complete_pairs": len(inventory[l]["complete_ids"]) for l in LAYOUTS},
                **{f"{l}_duplicates_or_orphans": len(inventory[l]["duplicates_or_orphans"]) for l in LAYOUTS},
                "final_ids_match": all(inventory[l]["complete_ids"] == sorted(book_ids) for l in LAYOUTS) and marked == sorted(book_ids),
            })
    write_csv("recovery.csv", rows)
    print("recovery:", [(r["crash_point"], r["final_ids_match"]) for r in rows])


# --- escalado de escrituras y actualizaciones (corrección 6) --------------------------------------

BATCH = 100


def cmd_scaling(args):
    sys.path.insert(0, str(HERE))
    import datalake

    rows = []
    for size in (0, 1000, 5000, 10000):
        batch = list(range(size + 1, size + 1 + BATCH))
        for layout, save in (("time_based", datalake.save_time_based), ("book_based", datalake.save_book_based),
                             ("range_based", datalake.save_range_based)):
            root = args.workdir / "scaling" / "lake"
            shutil.rmtree(root, ignore_errors=True)
            seed_layout(layout, root / layout, range(1, size + 1))
            datalake.DATALAKE_DIR = root
            samples = []
            for round_number in range(args.rounds + 1):
                elapsed, _ = timed_ms(lambda: [save(b, HEADER, BODY) for b in batch])
                if round_number > 0:  # la primera ronda es de calentamiento
                    samples.append(elapsed)
                _remove_batch(layout, root / layout, batch)
            rows.append({"experiment": "add_100_books", "structure": layout, "existing_size": size, **summarize(samples)})
            print(f"scaling write {layout} {size}: {rows[-1]['mean_ms']} ms", flush=True)
            shutil.rmtree(root, ignore_errors=True)

    for vocabulary_size in (5000, 20000, 100000):
        rows.extend(_scale_index(args, vocabulary_size))
    write_csv("scaling.csv", rows)
    write_json("scaling_params.json", {"revision": revision(), "seed": SEED, "added_books_per_round": BATCH,
                                       "book_terms": {"existing": 450, "new": 50}, "warmup_rounds": 1, "rounds": args.rounds})


def _remove_batch(layout, root, batch):
    if layout == "book_based":
        for book_id in batch:
            shutil.rmtree(root / "books" / str(book_id), ignore_errors=True)
        return
    now_dirs = list(root.glob("*/*")) if layout == "time_based" else list(root.glob("*"))
    wanted = {f"{b}.header.txt" for b in batch} | {f"{b}.body.txt" for b in batch}
    for folder in now_dirs:
        if folder.is_dir():
            for name in wanted:
                target = folder / name
                if target.exists():
                    target.unlink()


def _vocabulary(size):
    rng = random.Random(SEED)
    letters = "abcdefghijklmnopqrstuvwxyz"
    return [f"{rng.choice(letters)}{rng.choice(letters)}word{i}" for i in range(size)]


def _book_terms(vocabulary):
    rng = random.Random(SEED + 1)
    return set(rng.sample(vocabulary, 450)) | {f"newterm{i}" for i in range(50)}


def _seed_index(root, vocabulary):
    """Índice inicial en las tres estructuras: cada término aparece en el libro 1."""
    shutil.rmtree(root, ignore_errors=True)
    (root / "folder").mkdir(parents=True)
    for term in vocabulary:
        letter = root / "folder" / term[0].upper()
        letter.mkdir(exist_ok=True)
        (letter / f"{term}.txt").write_text("1\n", encoding="ascii")
    (root / "index.json").write_text(json.dumps({t: [1] for t in vocabulary}, indent=2), encoding="utf-8")
    conn = sqlite3.connect(root / "index.db")
    conn.execute("CREATE TABLE postings (term TEXT, book_id INTEGER, PRIMARY KEY (term, book_id))")
    conn.executemany("INSERT INTO postings VALUES (?, 1)", [(t,) for t in vocabulary])
    conn.commit()
    conn.close()


def _scale_index(args, vocabulary_size):
    import datamart_index
    import datamart_index_folder

    root = args.workdir / "scaling" / f"index_{vocabulary_size}"
    vocabulary = _vocabulary(vocabulary_size)
    terms = _book_terms(vocabulary)
    _seed_index(root, vocabulary)
    json_path, db_path, folder = root / "index.json", root / "index.db", root / "folder"
    baseline_json, baseline_db = root / "baseline.json", root / "baseline.db"
    shutil.copy(json_path, baseline_json)
    shutil.copy(db_path, baseline_db)
    datamart_index.INDEX_JSON_PATH = json_path
    datamart_index_folder.INDEX_FOLDER_DIR = folder

    def update_json():
        index = datamart_index.load_index()
        datamart_index.add_book(index, 9999, terms)
        datamart_index.save_index(index)

    def update_sqlite():
        conn = sqlite3.connect(db_path)
        conn.executemany("INSERT OR IGNORE INTO postings VALUES (?, ?)", [(t, 9999) for t in terms])
        conn.commit()
        conn.close()

    def restore_folder():
        for term in terms:
            target = datamart_index_folder.term_file(term)
            if term.startswith("newterm"):
                target.unlink(missing_ok=True)
            else:
                target.write_text("1\n", encoding="ascii")

    plans = {
        "json_monolithic": (update_json, lambda: shutil.copy(baseline_json, json_path)),
        "sqlite_postings": (update_sqlite, lambda: shutil.copy(baseline_db, db_path)),
        "folder_hierarchy": (lambda: datamart_index_folder.add_book(9999, terms), restore_folder),
    }
    rows = []
    for structure, (update, restore) in plans.items():
        samples = []
        for round_number in range(args.rounds + 1):
            elapsed, _ = timed_ms(update)
            if round_number > 0:
                samples.append(elapsed)
            restore()
        rows.append({"experiment": "add_one_500_term_book", "structure": structure, "existing_size": vocabulary_size,
                     **summarize(samples)})
        print(f"scaling index {structure} {vocabulary_size}: {rows[-1]['mean_ms']} ms", flush=True)
    shutil.rmtree(root, ignore_errors=True)
    return rows


# --- RAM y disco de cada índice (corrección 5) ------------------------------------------------------

def footprint_child(structure, root, base_lookup):
    """Proceso aislado: RSS antes y después de responder una consulta y de aplicar una actualización."""
    import psutil
    process = psutil.Process()

    def rss():
        return process.memory_info().rss

    terms = json.loads(sys.argv[sys.argv.index("--terms") + 1])
    new_terms = [f"footprintterm{i}" for i in range(50)]
    before = rss()
    root = Path(root)
    if structure == "json":
        with open(root / "index.json", encoding="utf-8") as f:
            index = json.load(f)
        answer = [sorted(index.get(t, [])) for t in terms]
        after_query = rss()
        for term in new_terms:
            index.setdefault(term, []).append(999999)
        (root / "index.json.tmp").write_text(json.dumps(index, indent=2), encoding="utf-8")
    elif structure == "sqlite":
        conn = sqlite3.connect(root / "index.db")
        answer = [[r[0] for r in conn.execute("select book_id from postings where term=? order by book_id", (t,))] for t in terms]
        after_query = rss()
        conn.executemany("INSERT OR IGNORE INTO postings VALUES (?, 999999)", [(t,) for t in new_terms])
        conn.rollback()
        conn.close()
    else:
        answer = []
        for term in terms:
            path = root / "folder" / term[0].upper() / f"{term}.txt"
            answer.append(sorted(int(x) for x in path.read_text().split()) if path.exists() else [])
        after_query = rss()
        for term in new_terms:
            path = root / "folder" / term[0].upper() / f"{term}.txt"
            path.write_text("999999\n", encoding="ascii")
            path.unlink()
    info = process.memory_info()
    print(json.dumps({"rss_before": before, "rss_after_query": after_query, "rss_after_update": rss(),
                      "peak_wset": getattr(info, "peak_wset", None), "answers": len(answer)}))


def cmd_footprint(args):
    fetch_corpus(args.workdir)
    with CorpusServer() as url:
        base = clean_pipeline_base(args.workdir, url)
    rows = []
    real = args.workdir / "footprint" / "real"
    shutil.rmtree(real, ignore_errors=True)
    real.mkdir(parents=True)
    shutil.copy(base / "datamarts" / "inverted_index.json", real / "index.json")
    shutil.copy(base / "datamarts" / "inverted_index.db", real / "index.db")
    shutil.copytree(base / "datamarts" / "inverted_index_folder", real / "folder")

    synthetic = args.workdir / "footprint" / "synthetic_100000"
    vocabulary = _vocabulary(100000)
    _seed_index(synthetic, vocabulary)

    real_terms = json.loads(QUERIES_FILE.read_text(encoding="utf-8"))["terms"]
    datasets = [("real_corpus", real, real_terms, len(_json_map(base))),
                ("synthetic_100000_terms", synthetic, vocabulary[:10], 100000)]
    paths = {"json": "index.json", "sqlite": "index.db", "folder": "folder"}
    for dataset, root, terms, term_count in datasets:
        for structure, relative in paths.items():
            for repetition in range(3):
                done = subprocess.run([sys.executable, str(Path(__file__).resolve()), "_footprint_child", structure, str(root),
                                       "--terms", json.dumps(terms)], capture_output=True, text=True, check=True)
                memory = json.loads(done.stdout)
                rows.append({"dataset": dataset, "terms": term_count, "structure": structure, "repetition": repetition,
                             **{k: v for k, v in tree_stats(root / relative).items()},
                             "rss_before_bytes": memory["rss_before"], "rss_after_query_bytes": memory["rss_after_query"],
                             "rss_after_update_bytes": memory["rss_after_update"], "peak_working_set_bytes": memory["peak_wset"]})
            print(f"footprint {dataset} {structure}: rss delta {rows[-1]['rss_after_query_bytes'] - rows[-1]['rss_before_bytes']}", flush=True)
    write_csv("index_footprint.csv", rows)
    shutil.rmtree(args.workdir / "footprint", ignore_errors=True)


COMMANDS = {
    "environment": cmd_environment, "discovery": cmd_discovery, "storage": cmd_storage, "queries": cmd_queries,
    "consistency": cmd_consistency, "recovery": cmd_recovery, "footprint": cmd_footprint, "scaling": cmd_scaling,
}


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "_footprint_child":
        footprint_child(sys.argv[2], sys.argv[3], None)
        return
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("command", choices=[*COMMANDS, "all"])
    parser.add_argument("--workdir", type=Path, required=True, help="directorio de trabajo, fuera de OneDrive")
    parser.add_argument("--rounds", type=int, default=5)
    args = parser.parse_args()
    args.workdir = args.workdir.resolve()
    if "onedrive" in str(args.workdir).lower():
        parser.error("--workdir debe quedar fuera de OneDrive")
    args.workdir.mkdir(parents=True, exist_ok=True)
    for name, command in COMMANDS.items():
        if args.command in (name, "all"):
            print(f"== {name}", flush=True)
            command(args)


if __name__ == "__main__":
    main()
