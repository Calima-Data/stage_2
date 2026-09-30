import sqlite3
import re
from pathlib import Path

# Rutas principales adaptadas a la estructura Time-based
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake" / "time_based"
DATAMARTS_DIR = BASE_DIR / "datamarts"
DB_PATH = DATAMARTS_DIR / "inverted_index.db"

def init_db():
    DATAMARTS_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS books (
            book_id INTEGER PRIMARY KEY,
            title TEXT,
            author TEXT,
            language TEXT
        )
    ''')
    conn.commit()
    return conn

def extract_metadata(header_text: str) -> dict:
    title_match = re.search(r"Title:\s*(.*)", header_text)
    author_match = re.search(r"Author:\s*(.*)", header_text)
    lang_match = re.search(r"Language:\s*(.*)", header_text)
    
    return {
        "title": title_match.group(1).strip() if title_match else "Desconocido",
        "author": author_match.group(1).strip() if author_match else "Desconocido",
        "language": lang_match.group(1).strip() if lang_match else "Desconocido"
    }

def process_metadata():
    if not DATALAKE_DIR.exists():
        print("Error: No se encontró el Datalake temporal.")
        return

    conn = init_db()
    cursor = conn.cursor()
    procesados = 0

    print("Iniciando extracción de metadatos desde estructura Time-based...")
    
    # rglob busca recursivamente todos los archivos que terminen en .header.txt
    for header_file in DATALAKE_DIR.rglob("*.header.txt"):
        # El nombre del archivo es "1342.header.txt", extraemos el ID numérico
        book_id = int(header_file.name.split('.')[0])
        
        text = header_file.read_text(encoding="utf-8")
        meta = extract_metadata(text)
        
        cursor.execute('''
            INSERT OR REPLACE INTO books (book_id, title, author, language)
            VALUES (?, ?, ?, ?)
        ''', (book_id, meta["title"], meta["author"], meta["language"]))
        
        print(f"  -> Libro {book_id}: {meta['title']} ({meta['author']})")
        procesados += 1
    
    conn.commit()
    conn.close()
    print(f"\n¡Proceso completado! {procesados} libros guardados en la tabla 'books' de {DB_PATH}")

if __name__ == "__main__":
    process_metadata()