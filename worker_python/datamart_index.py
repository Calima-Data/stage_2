import json
import re
from pathlib import Path

# Rutas principales
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake" / "book_based" / "books"
DATAMARTS_DIR = BASE_DIR / "datamarts"
CONTROL_DIR = BASE_DIR / "control"

INDEX_JSON_PATH = DATAMARTS_DIR / "inverted_index.json"
INDEXED_FILE = CONTROL_DIR / "indexed_books.txt"

def get_indexed_books() -> set:
    """Lee el control de libros ya indexados."""
    if not INDEXED_FILE.exists():
        return set()
    with open(INDEXED_FILE, "r") as f:
        return set(int(line.strip()) for line in f if line.strip().isdigit())

def mark_as_indexed(book_id: int):
    """Registra el libro como indexado."""
    CONTROL_DIR.mkdir(parents=True, exist_ok=True)
    with open(INDEXED_FILE, "a") as f:
        f.write(f"{book_id}\n")

def load_index() -> dict:
    """Carga el índice existente o crea uno vacío."""
    if INDEX_JSON_PATH.exists():
        with open(INDEX_JSON_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}

def save_index(index: dict):
    """Guarda el índice en disco."""
    DATAMARTS_DIR.mkdir(parents=True, exist_ok=True)
    with open(INDEX_JSON_PATH, "w", encoding="utf-8") as f:
        # indent=2 formatea el JSON para que sea legible por humanos
        json.dump(index, f, ensure_ascii=False, indent=2)

def process_index():
    if not DATALAKE_DIR.exists():
        print("Error: No se encontró el Datalake.")
        return

    indexed_books = get_indexed_books()
    inverted_index = load_index()
    procesados = 0

    print("Iniciando construcción del índice invertido (JSON)...")

    for book_dir in DATALAKE_DIR.iterdir():
        if book_dir.is_dir():
            book_id = int(book_dir.name)
            
            if book_id in indexed_books:
                print(f"Saltando libro {book_id}: Ya indexado.")
                continue
            
            body_file = book_dir / "body.txt"
            if body_file.exists():
                print(f"  -> Indexando libro {book_id}...")
                text = body_file.read_text(encoding="utf-8")
                
                # Expresión regular: extrae solo palabras alfanuméricas en minúsculas
                words = re.findall(r'\b[a-z0-9]+\b', text.lower())
                unique_words = set(words) # Usamos set para no contar palabras repetidas del mismo libro
                
                for word in unique_words:
                    if word not in inverted_index:
                        inverted_index[word] = []
                    if book_id not in inverted_index[word]:
                        inverted_index[word].append(book_id)
                        inverted_index[word].sort() # Mantenemos la lista ordenada
                
                mark_as_indexed(book_id)
                procesados += 1

    if procesados > 0:
        save_index(inverted_index)
        print(f"\n¡Proceso completado! Se añadieron {procesados} libros al índice.")
        print(f"Archivo guardado en {INDEX_JSON_PATH}")
    else:
        print("\nNo hay libros nuevos para indexar.")

if __name__ == "__main__":
    process_index()