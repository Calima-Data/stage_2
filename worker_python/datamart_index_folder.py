from pathlib import Path

from pipeline_common import (
    INDEX_FOLDER_DIR,
    INDEXED_FOLDER_FILE,
    append_id,
    atomic_write_text,
    crash_point,
    read_ids,
    ready_books,
    tokenize,
)


def term_file(term: str) -> Path:
    """Fichero de un término: una subcarpeta por primera letra y un .txt por término."""
    return INDEX_FOLDER_DIR / term[0].upper() / f"{term}.txt"


def add_book(book_id: int, terms: set):
    for word in terms:
        word_file = term_file(word)

        # Leer IDs existentes si el archivo ya existe
        existing_ids = read_ids(word_file)

        # Añadir el nuevo ID y guardar ordenado. La escritura es atómica: un corte a
        # medias de un fichero haría perder los postings de otros libros ya indexados.
        if book_id not in existing_ids:
            existing_ids.add(book_id)
            atomic_write_text(word_file, "".join(f"{i}\n" for i in sorted(existing_ids)), encoding="ascii")


def process_index_folder():
    pendientes = ready_books(read_ids(INDEXED_FOLDER_FILE))
    if not pendientes:
        print("\nNo hay libros nuevos para indexar en carpetas.")
        return

    print("Iniciando construcción del índice invertido (Carpetas)...")
    INDEX_FOLDER_DIR.mkdir(parents=True, exist_ok=True)

    for book_id, body_file in pendientes:
        print(f"  -> Indexando libro {book_id} en carpetas... (esto puede tardar unos segundos por las operaciones de I/O en disco)")
        crash_point("index_before_persist")
        add_book(book_id, tokenize(body_file.read_text(encoding="utf-8")))
        crash_point("index_before_mark")
        append_id(INDEXED_FOLDER_FILE, book_id)

    print(f"\n¡Proceso completado! Se añadieron {len(pendientes)} libros a la jerarquía de carpetas.")


if __name__ == "__main__":
    process_index_folder()
