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
    monkeypatch.setattr(datalake, "DATALAKE_DIR", TEST_DIR)
    yield
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)

def fresh_datalake():
    # Se ejecuta antes de CADA ronda (también las de warmup) y no se cronometra.
    # Sin esto, solo la primera ronda crearía carpetas y ficheros y las siguientes
    # solo los sobrescribirían, ocultando el coste de creación de directorios que
    # es justo lo que distingue a las tres estructuras.
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)
    TEST_DIR.mkdir(parents=True)

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

# 2 rondas de warmup (calientan el intérprete y la caché del SO) + 5 medidas, cada una
# sobre un datalake vacío: igual que @Warmup/@Measurement de JMH en worker_java.
def test_time_based(benchmark):
    benchmark.pedantic(batch_time_based, setup=fresh_datalake, rounds=5, iterations=1, warmup_rounds=2)

def test_book_based(benchmark):
    benchmark.pedantic(batch_book_based, setup=fresh_datalake, rounds=5, iterations=1, warmup_rounds=2)

def test_range_based(benchmark):
    benchmark.pedantic(batch_range_based, setup=fresh_datalake, rounds=5, iterations=1, warmup_rounds=2)
