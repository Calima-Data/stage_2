"""Coordinador central del pipeline de Stage 1, que atiende primero lo pendiente.

Primero termina los libros ya descargados que falten por indexar en alguna estructura
(por ejemplo, tras una interrupción) y solo después descarga libros nuevos. Todo el
progreso vive en los ficheros de control, así que se puede relanzar tras cualquier corte.

Uso:
    python coordinator.py 1342 84 11   # IDs explícitos
    python coordinator.py              # IDs de config/book_ids.txt
"""
import sys

import datalake
import datamart_index
import datamart_index_folder
import datamart_index_sqlite
import datamart_metadata
from pipeline_common import (
    DOWNLOADED_FILE,
    INDEXED_FOLDER_FILE,
    INDEXED_JSON_FILE,
    INDEXED_SQLITE_FILE,
    load_book_ids,
    read_ids,
)


def plan(requested: list, downloaded: set, indexed_everywhere: set) -> tuple:
    """Devuelve (pendientes, nuevos): lo ya descargado sin indexar va antes que lo nuevo."""
    pending = sorted(downloaded - indexed_everywhere)
    new = [book_id for book_id in requested if book_id not in downloaded]
    return pending, new


def build_datamarts():
    """Cada paso es idempotente y solo procesa lo que su control marque como pendiente."""
    datamart_metadata.process_metadata()
    datamart_index.process_index()
    datamart_index_sqlite.process_index_sqlite()
    datamart_index_folder.process_index_folder()


def run(requested: list):
    indexed_everywhere = (read_ids(INDEXED_JSON_FILE) & read_ids(INDEXED_SQLITE_FILE)
                          & read_ids(INDEXED_FOLDER_FILE))
    pending, new = plan(requested, read_ids(DOWNLOADED_FILE), indexed_everywhere)

    for book_id in pending:
        print(f"PENDIENTE libro {book_id}: descargado, falta indexarlo")
    if pending:
        build_datamarts()

    for book_id in new:
        print(f"NUEVO libro {book_id}")
        if datalake.ingest_book(book_id):
            build_datamarts()

    print(f"Coordinador: {len(pending)} pendientes atendidos, {len(new)} nuevos procesados.")


if __name__ == "__main__":
    run(load_book_ids(sys.argv[1:]))
