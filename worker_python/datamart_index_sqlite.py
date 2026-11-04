import sqlite3

from pipeline_common import (
    DATAMARTS_DIR,
    DB_PATH,
    INDEXED_SQLITE_FILE,
    append_id,
    crash_point,
    read_ids,
    ready_books,
    tokenize,
)


def init_db():
    DATAMARTS_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    # Tabla con clave primaria compuesta para evitar duplicados
    # y acelerar drásticamente las consultas WHERE term = 'x'
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS postings (
            term TEXT,
            book_id INTEGER,
            PRIMARY KEY (term, book_id)
        )
    ''')
    conn.commit()
    return conn


def process_index_sqlite():
    pendientes = ready_books(read_ids(INDEXED_SQLITE_FILE))
    if not pendientes:
        print("\nNo hay libros nuevos para indexar en SQLite.")
        return

    conn = init_db()
    cursor = conn.cursor()
    print("Iniciando construcción del índice invertido (SQLite)...")

    for book_id, body_file in pendientes:
        print(f"  -> Indexando libro {book_id} en BD...")
        # Preparamos la inserción masiva (bulk insert) para mayor rendimiento
        rows = [(word, book_id) for word in tokenize(body_file.read_text(encoding="utf-8"))]
        cursor.executemany('''
            INSERT OR IGNORE INTO postings (term, book_id)
            VALUES (?, ?)
        ''', rows)

        # Orden importante: primero el commit y después la marca. Si el proceso cae entre
        # ambos, el libro se reindexa y OR IGNORE evita duplicados; al revés, quedaría
        # marcado como indexado con la transacción perdida.
        crash_point("index_before_persist")
        conn.commit()  # Guardamos en disco por cada libro procesado
        crash_point("index_before_mark")
        append_id(INDEXED_SQLITE_FILE, book_id)

    conn.close()
    print(f"\n¡Proceso completado! Se añadieron {len(pendientes)} libros al índice SQLite.")
    print(f"Base de datos guardada en {DB_PATH}")


if __name__ == "__main__":
    process_index_sqlite()
