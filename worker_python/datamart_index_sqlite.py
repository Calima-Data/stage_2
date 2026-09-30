import sqlite3
import re
from pathlib import Path

# Rutas principales
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake" / "book_based" / "books"
DATAMARTS_DIR = BASE_DIR / "datamarts"
CONTROL_DIR = BASE_DIR / "control"

DB_PATH = DATAMARTS_DIR / "inverted_index.db"
# Usamos un archivo de control distinto para no pisar el progreso del JSON
INDEXED_FILE = CONTROL_DIR / "indexed_books_sqlite.txt"

def get_indexed_books() -> set:
    if not INDEXED_FILE.exists():
        return set()
    with open(INDEXED_FILE, "r") as f:
        return set(int(line.strip()) for line in f if line.strip().isdigit())

def mark_as_indexed(book_id: int):
    CONTROL_DIR.mkdir(parents=True, exist_ok=True)
    with open(INDEXED_FILE, "a") as f:
        f.write(f"{book_id}\n")

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
    if not DATALAKE_DIR.exists():
        print("Error: No se encontró el Datalake.")
        return

    indexed_books = get_indexed_books()
    conn = init_db()
    cursor = conn.cursor()
    procesados = 0

    print("Iniciando construcción del índice invertido (SQLite)...")

    for book_dir in DATALAKE_DIR.iterdir():
        if book_dir.is_dir():
            book_id = int(book_dir.name)
            
            if book_id in indexed_books:
                print(f"Saltando libro {book_id}: Ya indexado en SQLite.")
                continue
            
            body_file = book_dir / "body.txt"
            if body_file.exists():
                print(f"  -> Indexando libro {book_id} en BD...")
                text = body_file.read_text(encoding="utf-8")
                
                # Extraemos palabras únicas del libro
                words = set(re.findall(r'\b[a-z0-9]+\b', text.lower()))
                
                # Preparamos la inserción masiva (bulk insert) para mayor rendimiento
                rows = [(word, book_id) for word in words]
                
                cursor.executemany('''
                    INSERT OR IGNORE INTO postings (term, book_id)
                    VALUES (?, ?)
                ''', rows)
                
                mark_as_indexed(book_id)
                procesados += 1
                conn.commit() # Guardamos en disco por cada libro procesado

    conn.close()
    if procesados > 0:
        print(f"\n¡Proceso completado! Se añadieron {procesados} libros al índice SQLite.")
        print(f"Base de datos guardada en {DB_PATH}")
    else:
        print("\nNo hay libros nuevos para indexar en SQLite.")

if __name__ == "__main__":
    process_index_sqlite()