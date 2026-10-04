import pytest
import sqlite3
import json
import shutil
import random
from pathlib import Path

TEST_DIR = Path(__file__).resolve().parent.parent / "index_benchmark_tmp"
DB_PATH = TEST_DIR / "inverted_index.db"
JSON_PATH = TEST_DIR / "inverted_index.json"
FOLDER_DIR = TEST_DIR / "folders"

# Generamos un vocabulario preexistente de 5.000 palabras únicas
VOCABULARY = [f"word{i}" for i in range(5000)]
TEST_BOOK_ID = 9999
# El nuevo libro añade 500 palabras (mezcla de existentes y nuevas)
BOOK_WORDS = set(random.sample(VOCABULARY, 450) + [f"new_word{i}" for i in range(50)])

@pytest.fixture(autouse=True)
def cleanup():
    yield
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)

def reset_dir():
    if TEST_DIR.exists():
        shutil.rmtree(TEST_DIR)
    TEST_DIR.mkdir(parents=True)

# Los tres "seed_*" dejan el índice en el estado previo a indexar el libro nuevo y se
# ejecutan antes de CADA ronda (también las de warmup) sin cronometrarse. Si el estado se
# reutilizara, toda ronda posterior a la primera sería "reinsertar un libro que ya está en
# el índice", una operación distinta y más barata que indexar un libro nuevo.
def seed_sqlite():
    reset_dir()
    conn = sqlite3.connect(DB_PATH)
    conn.execute('CREATE TABLE postings (term TEXT, book_id INTEGER, PRIMARY KEY (term, book_id))')
    conn.executemany('INSERT INTO postings (term, book_id) VALUES (?, ?)', [(w, 1) for w in VOCABULARY])
    conn.commit()
    conn.close()
    return (TEST_BOOK_ID, BOOK_WORDS), {}

def seed_json():
    reset_dir()
    with open(JSON_PATH, "w") as f:
        json.dump({w: [1] for w in VOCABULARY}, f)
    return (TEST_BOOK_ID, BOOK_WORDS), {}

def seed_folders():
    reset_dir()
    FOLDER_DIR.mkdir()
    # solo existen los ficheros de las palabras del libro que ya estaban en el vocabulario
    for w in BOOK_WORDS:
        if w in VOCABULARY:
            letter_dir = FOLDER_DIR / w[0].upper()
            letter_dir.mkdir(exist_ok=True)
            with open(letter_dir / f"{w}.txt", "w") as f:
                f.write("1\n")
    return (TEST_BOOK_ID, BOOK_WORDS), {}

def insert_json(book_id, words):
    with open(JSON_PATH, "r") as f:
        index = json.load(f)
    for w in words:
        if w not in index:
            index[w] = []
        if book_id not in index[w]:
            index[w].append(book_id)
    with open(JSON_PATH, "w") as f:
        json.dump(index, f)

def insert_sqlite(book_id, words):
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    cursor.executemany('INSERT OR IGNORE INTO postings (term, book_id) VALUES (?, ?)', [(w, book_id) for w in words])
    conn.commit()
    conn.close()

def insert_folders(book_id, words):
    for w in words:
        letter_dir = FOLDER_DIR / w[0].upper()
        letter_dir.mkdir(exist_ok=True)
        word_file = letter_dir / f"{w}.txt"

        existing = set()
        if word_file.exists():
            with open(word_file, "r") as f:
                existing = set(int(line.strip()) for line in f if line.strip().isdigit())

        if book_id not in existing:
            existing.add(book_id)
            with open(word_file, "w") as f:
                for i in sorted(list(existing)):
                    f.write(f"{i}\n")

# 3 rondas de warmup + 8 medidas, igual que @Warmup/@Measurement de JMH en worker_java.
def test_index_sqlite(benchmark):
    benchmark.pedantic(insert_sqlite, setup=seed_sqlite, rounds=8, iterations=1, warmup_rounds=3)

def test_index_json(benchmark):
    benchmark.pedantic(insert_json, setup=seed_json, rounds=8, iterations=1, warmup_rounds=3)

def test_index_folders(benchmark):
    benchmark.pedantic(insert_folders, setup=seed_folders, rounds=8, iterations=1, warmup_rounds=3)
