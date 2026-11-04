"""Comprobaciones funcionales del pipeline de Stage 1 (correcciones 7, 8, 11 y 12).

Cada test lanza los scripts reales en un subproceso sobre un directorio temporal
(STAGE1_BASE_DIR) y con una "Gutenberg" local (GUTENBERG_URL_TEMPLATE). Los fallos se
provocan con STAGE1_CRASH_POINT, que termina el proceso con os._exit, sin limpieza, en el
punto indicado: es lo más parecido a un corte real que se puede repetir en un test.
"""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
CRASH_EXIT_CODE = 137

BOOKS = {
    1: ("Alpha Book", "Ann Author", "English", "whale ship sea whale"),
    2: ("Beta Book", "Bob Writer", "English", "ship harbour"),
    3: ("Gamma Libro", "Carla Autora", "Spanish", "sea mar"),
}


def gutenberg_text(book_id):
    title, author, language, body = BOOKS[book_id]
    return (
        f"The Project Gutenberg eBook of {title}\n\nTitle: {title}\n\nAuthor: {author}\n\n"
        f"Language: {language}\n\n*** START OF THE PROJECT GUTENBERG EBOOK {title.upper()} ***\n"
        f"{body}\n*** END OF THE PROJECT GUTENBERG EBOOK {title.upper()} ***\nfooter words\n"
    )


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        book_id = int(self.path.strip("/"))
        if book_id not in BOOKS:
            self.send_error(404)
            return
        payload = gutenberg_text(book_id).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def gutenberg_url():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_address[1]}/{{id}}"
    server.shutdown()


@pytest.fixture
def run(tmp_path, gutenberg_url):
    def _run(script, *args, crash=None, fake_hour=None):
        env = dict(os.environ, STAGE1_BASE_DIR=str(tmp_path), GUTENBERG_URL_TEMPLATE=gutenberg_url,
                   PYTHONIOENCODING="utf-8")
        env.pop("STAGE1_CRASH_POINT", None)
        if crash:
            env["STAGE1_CRASH_POINT"] = crash
        if fake_hour:
            env["STAGE1_FAKE_NOW"] = fake_hour
        return subprocess.run([sys.executable, str(HERE / script), *map(str, args)], env=env,
                              capture_output=True, text=True, encoding="utf-8", timeout=120)
    return _run


def control_ids(base, name):
    path = base / "control" / name
    return [int(line) for line in path.read_text().split()] if path.exists() else []


def time_based_files(base, book_id):
    return sorted(p.name for p in (base / "datalake" / "time_based").rglob(f"{book_id}.*"))


# --- corrección 11: una búsqueda término -> postings -------------------------------------

def test_lookup_returns_same_ids_in_all_structures(run, tmp_path):
    assert run("coordinator.py", 1, 2, 3).returncode == 0
    result = run("lookup.py", "SHIP", "--json")
    assert result.returncode == 0, result.stderr
    answers = json.loads(result.stdout)
    assert answers == {"json": [1, 2], "folder": [1, 2], "sqlite": [1, 2]}


def test_lookup_of_a_missing_term_is_empty_not_an_error(run):
    assert run("coordinator.py", 1).returncode == 0
    result = run("lookup.py", "unicorn", "--json")
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {"json": [], "folder": [], "sqlite": []}


# --- corrección 11: marcar como indexado solo después de persistir --------------------------

@pytest.mark.parametrize("script,control,point", [
    ("datamart_index.py", "indexed_books.txt", "index_before_persist"),
    ("datamart_index_sqlite.py", "indexed_books_sqlite.txt", "index_before_persist"),
    ("datamart_index_folder.py", "indexed_books_folder.txt", "index_before_persist"),
])
def test_crash_before_persisting_leaves_the_book_unmarked(run, tmp_path, script, control, point):
    assert run("datalake.py", 1).returncode == 0
    crashed = run(script, crash=point)
    assert crashed.returncode == CRASH_EXIT_CODE, crashed.stdout + crashed.stderr
    assert control_ids(tmp_path, control) == []


@pytest.mark.parametrize("script,control", [
    ("datamart_index.py", "indexed_books.txt"),
    ("datamart_index_sqlite.py", "indexed_books_sqlite.txt"),
    ("datamart_index_folder.py", "indexed_books_folder.txt"),
])
def test_crash_after_persisting_is_recovered_without_duplicates(run, tmp_path, script, control):
    assert run("datalake.py", 1).returncode == 0
    crashed = run(script, crash="index_before_mark")
    assert crashed.returncode == CRASH_EXIT_CODE, crashed.stdout + crashed.stderr
    assert control_ids(tmp_path, control) == []
    assert run(script).returncode == 0
    assert control_ids(tmp_path, control) == [1]
    assert json.loads(run("lookup.py", "whale", "--json").stdout)[
        {"datamart_index.py": "json", "datamart_index_sqlite.py": "sqlite",
         "datamart_index_folder.py": "folder"}[script]] == [1]


