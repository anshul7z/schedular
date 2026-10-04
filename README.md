# schedular

A scheduler service that migrates data from NoSQL (MongoDB) into SQL databases (PostgreSQL, MySQL/MariaDB,
SQL Server, Oracle). The architecture is described in [docs/HLD.md](docs/HLD.md), and the implementation
roadmap is in [docs/PLAN.md](docs/PLAN.md).

**Status:**
- **Phase 1 (foundation), done:** the metadata DB schema, domain entities, and the connection API (CRUD, encrypted
  passwords, connection test, listing collections/tables).
- **Phase 2 (migration engine), done:** MongoDB → PostgreSQL with FULL and INCREMENTAL sync, configurable mapping
  (flatten, JSON column, child tables), checkpoints and resume, retries, and dead letters for bad documents.
- **Phase 3 (scheduling and API), done:** cron schedules (clustered Quartz), the job and run REST API (run now,
  pause/resume, cancel, history, dead letters), and automatic mapping suggestions.
- **Phase 4, next:** MySQL/MariaDB, SQL Server and Oracle targets; API security; metrics; OpenAPI docs.

## Requirements
- Java 21
- Either Docker, for the full local stack, or nothing extra when using the `local` profile with embedded H2

## Running

### Without Docker (embedded H2 metadata DB)
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```
Metadata is stored in `./data/`. The `local` profile ships a development encryption key. Never use it anywhere else.

### With Docker (PostgreSQL metadata DB + sample MongoDB and MySQL)
```powershell
docker compose up -d
$env:SCHEDULAR_SECRET_KEY = "<output of: openssl rand -base64 32>"
.\mvnw.cmd spring-boot:run
```
| Service | Address | Credentials |
|---|---|---|
| Metadata DB | `localhost:5432/schedular_meta` | `schedular` / `schedular` |
| Target (PostgreSQL) | `localhost:5432/warehouse` | `warehouse` / `warehouse` |
| Target (MySQL) | `localhost:3306/warehouse` | `warehouse` / `warehouse` |
| Source (MongoDB) | `localhost:27017/shop` | `root` / `root` (authSource `admin`), seeded with `customers` and `orders` |

### Configuration
| Env var | Purpose | Default |
|---|---|---|
| `SCHEDULAR_SECRET_KEY` | Base64 AES-256 key for encrypting connection passwords. **Required**; keep it stable. | none |
| `SCHEDULAR_DB_URL` / `_USER` / `_PASSWORD` | Metadata DB | docker-compose Postgres |
| `SCHEDULAR_NODE_ID` | This instance's name in a cluster; recorded on its runs | host name |

## Connection API
| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/connections` | List connections |
| `POST` | `/api/connections` | Create a connection |
| `GET` / `PUT` / `DELETE` | `/api/connections/{id}` | Read, update or delete one |
| `POST` | `/api/connections/{id}/test` | Test a saved connection (`success: true/false`) |
| `POST` | `/api/connections/test` | Test settings without saving |
| `GET` | `/api/connections/{id}/collections` | List Mongo collections or SQL tables |

Rules:
- Give either `uri` or `host` (with optional `port` and `database`).
- The password always goes in the `password` field. A URI that embeds a password is rejected, so no secret is ever stored in clear text.
- `options` are handled per database type:
  - MongoDB: added as connection-string parameters, and only when connecting by `host`
  - SQL: passed to the JDBC driver as properties (for example, SQL Server needs `"trustServerCertificate": "true"` for a self-signed certificate)
- On `PUT`, leaving out `password` keeps the stored one, and `""` clears it.
- Errors come back as RFC 9457 problem JSON with these status codes:
  - `400` validation error
  - `404` not found
  - `409` duplicate name, or the connection is still in use by a job
  - `502` the database can't be reached when listing its collections or tables

Example, against the Docker stack:
```bash
curl -X POST localhost:8080/api/connections -H 'Content-Type: application/json' -d '{
  "name": "shop-mongo", "dbType": "MONGODB", "host": "localhost", "database": "shop",
  "username": "root", "password": "root", "options": {"authSource": "admin"}}'

curl -X POST localhost:8080/api/connections -H 'Content-Type: application/json' -d '{
  "name": "warehouse-pg", "dbType": "POSTGRESQL", "host": "localhost", "database": "warehouse",
  "username": "warehouse", "password": "warehouse"}'

curl -X POST localhost:8080/api/connections/1/test
curl localhost:8080/api/connections/1/collections
```

