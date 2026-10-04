# Plan: NoSQL → SQL Migration Scheduler (Spring Boot)

## Context
The repo `C:\Users\Admin\projects\schedular` is a fresh Spring Boot **4.1.1 / Java 21** skeleton (`com.data.schedular.SchedularApplication`, only `spring-boot-starter` in `pom.xml`). The goal is a scheduler service that, on a cron schedule or on demand, reads data from a NoSQL database and writes it into a SQL database.

Decisions so far:
- **Source (v1):** MongoDB, behind a pluggable `SourceConnector` interface so Cassandra, DynamoDB and others can be added later.
- **Targets:** PostgreSQL, MySQL/MariaDB, SQL Server and Oracle, each through its own `SqlDialect`. You only need the JDBC drivers for the targets you use.
- **Mapping:** configured per job. Each nested object can be flattened into columns, stored as a JSON column, or (for arrays) exploded into a child table.
- **Job management:** jobs are created through a REST API, stored in a metadata DB, and scheduled with Quartz.

## Progress
| Phase | Status | Notes |
|---|---|---|
| 1. Foundation | ✅ Done (2026-10-02) | Connection API, encryption, metadata schema, `local` profile |
| 2. Engine | ✅ Done (2026-10-04) | MongoDB → PostgreSQL. FULL **and** INCREMENTAL sync (INCREMENTAL was pulled forward from Phase 3). Tests run against embedded MongoDB and PostgreSQL. |
| 3. Scheduling & API | Next | Quartz, job CRUD, run/cancel endpoints, run history, infer-mapping |
| 4. More targets & hardening | — | |
| 5. Extensions | — | |

**Changes from the original plan, made during Phase 2:**
- There is **no `DataSourceRegistry`**. Each run opens its own small Hikari pool and closes it at the end, so editing
  or deleting a connection can never break a run in progress.
- `defaultNestedStrategy` was dropped from the mapping JSON. It had no effect in the engine, and unknown mapping
  properties are now rejected.
- In `TRUNCATE_AND_LOAD`, the target tables are truncated and then the load *upserts*, so a resumed load stays
  idempotent.
- INCREMENTAL reads `watermark ≥ lastWatermark` (not `>`), so documents on the boundary are never missed.
- `V2` widened the checkpoint columns. Quartz tables will therefore be `V3`.
- PostgreSQL DECIMAL is `numeric`, not `numeric(38,10)`, so values keep their exact scale.

---

## 1. High-level architecture

```
REST API ──> Job/Connection Service ──> Metadata DB (jobs, mappings, runs, checkpoints, Quartz tables)
                     │
                     ▼
              Quartz Scheduler (cron, clustered, no concurrent runs per job)
                     │ triggers
                     ▼
            MigrationExecutor (one run)
   ┌─────────────────┼──────────────────────────┐
   ▼                 ▼                          ▼
SourceConnector   MappingEngine             TargetWriter
(Mongo: batched   (doc → rows for parent    (JDBC batch upsert,
 cursor, filter,   + child tables, type      SqlDialect per DB,
 watermark)        coercion)                 SchemaManager DDL)
                     │
                     ▼
            Checkpoint + RunHistory + DeadLetter
```

Each run loops: read a batch of N docs from the checkpoint → transform → write in one SQL transaction (upsert) → commit → save the checkpoint. Because writes are upserts, a run can be restarted after a crash and continue from the last checkpoint without duplicating rows.

