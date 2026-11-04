# Design decisions

Each entry states what the assignment leaves open, what was decided, and why.

## Architecture

**D1. Coordination: classical orchestration with a Control Module.** The assignment accepts several
mechanisms. Orchestration keeps the whole workflow state in one inspectable table, needs no broker
process, and directly answers the Stage 1 correction about the missing central coordinator. Its
cost is a central component, which must be made highly available in Stage 3.

**D2. Publication is the commit of one SQLite transaction.** The assignment asks that an update be
published before search can read it. Metadata, postings and the version counter change in one
transaction, and search reads inside a snapshot, so a book is visible completely or not at all. A
separate publish step would add a state where a book is indexed but unpublished, and a crash window
to recover from, with no benefit for this workload.

**D3. No cache-refresh call.** The assignment mentions refreshing caches, if present. Cache entries
carry the index version and only hit for the version of the current snapshot. Invalidation therefore
cannot be missed, including across replicas, and needs no notification.

**D4. The final workflow step checks through the Search API.** `PUBLISHED` is recorded only after
`GET /books/{id}` answers 200 on the Search Service. This proves the outcome that matters, visibility
to front-ends, rather than inferring it from the indexer's response.

**D5. Readiness is asked over HTTP.** The Indexing Service calls `GET /ingest/status/{id}` before reading
a book, even though the orchestrator already ordered the steps. This enforces "eligible only after
ingestion has completed" at the place the data is read, whoever made the request, and lets the
checksum in the answer detect a datalake that does not match the ledger.

**D6. "Pending first" means the most advanced unfinished work first.** The claim order is INDEXED, then
INGESTED, then PENDING. After an interruption the system finishes what it started before it downloads
anything new.

## Storage

**D7. Datalake: the time-based layout, with a ledger.** The assignment requires the hierarchy from
Stage 1 and its example path is time-based (`datalake/20251008/14/1342`). The Stage 1 measurements in
`results/stage1/discovery.csv` show this layout's weakness: finding a book by ID has to walk the
folders. The ledger removes that cost by recording each book's path. It also fixes a recovery problem:
the path chosen by the first attempt is kept, so a retry in a later hour cannot leave a second copy.

**D8. Datamart: SQLite in WAL mode.** Stage 1 found it the cheapest structure to update, and it scales
better than JSON or folders (`results/stage1/scaling.csv`). WAL lets readers in other processes keep
a snapshot during a write, and `BEGIN IMMEDIATE` is a write lock that works across replicas. The limit
is one writer at a time on one host, which the benchmarks quantify. SQLite must not sit on a network
file system, so this has to change for Stage 3.

**D8b. The write-ahead log is bounded at 64 MB by the writers.** SQLite restarts the log only when no
connection is using it. Constant reads, or replicas taking turns to write, never leave that moment, and
the first benchmarks saw 1.3 to 1.9 GB of log and index for a 61 MB index. Writers now run a `TRUNCATE`
checkpoint before writing once the log passes the limit, and wait for one already running. The alternative,
a background thread, would still need the writers to stop for it. A book can write 53 MB by itself and
replicas overlap, so the log can pass 64 MB: the highest peak in the final campaign is 119 MB. A checkpoint that cannot
finish within the busy timeout does not stop the write.

**D8c. Postings stay clustered by term, and are inserted in that order.** Clustering by term makes search
read one contiguous range per term, but one book then touches about one leaf page per distinct term, so
publishing gets slower as the index grows. This trade favours reads, as a search engine should. Sorting
each book before inserting costs no memory and visits each leaf once, which saved 19% on a 500-book index.
A larger SQLite page cache would save about 23% more (748 against about 577 ms per book), but it would be per
connection, so it is left out and noted for Stage 3, where the write path has to change anyway.
Evidence: `results/stage2/evidence/publish_diagnosis.txt`.

**D9. Term frequency in the postings.** Stage 1 stored `(term, book_id)`. The assignment now asks for
ranking, which needs frequencies, so postings gained `tf` and books gained `word_count`.

**D10. `synchronous=FULL` by default.** Control's state says a book is published, so it must survive a
power cut as well as a process crash. The cost of this choice is measured against `NORMAL` rather
than assumed.

**D11. Each process owns its store.** Ingestion owns the ledger and the datalake, Indexing owns the
datamart, Control owns the task table. Search opens the datamart read-only. No service reads another
service's database, so these contracts can be replaced by remote calls in Stage 3.

## API

