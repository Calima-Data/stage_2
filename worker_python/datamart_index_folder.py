import re
from pathlib import Path

# Rutas principales
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake" / "book_based" / "books"
DATAMARTS_DIR = BASE_DIR / "datamarts"
INDEX_DIR = DATAMARTS_DIR / "inverted_index_folder"
CONTROL_DIR = BASE_DIR / "control"

# Control independiente para esta estructura
INDEXED_FILE = CONTROL_DIR / "indexed_books_folder.txt"

def get_indexed_books() -> set:
    if not INDEXED_FILE.exists():
        return set()
    with open(INDEXED_FILE, "r") as f:
        return set(int(line.strip()) for line in f if line.strip().isdigit())

def mark_as_indexed(book_id: int):
    CONTROL_DIR.mkdir(parents=True, exist_ok=True)
    with open(INDEXED_FILE, "a") as f:
        f.write(f"{book_id}\n")

def process_index_folder():
    if not DATALAKE_DIR.exists():
        print("Error: No se encontró el Datalake.")
        return

    indexed_books = get_indexed_books()
    procesados = 0

    print("Iniciando construcción del índice invertido (Carpetas)...")
    INDEX_DIR.mkdir(parents=True, exist_ok=True)

    for book_dir in DATALAKE_DIR.iterdir():
        if book_dir.is_dir():
            book_id = int(book_dir.name)
            
            if book_id in indexed_books:
                print(f"Saltando libro {book_id}: Ya indexado en carpetas.")
                continue
            
            body_file = book_dir / "body.txt"
            if body_file.exists():
                print(f"  -> Indexando libro {book_id} en carpetas... (esto puede tardar unos segundos por las operaciones de I/O en disco)")
                text = body_file.read_text(encoding="utf-8")
                
                # Extraemos palabras únicas
                words = set(re.findall(r'\b[a-z0-9]+\b', text.lower()))
                
                for word in words:
                    # Determinamos la primera letra para la subcarpeta
                    first_letter = word[0].upper()
                    letter_dir = INDEX_DIR / first_letter
                    letter_dir.mkdir(exist_ok=True)
                    
                    word_file = letter_dir / f"{word}.txt"
                    
                    # Leer IDs existentes si el archivo ya existe
                    existing_ids = set()
                    if word_file.exists():
                        with open(word_file, "r") as f:
                            existing_ids = set(int(line.strip()) for line in f if line.strip().isdigit())
                    
                    # Añadir el nuevo ID y guardar ordenado
                    if book_id not in existing_ids:
                        existing_ids.add(book_id)
                        sorted_ids = sorted(list(existing_ids))
                        with open(word_file, "w") as f:
                            for i in sorted_ids:
                                f.write(f"{i}\n")
                
                mark_as_indexed(book_id)
                procesados += 1

    if procesados > 0:
        print(f"\n¡Proceso completado! Se añadieron {procesados} libros a la jerarquía de carpetas.")
    else:
        print("\nNo hay libros nuevos para indexar en carpetas.")

if __name__ == "__main__":
    process_index_folder()