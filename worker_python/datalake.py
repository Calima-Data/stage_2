import os
import sys
from datetime import datetime
from pathlib import Path

import requests

from pipeline_common import (
    DATALAKE_DIR,
    DOWNLOADED_FILE,
    append_id,
    atomic_write_text,
    crash_point,
    load_book_ids,
    read_ids,
)

# Marcadores de Project Gutenberg
START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK"
END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK"

DEFAULT_URL_TEMPLATE = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"


def get_downloaded_books() -> set:
    """Lee el archivo de control y devuelve un set con los IDs ya descargados."""
    return read_ids(DOWNLOADED_FILE)


def mark_as_downloaded(book_id: int):
    """Añade el ID del libro al archivo de control."""
    append_id(DOWNLOADED_FILE, book_id)


def fetch_and_split_book(book_id: int):
    template = os.environ.get("GUTENBERG_URL_TEMPLATE", DEFAULT_URL_TEMPLATE)
    url = template.replace("{id}", str(book_id))
    print(f"Descargando libro {book_id}...")

    try:
        response = requests.get(url, timeout=15)
        response.raise_for_status()
    except requests.RequestException as e:
        print(f"Error al descargar libro {book_id}: {e}")
        return None, None

    response.encoding = "utf-8"
    text = response.text

    if START_MARKER not in text or END_MARKER not in text:
        print(f"Error: Marcadores no encontrados en el libro {book_id}")
        return None, None

    header, body_and_footer = text.split(START_MARKER, 1)
    body, _ = body_and_footer.split(END_MARKER, 1)

    return header.strip(), body.strip()


def _now() -> datetime:
    fake_now = os.environ.get("STAGE1_FAKE_NOW")  # solo para probar la reanudación en otra hora
    return datetime.fromisoformat(fake_now) if fake_now else datetime.now()


def _write_pair(header_path: Path, body_path: Path, header: str, body: str):
    # La cabecera va primero: la presencia del cuerpo implica que el par está completo.
    atomic_write_text(header_path, header)
    crash_point("ingest_after_header")
    atomic_write_text(body_path, body)


def existing_time_based_dir(book_id: int):
    """Carpeta time-based que ya contiene ficheros de este libro (de un intento interrumpido)."""
    root = DATALAKE_DIR / "time_based"
    if not root.exists():
        return None
    for day in root.iterdir():
        for hour in day.iterdir():
            if (hour / f"{book_id}.header.txt").exists() or (hour / f"{book_id}.body.txt").exists():
                return hour
    return None


def save_time_based(book_id: int, header: str, body: str, output_path: Path = None):
    if output_path is None:
        now = _now()
        output_path = DATALAKE_DIR / "time_based" / now.strftime("%Y%m%d") / now.strftime("%H")
    _write_pair(output_path / f"{book_id}.header.txt", output_path / f"{book_id}.body.txt", header, body)


def save_book_based(book_id: int, header: str, body: str):
    output_path = DATALAKE_DIR / "book_based" / "books" / str(book_id)
    _write_pair(output_path / "header.txt", output_path / "body.txt", header, body)


def save_range_based(book_id: int, header: str, body: str, range_size: int = 1000):
    lower = ((book_id - 1) // range_size) * range_size + 1
    upper = lower + range_size - 1
    output_path = DATALAKE_DIR / "range_based" / f"batch_{lower}_{upper}"
    _write_pair(output_path / f"{book_id}.header.txt", output_path / f"{book_id}.body.txt", header, body)


def ingest_book(book_id: int) -> bool:
    """Descarga un libro, lo guarda en las tres estructuras y solo entonces lo registra."""
    header, body = fetch_and_split_book(book_id)
    if not (header and body):
        return False
    # Un reintento reutiliza la carpeta del intento anterior: time-based depende de la
    # hora actual y, si no, un reintento en otra hora dejaría una segunda copia del libro.
    save_time_based(book_id, header, body, existing_time_based_dir(book_id))
    save_book_based(book_id, header, body)
    save_range_based(book_id, header, body)
    crash_point("ingest_before_mark")
    mark_as_downloaded(book_id)
    print(f"Éxito: Libro {book_id} guardado y registrado en control.")
    return True


def main(args: list):
    descargados = get_downloaded_books()
    for libro in load_book_ids(args):
        if libro in descargados:
            print(f"Saltando libro {libro}: Ya se encuentra en el Datalake.")
            continue
        ingest_book(libro)


if __name__ == "__main__":
    main(sys.argv[1:])