## 2. Dependencies (`pom.xml`)
- `spring-boot-starter-web`: REST API
- `spring-boot-starter-validation`: request DTO validation
- `spring-boot-starter-quartz`: scheduling (JDBC job store)
- `spring-boot-starter-data-jpa` + `flyway-core` (+ `flyway-database-postgresql`): metadata DB entities and migrations
- `spring-boot-starter-actuator`: health and metrics
- `org.mongodb:mongodb-driver-sync`: per-job Mongo clients created in code, since each job points at its own URI. We do **not** use Spring Data Mongo auto-config for this.
- Runtime JDBC drivers: `postgresql`, `mysql-connector-j`, `mariadb-java-client`, `mssql-jdbc`, `ojdbc11`
- `HikariCP` (comes with starter-jdbc): a small pool per run for the target
- `springdoc-openapi-starter-webmvc-ui`: Swagger UI, using a version compatible with Boot 4
- Test: `spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers` modules for `mongodb`, `postgresql`, `mysql`, `mssqlserver`
- Note: Boot 4 ships **Jackson 3** (`tools.jackson.*` packages), so use it for JSON (de)serialization of mapping config and JSON columns.

## 3. Package layout (`src/main/java/com/data/schedular/`)
```
config/        QuartzConfig, ObjectMapper/Jackson config, CryptoConfig, OpenApiConfig
api/           ConnectionController, JobController, RunController, GlobalExceptionHandler, dto/*
domain/        entities: ConnectionDef, MigrationJob, CollectionMapping, FieldMapping,
               JobRun, Checkpoint, DeadLetterRecord; enums: DbType, SyncMode, NestedStrategy, RunStatus
repository/    Spring Data JPA repositories for the above
service/       ConnectionService (CRUD + test connection), JobService (CRUD + (un)schedule),
               RunService (history, cancel), SecretCipher (AES-GCM encrypt/decrypt passwords)
scheduler/     MigrationQuartzJob (@DisallowConcurrentExecution), SchedulerService (register/update/pause triggers)
engine/        MigrationExecutor, RunContext, BatchResult
engine/source/ SourceConnector (SPI), SourceConnectorFactory, mongo/MongoSourceConnector, mongo/BsonValueConverter
engine/mapping/MappingEngine, SchemaInferrer (samples docs → suggested mapping), RowSet/TableRows, TypeCoercer
engine/target/ JdbcTargetWriter, SchemaManager (create/alter tables, key check), TargetSchemaException
engine/target/dialect/ SqlDialect (SPI), PostgresDialect, MySqlDialect, SqlServerDialect, OracleDialect, DialectFactory
```

## 4. Core abstractions
```java
interface SourceConnector extends AutoCloseable {
  void open(ConnectionDef conn);
  Stream<Map<String,Object>> sample(String collection, int n);              // for schema inference
  Iterator<List<SourceRecord>> read(ReadRequest req);                       // batched; filter, watermark, batchSize
  long estimateCount(String collection, Document filter);
}
record SourceRecord(Object id, Map<String,Object> data, Object watermarkValue) {}

interface SqlDialect {
  String quote(String ident);
  String sqlType(LogicalType t, Integer length);           // STRING, INT, LONG, DECIMAL, BOOL, TIMESTAMP, JSON, BINARY, UUID
  String upsertSql(TableDef t, List<String> cols, List<String> keyCols);
  //   PG: INSERT .. ON CONFLICT DO UPDATE; MySQL: ON DUPLICATE KEY UPDATE; SQLServer/Oracle: MERGE
  String createTableSql(TableDef t); String addColumnSql(TableDef t, ColumnDef c);
  void bind(PreparedStatement ps, int idx, Object v, LogicalType t);   // JSON → jsonb/JSON/NVARCHAR(MAX)/CLOB
}
interface TargetWriter { void ensureSchema(MappingPlan p); BatchResult write(RowSet rows); }
```

