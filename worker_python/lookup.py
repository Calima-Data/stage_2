"""Búsqueda básica término -> postings sobre las tres estructuras del índice invertido.

Uso:
    python lookup.py rabbit
    python lookup.py rabbit --structure sqlite
    python lookup.py rabbit --json
"""
import argparse
import json
import sqlite3

from pipeline_common import DB_PATH, INDEX_FOLDER_DIR, INDEX_JSON_PATH, read_ids, tokenize


def lookup_json(term: str) -> list:
    if not INDEX_JSON_PATH.exists():
        return []
    with open(INDEX_JSON_PATH, "r", encoding="utf-8") as f:
        return sorted(json.load(f).get(term, []))


def lookup_folder(term: str) -> list:
    return sorted(read_ids(INDEX_FOLDER_DIR / term[0].upper() / f"{term}.txt"))


def lookup_sqlite(term: str) -> list:
    if not DB_PATH.exists():
        return []
    conn = sqlite3.connect(f"file:{DB_PATH.as_posix()}?mode=ro", uri=True)
    try:
        rows = conn.execute("SELECT book_id FROM postings WHERE term = ? ORDER BY book_id", (term,))
        return [row[0] for row in rows]
    except sqlite3.OperationalError:  # la tabla postings aún no existe
        return []
    finally:
        conn.close()


LOOKUPS = {"json": lookup_json, "folder": lookup_folder, "sqlite": lookup_sqlite}


def normalize_term(raw: str) -> str:
    """Aplica la misma normalización que al indexar; exige exactamente un término."""
    terms = tokenize(raw)
    if len(terms) != 1:
        raise ValueError(f"Se esperaba un único término y se obtuvo: {sorted(terms)}")
    return terms.pop()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("term")
    parser.add_argument("--structure", choices=[*LOOKUPS, "all"], default="all")
    parser.add_argument("--json", action="store_true", help="salida JSON, para scripts")
    args = parser.parse_args()

    term = normalize_term(args.term)
    names = list(LOOKUPS) if args.structure == "all" else [args.structure]
    answers = {name: LOOKUPS[name](term) for name in names}
    if args.json:
        print(json.dumps(answers))
    else:
        for name, ids in answers.items():
            print(f"{name:7s} {term}: {ids}")


if __name__ == "__main__":
    main()