**D12. Publication year comes from the header's "Original publication" line, or else from a curated
list; otherwise it is null.** The API must filter by publication year, but Project Gutenberg headers
rarely state it: none of the 20 real books in the benchmark corpus do, not even *The Great Gatsby*.
The extractor still reads that line, in case a book has it. The "Release date" field
is the date of the eBook and would date *Pride and Prejudice* 1998. A wrong year is worse than none,
so the curated list (`publication_years.csv`) covers well-known books, and the rest have
`year: null` and never match the filter. The contract states this limitation.

**D13. Language is an ISO 639-1 code.** The example responses use `en` while headers say `English`. The
indexer maps the 20 languages most common on Gutenberg and stores `und` for others. A book listing
several languages keeps the first. A well-formed code that no book has returns an empty page rather
than an error, because which languages exist depends on the corpus; `GET /search/filters` lists them.

**D14. Several query terms mean AND.** The assignment shows one term. Search engines commonly narrow
results as words are added, and ranking still orders them.

**D15. Ranking is BM25, with `book_id` as a tie-break.** It accounts for term rarity and book length,
with the standard parameters `k1=1.2` and `b=0.75`. The tie-break keeps pages stable.

**D16. Author filter: a substring, ignoring case and diacritics.** People rarely type a catalogue name
such as "Mary Wollstonecraft Shelley" in full. `%` and `_` are escaped, so they match literally.

**D17. Unknown parameters are errors.** A misspelled `lang=en` that was ignored would return
unfiltered results that look correct. Repeated parameters are refused for the same reason.

**D18. Pagination: `offset` 0 and `limit` 20 by default, `limit` at most 100, `offset` at most 1000000.**
An offset beyond the matches is a valid empty page, and `total` stays correct.

**D19. Tokenization is Unicode-aware, and removes diacritics.** Stage 1's rule corrupted non-English
text. Queries use the same code as the index, so `espana` finds `España`.

**D20. Ingestion and indexing endpoints are synchronous.** The assignment's examples are, and a caller
gets a definite outcome. Control's `POST /control/books` is asynchronous (`202`) because a batch can
take a long time, and `GET /control/books/{id}` shows its progress. Rebuild is synchronous and atomic
and needs a generous client timeout.

**D21. Extra endpoints.** `GET /books/{id}` and `GET /search/filters` are the optional routes in the
assignment; a results page and filter controls need them. `GET /index/status/{id}` supports
operators. The `/control/*` routes exist because the orchestrator needs a way to receive work.

## Concurrency and deployment

**D22. Replicas are configured by endpoint lists, and the caller distributes requests.** Clients rotate
over the list and skip a replica that cannot be reached. No gateway or load balancer is added, because
the assignment makes it optional and it would be one more process sharing the host. A front-end on a
single URL would need one in Stage 3, or now if several Search replicas are exposed.

**D23. One Control Module, with many worker threads.** Replicas would need coordination the system
does not otherwise need. Claims are atomic with leases, so the table would support several instances,
but startup assumes one by releasing every claim, which is what makes recovery immediate. Running
several would require removing that step, and is left for Stage 3.

**D24. Thread settings are explicit.** `HTTP_THREADS` caps request threads, and the default keeps
Javalin's Jetty pool. Search's `SQLITE_POOL_SIZE` bounds read connections, and a request that finds none
free within the busy timeout answers `503` rather than queuing without limit. Control's concurrency is
`CONTROL_WORKERS`.

## Project

**D25. A Maven aggregator with a shared `common` module, not fully independent projects.** The assignment
describes each service as a standalone Maven project with an executable jar. Each one has its own pom
and jar, and can be built alone with `mvn -pl <module> -am package`. Sharing `common` avoids exactly
the duplicated logic that the Stage 1 correction criticized.

**D26. A local Gutenberg stand-in for benchmarks.** Measuring against the real site would measure the
Internet and put load on a public service. The stand-in serves real texts; a separate run with a few
books goes through the real site, to prove the whole path works.

**D27. Integration benchmarks are included, although optional.** The questions the assignment most wants
answered, on bottlenecks and coordination cost, can only be answered end to end.

**D28. Benchmark data lives outside OneDrive.** The repository sits in a synced folder. Sync activity
would falsify disk measurements and can interfere with SQLite's files, so the scripts refuse such a
path.

**D29. The repository name is not changed.** The assignment requires `github.com/<group>/stage_2`, but
this work is on branch `stage-2` of `stage_1`, as requested, and nothing has been pushed.
