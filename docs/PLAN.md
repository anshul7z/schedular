# Plan: NoSQL → SQL Migration Scheduler (Spring Boot)

## Context
`schedular` is a Spring Boot **4.1.1 / Java 21** service that reads data from a NoSQL database and writes it into a SQL
database, either on a cron schedule or on demand. The architecture is described in [HLD.md](HLD.md); this file is the
delivery plan and its progress log.

Decisions:
- **Source (v1):** MongoDB, behind a pluggable `SourceConnector` interface so Cassandra, DynamoDB and others can be
  added later.
- **Targets:** PostgreSQL (done), then MySQL/MariaDB, SQL Server and Oracle (Phase 4), each through its own
  `SqlDialect`.
- **Mapping:** configured per job. A nested object can be flattened into columns or stored as a JSON column, and an
  array can be exploded into a child table.
- **Job management:** jobs are created through a REST API, stored in a metadata DB, and scheduled with Quartz.

## Progress
| Phase | Status | Commit | Notes |
|---|---|---|---|
| 1. Foundation | ✅ Done (2026-10-02) | `525a04a` | Connection API, password encryption, metadata schema, `local` profile |
| 2. Engine | ✅ Done (2026-10-04) | `bdc153a` | MongoDB → PostgreSQL, FULL **and** INCREMENTAL sync (INCREMENTAL was pulled forward from Phase 3), checkpoints, dead letters |
| 3. Scheduling & API | ✅ Done (2026-10-04) | this commit | Clustered Quartz, job/run REST API, cancel, mapping inference. 71 tests pass. |
| 4. More targets & hardening | **Next** | | See [Phase 4](#phase-4--more-targets-and-hardening-next) |
| 5. Extensions | Not started | | See [Phase 5](#phase-5--extensions-optional) |

---

## Delivery phases

### Phase 1 — Foundation ✅
**Built:**
- Dependencies, `application.yml`, a `local` profile (embedded H2), and `docker-compose.yml` with PostgreSQL, MongoDB
  (seeded) and MySQL.
- Flyway `V1`: `connection_def`, `migration_job`, `collection_mapping`, `checkpoint`, `job_run`,
  `dead_letter_record`. Hibernate only validates the schema.
- Entities and repositories.
- Connection API: CRUD, test a saved or unsaved connection, list collections or tables.
- Passwords encrypted with AES-256-GCM (`SCHEDULAR_SECRET_KEY`). They are write-only in the API, and URIs that embed a
  password are rejected.
- Errors returned as RFC 9457 problem JSON.

### Phase 2 — Migration engine ✅
**Built:**
- `MongoSourceConnector`: one cursor per collection, sorted by `_id` (FULL) or `(watermark, _id)` (INCREMENTAL).
- `MappingCompiler` → `MappingPlan`: strict validation, so unknown properties, bad identifiers and duplicate columns
  are rejected.
- `MappingEngine` + `TypeCoercer`: FLATTEN, JSON and CHILD_TABLE; BSON values converted to each column type.
- `PostgresDialect`, `SchemaManager` (creates tables and columns, and checks the key needed for upserts), and
  `JdbcTargetWriter`.
- `MigrationExecutor`:
  - each batch is written in one transaction, and the checkpoint is saved only after it commits
  - transient errors are retried with backoff
  - if the database rejects a row, the batch is retried row by row
  - documents that still fail go to the dead-letter table
- `StaleRunCleaner` and Flyway `V2` (wider checkpoint columns).

**Changes from the original plan:**
- **No `DataSourceRegistry`.** Each run opens its own small Hikari pool and closes it at the end, so editing or
  deleting a connection can never break a run in progress.
- **`defaultNestedStrategy` was dropped** from the mapping JSON. It had no effect in the engine, and unknown mapping
  properties are now rejected.
- **`TRUNCATE_AND_LOAD` truncates, then upserts**, so a resumed load stays idempotent.
- **INCREMENTAL reads `watermark ≥ lastWatermark`** (not `>`), so documents on the boundary are never missed.
- **Checkpoints are type-preserving Extended JSON**, so an ObjectId resumes as an ObjectId.
- **PostgreSQL DECIMAL is `numeric`**, not `numeric(38,10)`, so values keep their exact scale.
- **Tests run against embedded MongoDB and PostgreSQL** processes instead of Testcontainers, because Docker isn't
  available on the dev machine.

### Phase 3 — Scheduling and API ✅
**Built:**
- Quartz JDBC job store, clustered, with tables in Flyway `V3`.
- `SchedulerService` keeps triggers in step with the jobs table and reconciles them at startup.
- `MigrationQuartzJob` uses `@DisallowConcurrentExecution`, and missed firings are not replayed.
- Job API: CRUD with full validation, run now (`202`), pause/resume, and reset checkpoint. Editing, deleting or
  resetting a running job returns `409`.
- Run API: history, details, cancel and dead letters, all paged.
- Mapping inference with `SchemaInferrer`.
- `job_run.node_id`, so that crash cleanup is safe in a cluster.
- Graceful shutdown: runs in progress are cancelled and given up to 30 s to stop.

**Changes from the original plan:**
- **Mapping inference belongs to the connection:** `GET /api/connections/{id}/collections/{collection}/mapping`,
  instead of `POST /api/jobs/{id}/infer-mapping`. You need a mapping *before* a job exists.
- **Manual runs don't go through Quartz.** The API claims the job and runs it on a background thread, so it can
  return the run id at once and refuse an overlapping run. Quartz only fires cron schedules.
- **Cancel never interrupts threads.** It is a flag checked between batches and during retry waits; an interrupt
  during JDBC I/O closes the connection mid-statement.
- **The Quartz JDBC delegate is detected from the metadata DB** (`QuartzConfig`). Spring's default didn't pick
  `PostgreSQLDelegate`, and the standard delegate can't read PostgreSQL `bytea`.
- **`idleWaitTime` is 5 s** (Quartz's default is 30 s), so new schedules take effect promptly.

### Phase 4 — More targets and hardening (next)
**Targets:**
- `MySqlDialect` covering MySQL and MariaDB:
  - upsert with `INSERT … ON DUPLICATE KEY UPDATE`
  - `json` columns
  - `rewriteBatchedStatements=true`
- `SqlServerDialect`:
  - upsert with `MERGE`
  - `nvarchar(max)` for JSON
  - `datetime2`
- `OracleDialect`:
  - upsert with `MERGE`
  - `ADD (col type)` syntax for new columns
  - unquoted names are folded to upper case
  - `clob` for JSON
- Each dialect gets unit tests of its generated SQL, plus Testcontainers integration tests that run in CI or anywhere
  Docker is available (HLD R6).

**Hardening:**
- **API security:** Spring Security, at least HTTP basic or an API key (HLD R9).
- **Metrics:** Micrometer counters and timers per job: docs read, rows written, docs failed, batch and run duration.
- **OpenAPI:** Swagger UI through springdoc, at a version compatible with Boot 4.
- **Stuck runs:** runs left `RUNNING` by a node that is gone for good (HLD R8). Add an admin "mark failed" action, or
  expire runs whose node has no heartbeat.
- **Fault-injection test:** drop the target connection mid-run and check that retries resume cleanly (HLD R7).

### Phase 5 — Extensions (optional)
- MongoDB change-stream CDC with a resume token: near-real-time sync that also propagates deletes (HLD R4).
- More sources through the `SourceConnector` SPI (Cassandra, DynamoDB).
- Failure notifications (webhook or email), triggered when a run fails or dead letters pass a threshold.
- A web UI dashboard.
- Key rotation for `SCHEDULAR_SECRET_KEY` (multiple `vN:` key versions plus a re-encrypt task).

---

## Reference: how it is built

### Architecture
```
REST API ──> Connection / Job / Run services ──> Metadata DB (jobs, mappings, runs, checkpoints, Quartz tables)
                     │                    │
                     │ run now            ▼
                     │           Quartz (cron, clustered, one run per job at a time)
                     ▼                    │
              MigrationExecutor  <────────┘   claim job → one run, batch by batch
   ┌─────────────────┼──────────────────────────┐
   ▼                 ▼                          ▼
SourceConnector   MappingCompiler/Engine     JdbcTargetWriter + SchemaManager
(Mongo cursor,    (doc → parent row + child  (one transaction per batch,
 filter, id or     rows, TypeCoercer)          SqlDialect per database)
 watermark)
                     │
                     ▼
        checkpoint (after commit) · run counters · dead letters
```

### Package layout (`src/main/java/com/data/schedular/`)
```
config/                SchedularProperties, QuartzConfig
api/                   ConnectionController, JobController, RunController, GlobalExceptionHandler, dto/*
domain/                ConnectionDef, MigrationJob, CollectionMapping, Checkpoint, JobRun, DeadLetterRecord + enums
repository/            Spring Data JPA repositories
service/               ConnectionService, JobService, RunService, SecretCipher, exceptions
service/connectivity/  ConnectionResolver, ResolvedConnection, Mongo/Jdbc connection probes, MongoClientFactory
scheduler/             SchedulerService, MigrationQuartzJob
engine/                MigrationExecutor, RunContext, JobSnapshot, StaleRunCleaner
engine/source/         SourceConnector (SPI), SourceConnectorFactory, ReadRequest, SourceRecord, mongo/*
engine/mapping/        MappingSpec, MappingCompiler, MappingPlan, MappingEngine, TypeCoercer, BsonValues,
                       DocumentPaths, SchemaInferrer
engine/target/         JdbcTargetWriter, SchemaManager, dialect/ (SqlDialect, PostgresDialect, DialectFactory)
```

### Metadata DB (Flyway)
| Version | Contents |
|---|---|
| `V1__init` | `connection_def`, `migration_job`, `collection_mapping`, `checkpoint`, `job_run`, `dead_letter_record` |
| `V2__widen_checkpoint_values` | `checkpoint.last_id` / `last_watermark` widened to 2000 characters |
| `V3__quartz_and_run_node` | Quartz `QRTZ_*` tables (its PostgreSQL script, which also runs on H2) and `job_run.node_id` |

The metadata DB is PostgreSQL in production. H2 is used by the `local` profile and the tests. Both are tested.

### Mapping, sync and write modes
The mapping JSON format, the type table, sync modes and write modes are documented in HLD §6.2 and §7.

### REST API
| Method | Path | Purpose |
|---|---|---|
| GET/POST, GET/PUT/DELETE | `/api/connections`, `/api/connections/{id}` | Manage connections (password write-only) |
| POST | `/api/connections/{id}/test`, `/api/connections/test` | Test a saved connection, or settings before saving |
| GET | `/api/connections/{id}/collections` | List Mongo collections or SQL tables |
| GET | `/api/connections/{id}/collections/{collection}/mapping` | Suggest a mapping from sampled docs |
| GET/POST, GET/PUT/DELETE | `/api/jobs`, `/api/jobs/{id}` | Manage jobs and their mappings |
| POST | `/api/jobs/{id}/run` | Run now (`202` + run) |
| POST | `/api/jobs/{id}/pause`, `/resume`, `/reset-checkpoint` | Schedule control; forget progress |
| GET | `/api/jobs/{id}/runs` | Run history (paged) |
| GET / POST | `/api/runs/{runId}`, `/api/runs/{runId}/cancel` | Run details; cancel |
| GET | `/api/runs/{runId}/dead-letters` | Documents that failed (paged) |

### Verification
Run `.\mvnw.cmd test`. No Docker is needed: MongoDB 8 and PostgreSQL 17 run as embedded processes. Current suite:
71 tests.

| Area | Tests |
|---|---|
| Unit | `MappingCompilerTest`, `MappingEngineTest` (incl. type coercion), `SchemaInferrerTest`, `PostgresDialectTest`, `ConnectionResolverTest`, `SecretCipherTest` |
| Engine, end to end | `MigrationExecutorTest`: nested data, idempotent re-runs, 2,345 docs across batches, dead letters (mapping and DB errors), resume, incremental, truncate-and-load, schema growth, clear failures, crash cleanup |
| API, end to end | `ConnectionControllerTest`, `JobApiTest`: create/run/pause/resume/update/delete, validation, 409s while running, cancel and resume, dead letters, inferred mapping used as is |
| Scheduling | `ScheduledRunTest`: a live Quartz scheduler fires a cron job and stops when it is paused |
| Production setup | `PostgresMetadataDbTest`: the app on a PostgreSQL metadata DB (Flyway V1–V3, Hibernate validation, a migration, Quartz `bytea`) |

To try it by hand:
1. Start the app with `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"`, or use
   `docker compose up -d` with the default profile.
2. Create connections and a job (see the README for curl examples), then call `POST /api/jobs/{id}/run`.
3. Poll `GET /api/runs/{runId}` and query the target tables.
