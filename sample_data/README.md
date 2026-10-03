# Sample data

Small, ready-to-inspect slice of real pipeline output, so instructors can test
the datamarts without running the full ingestion pipeline first.

- `datalake_sample/book_based/books/11/` — one real downloaded book (*Alice's
  Adventures in Wonderland*, Project Gutenberg ID 11) in the book-based
  datalake layout, header and body already split.
- `datamarts_sample/inverted_index.json` — monolithic JSON inverted index built
  from 4 sample books (IDs 1342, 84, 11, 16).
- `datamarts_sample/inverted_index.db` — SQLite datamart with the same 4 books,
  containing both the `postings` table (inverted index) and the `books`
  metadata table.
- `control_sample/downloaded_books.txt` — example control file listing which
  book IDs have already been ingested.

(Named `*_sample` rather than `datalake`/`datamarts`/`control` so these files
aren't swallowed by the root `.gitignore`, which ignores those directory
names anywhere in the tree — the real, git-ignored working directories keep
those plain names.)

To try the datamarts immediately:

```bash
sqlite3 sample_data/datamarts_sample/inverted_index.db "SELECT * FROM books;"
sqlite3 sample_data/datamarts_sample/inverted_index.db "SELECT book_id FROM postings WHERE term='rabbit';"
```

To regenerate this data (or scale it up to more books), see the root
[README.md](../README.md) for how to run the full pipeline in any of the
three implemented languages (Python, Java, Node.js).
