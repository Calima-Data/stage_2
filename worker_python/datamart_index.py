import json

from pipeline_common import (
    INDEX_JSON_PATH,
    INDEXED_JSON_FILE,
    append_id,
    atomic_write_text,
    crash_point,
    read_ids,
    ready_books,
    tokenize,
)


def load_index() -> dict:
    """Carga el índice existente o crea uno vacío."""
    if INDEX_JSON_PATH.exists():
        with open(INDEX_JSON_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_index(index: dict):
    """Guarda el índice en disco de forma atómica."""
    # indent=2 formatea el JSON para que sea legible por humanos
    atomic_write_text(INDEX_JSON_PATH, json.dumps(index, ensure_ascii=False, indent=2))


def add_book(index: dict, book_id: int, terms: set):
    for word in terms:
        postings = index.setdefault(word, [])
        if book_id not in postings:
            postings.append(book_id)
            postings.sort()  # Mantenemos la lista ordenada


def process_index():
    pendientes = ready_books(read_ids(INDEXED_JSON_FILE))
    if not pendientes:
        print("\nNo hay libros nuevos para indexar.")
        return

    inverted_index = load_index()
    print("Iniciando construcción del índice invertido (JSON)...")
    for book_id, body_file in pendientes:
        print(f"  -> Indexando libro {book_id}...")
        add_book(inverted_index, book_id, tokenize(body_file.read_text(encoding="utf-8")))

    # Orden importante: primero se persiste el índice y después se marca cada libro. Si el
    # proceso cae entre ambos pasos, el libro se reindexa sin duplicar sus postings; al
    # revés, quedaría marcado como indexado sin estar en el índice.
    crash_point("index_before_persist")
    save_index(inverted_index)
    crash_point("index_before_mark")
    for book_id, _ in pendientes:
        append_id(INDEXED_JSON_FILE, book_id)

    print(f"\n¡Proceso completado! Se añadieron {len(pendientes)} libros al índice.")
    print(f"Archivo guardado en {INDEX_JSON_PATH}")


if __name__ == "__main__":
    process_index()