## 5. Mapping model (per job, stored as JSON on `CollectionMapping`)
```json
{
  "sourceCollection": "orders",
  "targetTable": "orders",
  "primaryKey": {"source": "_id", "column": "id", "type": "STRING"},
  "fields": [
    {"path": "customer.name", "column": "customer_name", "type": "STRING"},
    {"path": "total", "column": "total", "type": "DECIMAL"},
    {"path": "meta", "strategy": "JSON", "column": "meta"},
    {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
     "fields": [{"path": "sku", "column": "sku", "type": "STRING"}, {"path": "qty", "column": "qty", "type": "INT"}]}
  ],
  "unmappedFields": "IGNORE | JSON_OVERFLOW_COLUMN"
}
```
- **FLATTEN:** `a.b.c` becomes the column `a_b_c`.
- **JSON:** the value is serialized into the dialect's JSON column type.
- **CHILD_TABLE:** each array element becomes a row in the child table, with an FK `parent_id` and an `ordinal` column. Child rows for a parent are delete-and-reinserted inside the same transaction, so they stay idempotent.
- **BSON conversion:** `ObjectId` → string, `Decimal128` → `BigDecimal`, `Date` → `Instant`/`TIMESTAMP`, `Binary` → bytes, `UUID` → string or uuid.
- **Auto-mapping:** `SchemaInferrer` samples N docs and suggests a mapping through `POST /api/jobs/{id}/infer-mapping`. You review and save it.

## 6. Sync modes
- **FULL:** reads the whole collection in batches ordered by `_id`. The checkpoint is the last `_id`, so a failed run resumes where it stopped.
- **INCREMENTAL:** filters on `watermarkField ≥ lastWatermark` (for example `updatedAt`), sorted by that field then `_id`. After each batch it saves the batch's highest watermark.
- **Later (Phase 5): CDC.** A Mongo change stream with a resume token, for near-real-time sync and delete propagation.
- **Optional:** a filter expression (Mongo JSON query) and a `writeMode` per job (`UPSERT` default, `INSERT`, or `TRUNCATE_AND_LOAD`).

## 7. Metadata DB schema (Flyway `V1__init.sql`, plus Quartz `tables_postgres.sql` as V2)
- `connection_def`(id, name, kind[SOURCE/TARGET], db_type, uri/host/port/database, username, password_enc, options_json, created_at)
- `migration_job`(id, name, source_conn_id, target_conn_id, cron, timezone, enabled, sync_mode, batch_size, write_mode, auto_create_schema, max_retries, created_at, updated_at)
- `collection_mapping`(id, job_id, source_collection, target_table, mapping_json, watermark_field, filter_json, order_index)
- `checkpoint`(job_id, collection_mapping_id, last_id, last_watermark, updated_at)
- `job_run`(id, job_id, trigger[CRON/MANUAL], status[RUNNING/SUCCEEDED/FAILED/CANCELLED/PARTIAL], started_at, ended_at, docs_read, rows_written, docs_failed, error_message)
- `dead_letter_record`(id, run_id, collection, source_id, payload_json, error, created_at)
- Dev default: the metadata DB is PostgreSQL via `docker-compose.yml`. H2 can be used for local tests.

## 8. Scheduling (Quartz)
- JDBC JobStore with `isClustered=true`, so several app instances can run safely and each trigger fires only once.
- One Quartz `JobDetail` per `migration_job`, with key `job-{id}` and a `CronTrigger` in the job's timezone.
- `MigrationQuartzJob` is annotated `@DisallowConcurrentExecution` and has misfire policy `DO_NOTHING`. It calls `MigrationExecutor.run(jobId, trigger)`.
- `SchedulerService` handles create, update, pause, resume, delete and triggerNow. The REST calls on `JobService` go through it.
- Cancel works through a cooperative flag in `RunContext` (checked between batches) plus Quartz `interrupt`.

## 9. Execution flow (`MigrationExecutor.run`)
1. Create a `job_run` row with status RUNNING. Load the job, its connections (decrypting passwords) and its mappings.
2. Open a `SourceConnector` through the factory and a Hikari pool for the target, both owned by this run.
3. If `auto_create_schema` is set, `SchemaManager.ensureSchema` creates any missing tables and columns. It only adds; it never drops.
4. For each collection mapping, loop over batches:
   - transform the docs to a `RowSet` (parent rows plus child-table rows); a doc that fails is written to dead-letter
   - open a JDBC transaction and run `executeBatch` upserts per table
   - commit, then update the checkpoint and counters
