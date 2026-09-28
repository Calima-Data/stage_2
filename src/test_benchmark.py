import pytest
import shutil
from pathlib import Path

# Importamos el módulo completo en lugar de funciones sueltas
import datalake

# Generamos un texto masivo en memoria para simular un libro grande (evitando descargas de red)
TEST_BOOK_ID = 99999
TEST_HEADER = "Title: Benchmark Test\nAuthor: Data Team\nLanguage: English"
TEST_BODY = "This is a dummy text block simulating a large book body. " * 50000 

# Carpeta temporal exclusiva para el benchmark para no ensuciar tu Datalake real
TEST_DIR = Path(__file__).resolve().parent.parent / "datalake_benchmark_tmp"

@pytest.fixture(autouse=True)
def setup_and_teardown(monkeypatch):
    """Se ejecuta antes y después de cada test para limpiar el entorno."""
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)
    TEST_DIR.mkdir(parents=True)
    
    # "Engañamos" al script principal cambiándole la variable DATALAKE_DIR al vuelo
    monkeypatch.setattr(datalake, "DATALAKE_DIR", TEST_DIR)
    
    yield
    
    # Borramos la carpeta temporal al terminar
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)

def test_time_based(benchmark):
    # Ya solo pasamos 3 argumentos (book_id, header, body)
    benchmark(datalake.save_time_based, TEST_BOOK_ID, TEST_HEADER, TEST_BODY)

def test_book_based(benchmark):
    benchmark(datalake.save_book_based, TEST_BOOK_ID, TEST_HEADER, TEST_BODY)

def test_range_based(benchmark):
    # El 1000 es el parámetro opcional 'range_size' de esta función específica
    benchmark(datalake.save_range_based, TEST_BOOK_ID, TEST_HEADER, TEST_BODY, 1000)