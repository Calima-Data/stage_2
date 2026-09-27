import requests
from pathlib import Path
from datetime import datetime

# Marcadores de Project Gutenberg
START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK"
END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK"

# Definir la raíz del proyecto respecto a este script (src/ -> raíz)
BASE_DIR = Path(__file__).resolve().parent.parent
DATALAKE_DIR = BASE_DIR / "datalake"


def fetch_and_split_book(book_id: int):
    """
    Descarga el libro y devuelve (header, body).
    Aislar la descarga permite medir el guardado en disco sin latencia de red.
    """
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


def save_time_based(book_id: int, header: str, body: str, base_path: Path = DATALAKE_DIR):
    """Estructura 1: datalake/time_based/YYYYMMDD/HH/"""
    now = datetime.now()
    output_path = base_path / "time_based" / now.strftime("%Y%m%d") / now.strftime("%H")
    output_path.mkdir(parents=True, exist_ok=True)

    (output_path / f"{book_id}.header.txt").write_text(header, encoding="utf-8")
    (output_path / f"{book_id}.body.txt").write_text(body, encoding="utf-8")
    return output_path


def save_book_based(book_id: int, header: str, body: str, base_path: Path = DATALAKE_DIR):
    """Estructura 2: datalake/book_based/books/<BOOK_ID>/"""
    output_path = base_path / "book_based" / "books" / str(book_id)
    output_path.mkdir(parents=True, exist_ok=True)

    (output_path / "header.txt").write_text(header, encoding="utf-8")
    (output_path / "body.txt").write_text(body, encoding="utf-8")
    return output_path


def save_range_based(book_id: int, header: str, body: str, base_path: Path = DATALAKE_DIR, range_size: int = 1000):
    """Estructura 3: datalake/range_based/batch_<inicio>_<fin>/"""
    lower = ((book_id - 1) // range_size) * range_size + 1
    upper = lower + range_size - 1
    output_path = base_path / "range_based" / f"batch_{lower}_{upper}"
    output_path.mkdir(parents=True, exist_ok=True)

    (output_path / f"{book_id}.header.txt").write_text(header, encoding="utf-8")
    (output_path / f"{book_id}.body.txt").write_text(body, encoding="utf-8")
    return output_path


if __name__ == "__main__":
    libros_prueba = [1342, 84, 11]  # Orgullo y Prejuicio, Frankenstein, Alicia

    for libro in libros_prueba:
        header, body = fetch_and_split_book(libro)
        if header and body:
            # Guardamos en las 3 estructuras para tener datos en las comparativas
            save_time_based(libro, header, body)
            save_book_based(libro, header, body)
            save_range_based(libro, header, body)
            print(f"Éxito: Libro {libro} guardado en las 3 estructuras del datalake.")