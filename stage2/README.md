# Stage 2: Service-Oriented Architecture — Calima Data

Search Engine Project · Big Data · Grado en Ciencia e Ingeniería de Datos · ULPGC

The Stage 1 data layer as cooperating services on one machine: an **Ingestion Service**, an
**Indexing Service** and a **Search Service**, coordinated by a **Control Module**. Written in Java 17
with Javalin and Gson.

## Contents

1. [Architecture](#architecture)
2. [Build](#build)
3. [Run everything on one machine](#run-everything-on-one-machine)
4. [Configuration](#configuration)
5. [Try some queries](#try-some-queries)
6. [API specification and validation](#api-specification-and-validation)
7. [Tests](#tests)
8. [Benchmarks](#benchmarks)
9. [Where things are](#where-things-are)

## Architecture

```
                 Front-end (HTTP client)
                          |
                          |  GET /search, /search/filters, /books/{id}
   Single host            v
 +---------------------------------------------------------------------------------+
 |                 Search Service  (1..N replicas, HTTP thread pool)               |
 |                    ^                                     | read-only snapshot   |
 |                    | GET /books/{id}                     v                      |
 |  Control Module ---+---------------------------->  datamart (SQLite, WAL)       |
 |  (1 process,       |                                     ^ one writer at a time |
 |   N worker threads)| POST /index/update/{id}             |                      |
 |   control.db       +--------> Indexing Service (1..N) ---+                      |
 |                    |                 |        \                                 |
 |                    |                 |         \ reads files of ready books     |
 |                    |                 | GET /ingest/status/{id}                  |
 |                    | POST /ingest/{id}          \                               |
 |                    +--------> Ingestion Service (1..N) ---> datalake (files)    |
 |                                      | ingestion.db            time-based       |
 +--------------------------------------|------------------------------------------+
                                        v
                              Project Gutenberg
```

Each book moves through `PENDING -> INGESTED -> INDEXED -> PUBLISHED`, or ends in `FAILED`. The
Control Module records each transition only after the responsible service confirms the step. A
crash between the two just repeats the step, and each step is idempotent. Unfinished books are
claimed before new ones.

The reasons behind these choices are in [docs/DECISIONS.md](docs/DECISIONS.md), and the full
discussion and results are in the [report](docs/REPORT.md).

## Build

Requires **JDK 17** and **Maven 3.9**.

```bash
cd stage2
mvn clean package                                   # all services, with their tests
mvn -pl search-service -am package -DskipTests      # or a single service
```

Each module produces `target/<module>-1.0.0.jar`, which runs with `java -jar`. The services with
benchmarks also produce `target/<module>-1.0.0-benchmarks.jar`. Benchmarks live in `src/test/java`, so
they never reach the service jars.

The scripts need Python 3.10 or later:

```bash
python -m pip install -r scripts/requirements.txt
```

## Run everything on one machine

`--data-dir` holds the datalake, the datamart, the state databases and the logs. **Keep it out of
cloud-synced folders**, because SQLite needs a local file system.

```bash
python scripts/deploy.py up --data-dir C:/tmp/search            # one replica of each service
python scripts/deploy.py up --data-dir C:/tmp/search --indexing 2 --search 2 --control-workers 8
python scripts/deploy.py status --data-dir C:/tmp/search
python scripts/deploy.py down --data-dir C:/tmp/search
```

| Process | First replica | Further replicas |
|---|---|---|
| Control Module | `http://localhost:7000` | one process only |
| Ingestion Service | `http://localhost:7001` | 7011, 7021, ... |
| Indexing Service | `http://localhost:7002` | 7012, 7022, ... |
| Search Service | `http://localhost:7003` | 7013, 7023, ... |

To run one service by hand, set its variables and start its jar:

```bash
PORT=7003 DATAMART_PATH=C:/tmp/search/datamart/search.db java -jar search-service/target/search-service-1.0.0.jar
```

## Configuration

Every setting is an environment variable, or a system property of the same name. Each module's
defaults are in `src/main/resources/application.properties`.

| Variable | Used by | Default | Meaning |
|---|---|---|---|
| `PORT` | all | 7000 to 7003 | HTTP port |
| `HTTP_THREADS` | all | Javalin's default | Request threads. By default Jetty's pool grows up to 250 threads |
| `CORS_ALLOWED_ORIGINS` | all | empty | Comma-separated origins allowed from a browser. Set it on Search if a front-end uses another origin |
| `DATALAKE_PATH` | Ingestion, Indexing | `data/datalake` | Datalake root |
| `INGESTION_LEDGER_PATH` | Ingestion | `data/state/ingestion.db` | Ingestion ledger |
| `GUTENBERG_URL_TEMPLATE` | Ingestion | gutenberg.org | Download URL; `{id}` is replaced |
| `DOWNLOAD_TIMEOUT_MS` | Ingestion | 30000 | Download timeout |
| `INGEST_LEASE_MS` | Ingestion | 120000 | After this long, another request may take over an unfinished claim |
| `DATAMART_PATH` | Indexing, Search | `data/datamart/search.db` | Datamart |
| `INGESTION_URLS` | Indexing, Control | `http://localhost:7001` | Ingestion replicas, comma-separated |
| `INTERNAL_HTTP_TIMEOUT_MS` | Indexing | 10000 | Timeout of its calls to Ingestion |
| `REBUILD_THREADS` | Indexing | 4 | Threads that parse books during a rebuild |
| `YEAR_OVERRIDES_PATH` | Indexing | bundled list | CSV of `book_id,year` |
| `SQLITE_POOL_SIZE` | Ingestion, Indexing, Search | 8, 8, 16 | Database connections per process |
| `SEARCH_CACHE_SIZE` | Search | 1000 | Cached answers; 0 disables the cache |
| `CONTROL_DB_PATH` | Control | `data/state/control.db` | Workflow state |
| `INDEXING_URLS`, `SEARCH_URLS` | Control | ports 7002, 7003 | Replicas of each service, comma-separated |
| `CONTROL_WORKERS` | Control | 4 | Books processed concurrently |
| `CONTROL_POLL_MS` | Control | 50 | Wait when no task is ready |
| `INGEST_TIMEOUT_MS`, `INDEX_TIMEOUT_MS`, `PUBLISH_CHECK_TIMEOUT_MS` | Control | 60000, 60000, 5000 | Timeouts of each step |
| `TASK_LEASE_MS` | Control | 180000 | After this long, a claimed task may be claimed again |
| `MAX_ATTEMPTS`, `RETRY_BASE_MS`, `RETRY_MAX_MS` | Control | 8, 500, 30000 | Retries of a step and their backoff |
| `SQLITE_BUSY_TIMEOUT_MS` | all with a database | 10000; Indexing 30000; Search 5000 | Wait for the database lock, and also for a free connection |
| `SQLITE_SYNCHRONOUS` | all that write | `FULL` | `FULL` survives a power cut; `NORMAL` only a process crash |
| `FAULT_POINT` | all | unset | Test only: halts the process at a named line |

## Try some queries

With the system running, submit some books. This downloads them from Project Gutenberg:

```bash
curl -X POST http://localhost:7000/control/books -H "Content-Type: application/json" -d "{\"book_ids\":[1342,84,11,2701,2000]}"
curl http://localhost:7000/control/status                 # wait until all are PUBLISHED
curl "http://localhost:7003/search?q=whale"
curl "http://localhost:7003/search?q=monster&language=en&year=1818"
curl "http://localhost:7003/search?q=rabbit&author=carroll&limit=5&offset=0"
curl "http://localhost:7003/search/filters"
curl "http://localhost:7003/books/1342"
```

## API specification and validation

- [api/openapi.yaml](api/openapi.yaml): versioned specification of every endpoint.
- [api/ENDPOINTS.md](api/ENDPOINTS.md): separate inventories for front-end, internal and operational
  endpoints, with callers, timeouts and failure behaviour.
- [api/requests.http](api/requests.http): runnable examples with their expected results.

Two scripts check the running system, with no front-end and no external network. They deploy
everything against a local Gutenberg stand-in and the books in `fixtures/corpus/`:

```bash
python scripts/api_check.py --data-dir C:/tmp/api-check
python scripts/failure_check.py --data-dir C:/tmp/failure-check
```

**Expected result:** every line starts with `PASS`, the last line reads `N/N`, and the exit status is 0.

- `api_check.py` exercises all documented operations, including filters, empty results, invalid
  parameters and pagination. It validates each response against the OpenAPI schemas, and fails if a
  documented route was never exercised. Its report is `results/stage2/api_validation.json`.
- `failure_check.py` crashes real processes at exact lines, takes services down and runs concurrent
  duplicates across replicas, and then checks that nothing was lost, duplicated or partially visible.
  Its report is `results/stage2/failure_checks.json`.

To try the examples by hand, leave the services up:

```bash
python scripts/api_check.py --data-dir C:/tmp/api-check --keep-running
python scripts/deploy.py down --data-dir C:/tmp/api-check
```

## Tests

```bash
mvn test
```

## Benchmarks

Run them on an idle machine, after `mvn package`. Each script stores raw results and its exact
configuration under `results/stage2/`, and refuses data folders inside OneDrive.

```bash
python scripts/run_jmh.py                                                # component level, about 20 min
python scripts/bench_integration.py all --data-dir C:/tmp/bench          # whole system, about 1 hour
python scripts/plot_results.py                                           # charts and tables from the raw files
```

## Where things are

```
stage2/
  common/              Configuration, HTTP conventions, SQLite access, tokenizer
  ingestion-service/   Datalake writes and the ingestion ledger
  indexing-service/    Metadata extraction, postings and atomic publication
  search-service/      Query validation, ranking, filters and cache
  control-module/      Orchestrator and its task table
  benchmark-tools/     Gutenberg stand-in and HTTP load generator
  api/                 OpenAPI specification, inventories and HTTP examples
  fixtures/            Test corpus and the list of real books for benchmarks
  scripts/             Deployment, validation, benchmarks and charts
  docs/                Report, design decisions and plan
../results/stage2/     Raw benchmark data, validation reports and charts
```
