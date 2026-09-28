import pytest
import shutil
from pathlib import Path
import datalake

TEST_DIR = Path(__file__).resolve().parent.parent / "datalake_benchmark_tmp"
NUM_BOOKS = 1000
TEST_HEADER = "Title: Benchmark Test\nAuthor: Data Team\nLanguage: English"
TEST_BODY = "Small simulated text to focus purely on filesystem I/O overhead. " * 20

@pytest.fixture(autouse=True)
def setup_and_teardown(monkeypatch):
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)
    TEST_DIR.mkdir(parents=True)
    monkeypatch.setattr(datalake, "DATALAKE_DIR", TEST_DIR)
    yield
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)

# Envolvemos las funciones para insertar 1.000 libros por iteración
def batch_time_based():
    for i in range(NUM_BOOKS):
        datalake.save_time_based(i, TEST_HEADER, TEST_BODY)

def batch_book_based():
    for i in range(NUM_BOOKS):
        datalake.save_book_based(i, TEST_HEADER, TEST_BODY)

def batch_range_based():
    for i in range(NUM_BOOKS):
        datalake.save_range_based(i, TEST_HEADER, TEST_BODY, 100) # Lotes de 100

# Usamos rounds=3 para que no tarde una eternidad procesando miles de archivos
def test_time_based(benchmark):
    benchmark.pedantic(batch_time_based, rounds=3, iterations=1)

def test_book_based(benchmark):
    benchmark.pedantic(batch_book_based, rounds=3, iterations=1)

def test_range_based(benchmark):
    benchmark.pedantic(batch_range_based, rounds=3, iterations=1)