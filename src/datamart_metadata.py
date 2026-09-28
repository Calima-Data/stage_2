import sqlite3
import re
from pathlib import Path

# Rutas principales
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake" / "book_based" / "books"
DATAMARTS_DIR = BASE_DIR / "datamarts"
DB_PATH = DATAMARTS_DIR / "metadata.db"

def init_db():
    """Crea la base de datos y la tabla si no existen."""
    DATAMARTS_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    # Tabla normalizada para los metadatos
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS metadata (
            book_id INTEGER PRIMARY KEY,
            title TEXT,
            author TEXT,
            language TEXT
        )
    ''')
    conn.commit()
    return conn

def extract_metadata(header_text: str) -> dict:
    """Extrae Título, Autor e Idioma usando expresiones regulares."""
    title_match = re.search(r"Title:\s*(.*)", header_text)
    author_match = re.search(r"Author:\s*(.*)", header_text)
    lang_match = re.search(r"Language:\s*(.*)", header_text)
    
    return {
        "title": title_match.group(1).strip() if title_match else "Desconocido",
        "author": author_match.group(1).strip() if author_match else "Desconocido",
        "language": lang_match.group(1).strip() if lang_match else "Desconocido"
    }

def process_metadata():
    """Recorre el datalake, extrae la información y la guarda en SQLite."""
    if not DATALAKE_DIR.exists():
        print("Error: No se encontró el Datalake. Ejecuta datalake.py primero.")
        return

    conn = init_db()
    cursor = conn.cursor()
    procesados = 0

    print("Iniciando extracción de metadatos...")
    
    # Recorremos cada carpeta de libro en el datalake
    for book_dir in DATALAKE_DIR.iterdir():
        if book_dir.is_dir():
            book_id = int(book_dir.name)
            header_file = book_dir / "header.txt"
            
            if header_file.exists():
                text = header_file.read_text(encoding="utf-8")
                meta = extract_metadata(text)
                
                # Usamos REPLACE por si ejecutamos el script varias veces, para que actualice y no duplique
                cursor.execute('''
                    INSERT OR REPLACE INTO metadata (book_id, title, author, language)
                    VALUES (?, ?, ?, ?)
                ''', (book_id, meta["title"], meta["author"], meta["language"]))
                
                print(f"  -> Libro {book_id}: {meta['title']} ({meta['author']})")
                procesados += 1
    
    conn.commit()
    conn.close()
    print(f"\n¡Proceso completado! {procesados} libros guardados en {DB_PATH}")

if __name__ == "__main__":
    process_metadata()