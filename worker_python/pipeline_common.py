"""Rutas, capa de control y utilidades compartidas por todos los scripts del pipeline.

Antes cada script repetía sus rutas y su lectura/escritura de ficheros de control.
Reunirlas aquí evita que las copias diverjan.
"""
import os
import re
import sys
import tempfile
from pathlib import Path

REPO_DIR = Path(__file__).resolve().parent.parent
# STAGE1_BASE_DIR permite ejecutar el pipeline real sobre un directorio aislado.
BASE_DIR = Path(os.environ.get("STAGE1_BASE_DIR", REPO_DIR))

DATALAKE_DIR = BASE_DIR / "datalake"
TIME_BASED_DIR = DATALAKE_DIR / "time_based"
BOOK_BASED_DIR = DATALAKE_DIR / "book_based" / "books"
RANGE_BASED_DIR = DATALAKE_DIR / "range_based"

DATAMARTS_DIR = BASE_DIR / "datamarts"
DB_PATH = DATAMARTS_DIR / "inverted_index.db"
INDEX_JSON_PATH = DATAMARTS_DIR / "inverted_index.json"
INDEX_FOLDER_DIR = DATAMARTS_DIR / "inverted_index_folder"

CONTROL_DIR = BASE_DIR / "control"
DOWNLOADED_FILE = CONTROL_DIR / "downloaded_books.txt"
INDEXED_JSON_FILE = CONTROL_DIR / "indexed_books.txt"
INDEXED_SQLITE_FILE = CONTROL_DIR / "indexed_books_sqlite.txt"
INDEXED_FOLDER_FILE = CONTROL_DIR / "indexed_books_folder.txt"

DEFAULT_BOOK_IDS_FILE = REPO_DIR / "config" / "book_ids.txt"

_TOKEN = re.compile(r"\b[a-z0-9]+\b")


def read_ids(control_file: Path) -> set:
    """Devuelve los IDs registrados en un fichero de control (vacío si no existe)."""
    if not control_file.exists():
        return set()
    with open(control_file, "r") as f:
        return {int(line.strip()) for line in f if line.strip().isdigit()}


def append_id(control_file: Path, book_id: int) -> None:
    """Registra un ID. Debe llamarse solo cuando el trabajo ya está persistido."""
    control_file.parent.mkdir(parents=True, exist_ok=True)
    with open(control_file, "a") as f:
        f.write(f"{book_id}\n")


def tokenize(text: str) -> set:
    """Términos únicos de un texto: la misma regla al indexar y al consultar."""
    return set(_TOKEN.findall(text.lower()))


def atomic_write_text(path: Path, content: str, encoding: str = "utf-8") -> None:
    """Escribe en un temporal y lo renombra: un corte deja el fichero anterior o el nuevo, nunca uno a medias."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding=encoding) as f:
            f.write(content)
        os.replace(tmp_name, path)
    except BaseException:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
        raise


def crash_point(name: str) -> None:
    """Termina el proceso sin limpieza si STAGE1_CRASH_POINT coincide (pruebas de recuperación)."""
    if os.environ.get("STAGE1_CRASH_POINT") == name:
        sys.stdout.flush()
        os._exit(137)


def load_book_ids(args: list) -> list:
    """IDs a procesar: los de la línea de comandos o, si no hay, los de config/book_ids.txt."""
    if args:
        return [int(arg) for arg in args]
    with open(DEFAULT_BOOK_IDS_FILE, "r") as f:
        return [int(line.split("#")[0]) for line in f if line.split("#")[0].strip()]


def ready_books(indexed: set) -> list:
    """Libros con la ingesta terminada (según el control) que faltan por indexar, y su cuerpo."""
    pending = []
    for book_id in sorted(read_ids(DOWNLOADED_FILE) - indexed):
        body_file = BOOK_BASED_DIR / str(book_id) / "body.txt"
        if body_file.exists():
            pending.append((book_id, body_file))
    return pending