5. Retry transient failures (connection drop, deadlock) per batch with exponential backoff up to `max_retries`. After that, mark the run FAILED; the checkpoint is preserved.
6. Finalize the `job_run` status and metrics: Micrometer counters/timers tagged by job, exposed through actuator.

## 10. REST API (`/api`)
| Method | Path | Purpose |
|---|---|---|
| POST/GET/PUT/DELETE | `/connections[/{id}]` | Manage source/target connections (password write-only) |
| POST | `/connections/{id}/test` | Verify connectivity |
| GET | `/connections/{id}/collections` | List Mongo collections / SQL tables |
| POST/GET/PUT/DELETE | `/jobs[/{id}]` | Manage jobs + mappings (cron validated) |
| POST | `/jobs/{id}/infer-mapping` | Suggest mapping from sampled docs |
| POST | `/jobs/{id}/run` | Trigger now |
| POST | `/jobs/{id}/pause` / `/resume` | Pause/resume schedule |
| POST | `/jobs/{id}/reset-checkpoint` | Force full reload next run |
| GET | `/jobs/{id}/runs`, `/runs/{runId}` | Run history & details |
| POST | `/runs/{runId}/cancel` | Cancel running migration |
| GET | `/runs/{runId}/dead-letters` | Inspect failed documents |

## 11. Cross-cutting concerns
- **Security:** connection passwords are encrypted with AES-GCM. The key comes from the env var `SCHEDULAR_SECRET_KEY` and is never returned by the API. The API is protected with basic auth via `spring-boot-starter-security` in Phase 4.
- **Validation:** `@Valid` DTOs, Quartz `CronExpression.isValidExpression`, identifier sanitization for table and column names, which are always quoted by the dialect.
- **Errors:** `@RestControllerAdvice` returns RFC 7807 `ProblemDetail` responses.
- **Logging:** a run id in MDC on every log line during a run.
- **Config** (`application.yml`): metadata datasource, Quartz properties, default batch size (1000), pool sizes, sample size for inference.

## 12. Delivery phases
1. **Foundation:** pom deps, `application.yml`, docker-compose (Postgres metadata DB, Mongo, MySQL), Flyway schema, entities, repositories, connection CRUD with encryption and the test endpoint.
2. **Engine (Mongo → Postgres):** `MongoSourceConnector`, `MappingEngine` (FLATTEN/JSON/CHILD_TABLE), `PostgresDialect`, `SchemaManager`, `JdbcTargetWriter`, `MigrationExecutor` with FULL mode and checkpoints.
3. **Scheduling and API:** Quartz integration, job CRUD, run/pause/resume/cancel, run history, dead letters, infer-mapping. (INCREMENTAL mode was done in Phase 2.)
4. **More targets and hardening:** MySQL, SQL Server and Oracle dialects; retries; metrics; security; OpenAPI docs.
5. **Extensions (optional):** Mongo change-stream CDC, more sources (Cassandra, DynamoDB) via the `SourceConnector` SPI, web UI dashboard, notifications on failure.

## 13. Verification
- **Unit tests:**
  - `MappingEngine`: flatten, JSON, child tables, BSON type coercion
  - each `SqlDialect`: generated upsert, DDL and type SQL
  - `SecretCipher` round-trip
- **Integration tests (Testcontainers):** seed Mongo with nested and array documents, run `MigrationExecutor`, then check row counts and values in Postgres, MySQL and SQL Server. Also check:
  - running again gives no duplicates (idempotent)
  - an incremental run picks up only new or updated docs
  - a run killed mid-way resumes from the checkpoint
- **Scheduler test:** a job with cron `0/10 * * * * ?` fires, and two overlapping triggers do not run concurrently.
- **Manual end-to-end check:**
  1. `docker compose up -d`, then `./mvnw spring-boot:run`
  2. Create the connections and a job through Swagger UI or curl, then `POST /api/jobs/{id}/run`
  3. Check `GET /api/jobs/{id}/runs` and query the target tables
- Build gate: `./mvnw verify` passes.
