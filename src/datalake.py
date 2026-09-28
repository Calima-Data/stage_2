import requests
from pathlib import Path
from datetime import datetime

# Marcadores de Project Gutenberg
START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK"
END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK"

# Rutas principales
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake"
CONTROL_DIR = BASE_DIR / "control"
DOWNLOADED_FILE = CONTROL_DIR / "downloaded_books.txt"

def get_downloaded_books() -> set:
    """Lee el archivo de control y devuelve un set con los IDs ya descargados."""
    if not DOWNLOADED_FILE.exists():
        return set()
    with open(DOWNLOADED_FILE, "r") as f:
        return set(int(line.strip()) for line in f if line.strip().isdigit())

def mark_as_downloaded(book_id: int):
    """Añade el ID del libro al archivo de control."""
    CONTROL_DIR.mkdir(parents=True, exist_ok=True)
    with open(DOWNLOADED_FILE, "a") as f:
        f.write(f"{book_id}\n")

def fetch_and_split_book(book_id: int):
    url = f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt"
    print(f"Descargando libro {book_id}...")
    
    try:
        response = requests.get(url, timeout=15)
        response.raise_for_status()
    except requests.RequestException as e:
        print(f"Error al descargar libro {book_id}: {e}")
        return None, None

    text = response.text

    if START_MARKER not in text or END_MARKER not in text:
        print(f"Error: Marcadores no encontrados en el libro {book_id}")
        return None, None

    header, body_and_footer = text.split(START_MARKER, 1)
    body, _ = body_and_footer.split(END_MARKER, 1)

    return header.strip(), body.strip()

def save_time_based(book_id: int, header: str, body: str):
    now = datetime.now()
    output_path = DATALAKE_DIR / "time_based" / now.strftime("%Y%m%d") / now.strftime("%H")
    output_path.mkdir(parents=True, exist_ok=True)
    (output_path / f"{book_id}.header.txt").write_text(header, encoding="utf-8")
    (output_path / f"{book_id}.body.txt").write_text(body, encoding="utf-8")

def save_book_based(book_id: int, header: str, body: str):
    output_path = DATALAKE_DIR / "book_based" / "books" / str(book_id)
    output_path.mkdir(parents=True, exist_ok=True)
    (output_path / "header.txt").write_text(header, encoding="utf-8")
    (output_path / "body.txt").write_text(body, encoding="utf-8")

def save_range_based(book_id: int, header: str, body: str, range_size: int = 1000):
    lower = ((book_id - 1) // range_size) * range_size + 1
    upper = lower + range_size - 1
    output_path = DATALAKE_DIR / "range_based" / f"batch_{lower}_{upper}"
    output_path.mkdir(parents=True, exist_ok=True)
    (output_path / f"{book_id}.header.txt").write_text(header, encoding="utf-8")
    (output_path / f"{book_id}.body.txt").write_text(body, encoding="utf-8")

if __name__ == "__main__":
    # Añadimos un libro nuevo (ej. 16 - Peter Pan) para ver la diferencia
    libros_prueba = [1342, 84, 11, 16]  
    
    descargados = get_downloaded_books()

    for libro in libros_prueba:
        if libro in descargados:
            print(f"Saltando libro {libro}: Ya se encuentra en el Datalake.")
            continue
            
        header, body = fetch_and_split_book(libro)
        if header and body:
            save_time_based(libro, header, body)
            save_book_based(libro, header, body)
            save_range_based(libro, header, body)
            mark_as_downloaded(libro)
            print(f"Éxito: Libro {libro} guardado y registrado en control.")