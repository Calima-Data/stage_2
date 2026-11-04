# Stage 2 Implementation Plan

**Goal:** turn the Stage 1 data layer into cooperating Java 17 / Javalin services
(Ingestion, Indexing, Search) coordinated by an orchestrator, with a versioned API
contract, runnable validation and reproducible benchmarks, after applying the
corrections to Stage 1.

**Architecture:** classical orchestration. A Control Module owns a persistent task table
and drives each book through `PENDING -> INGESTED -> INDEXED -> PUBLISHED` over HTTP.
The Ingestion Service owns a time-based datalake and a ledger; the Indexing Service owns
a SQLite datamart in WAL mode; the Search Service reads that datamart through read-only
snapshots. The motivation for each choice is in [DECISIONS.md](DECISIONS.md).

**Tech stack:** Java 17, Javalin 6.1.3, Gson 2.11, slf4j-simple 2.0.9, sqlite-jdbc,
JUnit 5, JMH; Python 3 for deployment, validation and plotting scripts.

**Spec:** `Contexto/stage_2_service_oriented_architecture.pdf` and
`Contexto/Correciones Stage_1.txt` (kept local and untracked).

## Global constraints

- Every process runs on one host; ports, paths, replica counts and thread settings are configurable.
- Each service is a Maven project producing an executable jar.
- JMH benchmarks live in `src/test/java`, with at least 5 warm-up and 10 measurement iterations, fixed heap, CSV output.
- A book is indexable only after ingestion has completed; search reads a consistent published state.
- Repeated requests must not create duplicates or corrupt data.
- No number is reported unless a committed script produced it from a run on this host.
- Nothing is pushed to GitHub.
- Benchmarks do not run on the OneDrive folder, which would add sync interference.
- No directory is named `control/`, `datalake/` or `datamarts/` in tracked source: `.gitignore` ignores those names anywhere.

## Review focus

1. Crash between finishing a step and recording it: the retry must converge to the same state, with no duplicate time-based copy.
2. Queries with no indexable token (`q=!!!`), unknown parameters, an out-of-range `limit`, and a non-existent datamart: documented errors, never a 500.
3. Concurrent duplicate `POST /ingest/{id}` and `POST /index/update/{id}`, across replicas: one effective write.
4. Reads during `POST /index/rebuild`: search sees the complete old state or the complete new state, never a partial one.
5. Non-English and non-ASCII books (diacritics, `THIS` instead of `THE` in the Gutenberg markers, BOM, missing author or year).

## Tasks

| # | Task | Deliverable and proof |
|---|---|---|
| 1 | Stage 1 code corrections | Shared Python module, persist-before-mark, term lookup, pending-first coordinator, no hard-coded book lists; `pytest` |
| 2 | Stage 1 experiments | Script producing raw CSV/JSON and charts for items 2-10; addendum with table of contents |
| 3 | Maven skeleton and `common` | Aggregator, config, JSON/error handling, SQLite helper, tokenizer; JUnit |
| 4 | Ingestion Service | Atomic writes, ledger with leases, `/ingest/*`; JUnit |
| 5 | Indexing Service | Metadata, TF postings, atomic publication, `/index/*`; JUnit |
| 6 | Search Service | Validation, BM25, filters, pagination, snapshot reads, cache, `/search`, `/books`, `/search/filters`; JUnit |
| 7 | Control Module | Persistent tasks, atomic claim, retries, replica failover, `/control/*`; JUnit |
| 8 | Contracts and validation | OpenAPI, endpoint inventories, `.http` examples, `api_check.py`, `failure_check.py` |
| 9 | Benchmarks | JMH suites and CSV; mock upstream, load tester, integration runner, plots |
| 10 | Report and summary | README, report (Markdown and PDF), requirement traceability, `RESUMEN_STAGE2.md` |