## Jobs and runs API
| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/connections/{id}/collections/{collection}/mapping?sampleSize=200` | Sample a collection and propose a mapping (plus a `watermarkField` for incremental sync) |
| `GET` / `POST` | `/api/jobs` | List or create jobs |
| `GET` / `PUT` / `DELETE` | `/api/jobs/{id}` | Read, replace or delete a job (`409` while it is running) |
| `POST` | `/api/jobs/{id}/run` | Start a run now: `202` with the run; poll its `Location` |
| `POST` | `/api/jobs/{id}/pause` / `/resume` | Stop / restart the cron schedule (manual runs still work while paused) |
| `POST` | `/api/jobs/{id}/reset-checkpoint` | Forget progress; the next run starts from the beginning |
| `GET` | `/api/jobs/{id}/runs?page=0&size=20` | Run history, newest first |
| `GET` | `/api/runs/{runId}` | One run: status, counts, error, duration |
| `POST` | `/api/runs/{runId}/cancel` | Stop after the current batch; the run ends `CANCELLED` and keeps its checkpoint |
| `GET` | `/api/runs/{runId}/dead-letters?page=0&size=50` | Documents that failed, with the reason and the document |

A job's fields and their defaults:

| Field | Default | Notes |
|---|---|---|
| `name`, `sourceConnectionId`, `targetConnectionId`, `mappings` | required | The source must be a MongoDB connection, the target a PostgreSQL one (other targets: Phase 4) |
| `cron` | none (manual only) | Quartz format with seconds: `0 0/15 * * * ?` = every 15 minutes |
| `timezone` | `UTC` | The zone the cron is evaluated in, e.g. `Asia/Kolkata` |
| `enabled` | `true` | Same as pause/resume |
| `syncMode` | `FULL` | `INCREMENTAL` needs a `watermarkField` on every mapping |
| `writeMode` | `UPSERT` | Also `INSERT` (append-only) and `TRUNCATE_AND_LOAD` (FULL only) |
| `batchSize` / `maxRetries` / `autoCreateSchema` | `1000` / `3` / `true` | |

Each entry in `mappings` has these fields:
- `sourceCollection` and `targetTable`
- `mapping`: the field mapping (see [HLD §6.2](docs/HLD.md#62-document--relational-mapping)), or the one suggested by
  the endpoint above
- `watermarkField`: used by INCREMENTAL sync
- `filter`: a MongoDB query that limits which documents are migrated

Example: suggest a mapping, create a job that runs every 15 minutes, then run it now.
```bash
curl "localhost:8080/api/connections/1/collections/orders/mapping?sampleSize=200"

curl -X POST localhost:8080/api/jobs -H 'Content-Type: application/json' -d '{
  "name": "orders-sync", "sourceConnectionId": 1, "targetConnectionId": 2,
  "cron": "0 0/15 * * * ?", "timezone": "Asia/Kolkata", "syncMode": "INCREMENTAL",
  "mappings": [{"sourceCollection": "orders", "targetTable": "orders", "watermarkField": "updatedAt",
                "mapping": {"fields": [{"path": "status"}, {"path": "total", "type": "DECIMAL"},
                  {"path": "items", "strategy": "CHILD_TABLE", "childTable": "order_items",
                   "fields": [{"path": "sku"}, {"path": "qty", "type": "INT"}]}]}}]}'

curl -X POST localhost:8080/api/jobs/1/run          # -> 202, {"id": 7, "status": "RUNNING", ...}
curl localhost:8080/api/runs/7                       # poll until status is SUCCEEDED / PARTIAL / FAILED
```

Several instances can share one metadata DB: each cron firing runs on exactly one instance. Give each instance a
stable, unique `SCHEDULAR_NODE_ID` (default: the host name).

## How a migration run works
For each collection mapping in a job, the run:
1. creates the target tables and columns if they are missing (when `autoCreateSchema` is on)
2. reads the collection in batches (`batchSize`, default 1000), mapping each document to a parent row plus child-table
   rows
3. writes each batch in one transaction, then saves a checkpoint

Writes are upserts by primary key, so re-running is safe: rows are never duplicated, and a failed run resumes after
its last committed batch.

What happens to bad data:
- **A document can't be converted** (wrong type, too long, no `_id`): it goes to the dead-letter table and the run
  ends `PARTIAL`.
- **The database rejects a row:** the batch is retried one document at a time, so only the bad ones are
  dead-lettered.
- **A transient error** (lost connection, deadlock): the batch is retried with backoff, up to the job's `maxRetries`.

The mapping format and every option are described in [docs/HLD.md §6–7](docs/HLD.md#6-data-design).

## Tests
```powershell
.\mvnw.cmd test
```
No Docker is needed:
- **Metadata DB:** an in-memory H2 database.
- **Engine and connection tests:** real **embedded MongoDB and PostgreSQL** processes. The binaries are downloaded
  once, on the first run, and cached.
- **Production setup:** one test boots the whole app with PostgreSQL as its metadata DB.
