# Endpoint inventories

Specification version **2.0.0**. The full contract (parameters, schemas, status codes and
examples) is in [openapi.yaml](openapi.yaml). `scripts/api_check.py` validates the running services
against it and fails if a route in the specification was not exercised.

Status codes in **bold** mean the operation finished; *italics* mean it was only accepted.

## 1. Front-end-facing

| Method and route | Provider | Consumer | Purpose | Success | Expected errors | Retry |
|---|---|---|---|---|---|---|
| `GET /search` | Search | Front-end | Keyword search with `author`, `language` and `year` filters, ranking and pagination | **200** | 400, 503 | Read-only, safe |
| `GET /search/filters` | Search | Front-end | Values to offer in filter controls | **200** | 503 | Read-only, safe |
| `GET /books/{book_id}` | Search | Front-end; also Control Module as its final check | Details of one published book | **200** | 400, 404, 503 | Read-only, safe |

Pagination: `offset` defaults to 0 and `limit` to 20, with a maximum of 100. Ordering: BM25
descending, then `book_id` ascending. Filter rules: `author` is a case- and diacritic-insensitive
substring; `language` is an exact ISO 639-1 code; `year` is an exact year of original publication,
which many books lack.

**Browser access.** A front-end on another origin needs `CORS_ALLOWED_ORIGINS` set on the Search
Service, for example `https://app.example.org`. It is empty by default, so no cross-origin access
is granted. Front-ends never need local paths, database access or knowledge of the orchestrator.

## 2. Internal

| Method and route | Provider | Caller | Position in the workflow | Success | Expected errors | Caller timeout | Caller's behaviour on failure | Idempotency |
|---|---|---|---|---|---|---|---|---|
| `POST /ingest/{book_id}` | Ingestion | Control Module | Step 1, from PENDING | **200** | 400, 404, 409, 422, 502, 503 | 60 s | Retries 409, 5xx and connection errors with backoff on the next replica. FAILED on 400, 404, 422 | Repeats return `already_available`; concurrent calls download once |
| `GET /ingest/status/{book_id}` | Ingestion | Indexing | Readiness check before reading a book | **200** | 400, 503 | 10 s | Answers 503 to its own caller | Read-only |
| `GET /ingest/list` | Ingestion | Indexing | Start of a rebuild | **200** | 503 | 10 s | Answers 503 to its own caller | Read-only |
| `POST /index/update/{book_id}` | Indexing | Control Module | Step 2, from INGESTED | **200** | 400, 409, 500, 503 | 60 s | 409 `book_not_ready` returns the task to PENDING. Retries 500, 503 and connection errors with backoff. FAILED on 400 | Same content returns `unchanged`; concurrent calls publish once |
| `GET /books/{book_id}` | Search | Control Module | Step 3, from INDEXED: confirms the book is visible | **200** | 400, 404, 503 | 5 s | Retries with backoff without repeating indexing | Read-only |

Retries use exponential backoff from `RETRY_BASE_MS` (500 ms) up to `RETRY_MAX_MS` (30 s), with
jitter. After `MAX_ATTEMPTS` (8) failures of one step the task becomes FAILED, which
`POST /control/books/{id}/retry` reverses.

**Calls that are absent, and why.** No service notifies another: the orchestrator learns the outcome
from each response. There is no publish call, because the commit of `POST /index/update` already
publishes the book, and no cache-refresh call, because the Search cache keys its entries by index
version. The system uses no broker, schedule or shared memory, so there are no non-HTTP contracts
beyond the two shared-storage rules below.

**Shared storage rules.**

- *Datalake.* Written only by Ingestion and read only by Indexing. A file is read only after
  `GET /ingest/status` reports its book `available`, which is recorded after both files are complete.
- *Datamart.* Written only by Indexing and read only by Search, in read-only mode. A snapshot read
  sees each book completely or not at all, and `index_state.version` grows with every publication.

## 3. Operational and administrative

| Method and route | Provider | Consumer | Purpose | Success | Expected errors | Semantics |
|---|---|---|---|---|---|---|
| `GET /status` | All services | `deploy.py`, monitoring | Liveness and thread configuration of one process | **200** | none | Read-only |
| `POST /control/books` | Control Module | Operators, scripts | Submit books by list or range | *202* | 400 | Accepted only. Known books are ignored |
| `GET /control/books/{book_id}` | Control Module | Operators, scripts | State, errors and timings of one book | **200** | 400, 404 | How a caller observes completion |
| `POST /control/books/{book_id}/retry` | Control Module | Operators | Return a FAILED book to the queue | *202* | 400, 409 | Accepted only. A repeat answers 409 |
| `GET /control/status` | Control Module | Operators, benchmarks | Tasks per state and timing means | **200** | none | Read-only |
| `POST /index/rebuild` | Indexing | Operators | Rebuild the datamart atomically | **200** | 409, 500, 503 | Synchronous. Same content when repeated |
| `GET /index/status` | Indexing | Operators, benchmarks | Book count, version, last update and size | **200** | 503 | Read-only |
| `GET /index/status/{book_id}` | Indexing | Operators, validation | Whether one book is indexed | **200** | 400, 503 | Read-only |
