import sqlite3
import re
from pathlib import Path

# Rutas principales
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake" / "book_based" / "books"
DATAMARTS_DIR = BASE_DIR / "datamarts"
# Cambiamos la ruta para unificarlo en la misma base de datos del índice
DB_PATH = DATAMARTS_DIR / "inverted_index.db"

def init_db():
    DATAMARTS_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    # Cambiamos el nombre de la tabla a 'books' como exige el documento
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
        print("Error: No se encontró el Datalake.")
        return

    conn = init_db()
    cursor = conn.cursor()
    procesados = 0

    print("Iniciando extracción de metadatos...")
    
    for book_dir in DATALAKE_DIR.iterdir():
        if book_dir.is_dir():
            book_id = int(book_dir.name)
            header_file = book_dir / "header.txt"
            
            if header_file.exists():
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
    
    # Opcional: Borrar el archivo viejo metadata.db si existe para no confundirnos
    old_db = DATAMARTS_DIR / "metadata.db"
    if old_db.exists():
        old_db.unlink()
        print("Archivo antiguo metadata.db eliminado para mantener limpio el directorio.")

if __name__ == "__main__":
    process_metadata()