# --- corrección 7: interrumpir la ingesta y reanudarla ----------------------------------------

@pytest.mark.parametrize("point", ["ingest_after_header", "ingest_before_mark"])
def test_interrupted_ingestion_resumes_without_loss_or_duplicates(run, tmp_path, point):
    crashed = run("datalake.py", 1, 2, crash=point)
    assert crashed.returncode == CRASH_EXIT_CODE
    assert control_ids(tmp_path, "downloaded_books.txt") == []

    # La reanudación ocurre en otra hora: la estructura time-based no debe crear otra copia.
    resumed = run("datalake.py", 1, 2, fake_hour="2031-01-02T23:00:00")
    assert resumed.returncode == 0, resumed.stderr
    assert sorted(control_ids(tmp_path, "downloaded_books.txt")) == [1, 2]
    for book_id in (1, 2):
        assert time_based_files(tmp_path, book_id) == [f"{book_id}.body.txt", f"{book_id}.header.txt"]
        book_dir = tmp_path / "datalake" / "book_based" / "books" / str(book_id)
        assert (book_dir / "header.txt").read_text(encoding="utf-8").strip()
        assert (book_dir / "body.txt").read_text(encoding="utf-8").strip()


def test_partial_book_is_not_visible_to_metadata_or_indexing(run, tmp_path):
    assert run("datalake.py", 1, crash="ingest_after_header").returncode == CRASH_EXIT_CODE
    assert run("datamart_metadata.py").returncode == 0
    assert run("datamart_index_sqlite.py").returncode == 0
    db = tmp_path / "datamarts" / "inverted_index.db"
    if db.exists():
        conn = sqlite3.connect(db)
        tables = {row[0] for row in conn.execute("select name from sqlite_master")}
        assert "postings" not in tables or conn.execute("select count(*) from postings").fetchone()[0] == 0
        assert "books" not in tables or conn.execute("select count(*) from books").fetchone()[0] == 0
        conn.close()


# --- corrección 8: reaplicar una actualización no cambia el estado ------------------------------

def _snapshot(base):
    conn = sqlite3.connect(base / "datamarts" / "inverted_index.db")
    postings = sorted(conn.execute("select term, book_id from postings").fetchall())
    books = sorted(conn.execute("select * from books").fetchall())
    conn.close()
    folder = base / "datamarts" / "inverted_index_folder"
    files = {str(p.relative_to(folder)): p.read_text() for p in folder.rglob("*.txt")}
    index_json = json.loads((base / "datamarts" / "inverted_index.json").read_text(encoding="utf-8"))
    return postings, books, files, index_json


def test_reapplying_the_pipeline_preserves_the_output_state(run, tmp_path):
    assert run("coordinator.py", 1, 2, 3).returncode == 0
    first = _snapshot(tmp_path)
    assert run("coordinator.py", 1, 2, 3).returncode == 0
    assert _snapshot(tmp_path) == first


def test_clearing_the_tracking_and_reindexing_preserves_the_output_state(run, tmp_path):
    assert run("coordinator.py", 1, 2, 3).returncode == 0
    first = _snapshot(tmp_path)
    for name in ("indexed_books.txt", "indexed_books_sqlite.txt", "indexed_books_folder.txt"):
        (tmp_path / "control" / name).unlink()
    assert run("coordinator.py", 1, 2, 3).returncode == 0
    assert _snapshot(tmp_path) == first


# --- corrección 11: coordinador que atiende primero lo pendiente --------------------------------

def test_coordinator_finishes_pending_books_before_downloading_new_ones(run, tmp_path):
    assert run("datalake.py", 1).returncode == 0  # descargado pero sin indexar
    result = run("coordinator.py", 1, 2)
    assert result.returncode == 0, result.stderr
    assert result.stdout.index("PENDIENTE libro 1") < result.stdout.index("NUEVO libro 2")
    assert json.loads(run("lookup.py", "ship", "--json").stdout)["sqlite"] == [1, 2]


def test_coordinator_retries_after_a_crash(run, tmp_path):
    assert run("coordinator.py", 1, 2, crash="index_before_mark").returncode == CRASH_EXIT_CODE
    assert run("coordinator.py", 1, 2).returncode == 0
    assert json.loads(run("lookup.py", "ship", "--json").stdout) == {
        "json": [1, 2], "folder": [1, 2], "sqlite": [1, 2]}


# --- corrección 12: sin listas de libros fijas en el código --------------------------------------

def test_book_ids_come_from_the_command_line(run, tmp_path):
    assert run("datalake.py", 3).returncode == 0
    assert control_ids(tmp_path, "downloaded_books.txt") == [3]


def test_unavailable_book_is_reported_and_not_marked(run, tmp_path):
    result = run("datalake.py", 999)
    assert result.returncode == 0
    assert control_ids(tmp_path, "downloaded_books.txt") == []
    assert not (tmp_path / "datalake" / "book_based" / "books" / "999").exists()
