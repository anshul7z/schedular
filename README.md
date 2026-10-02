# schedular

A scheduler service that migrates data from NoSQL (MongoDB) into SQL databases (PostgreSQL, MySQL/MariaDB,
SQL Server, Oracle). The architecture is described in [docs/HLD.md](docs/HLD.md), and the implementation
roadmap is in [docs/PLAN.md](docs/PLAN.md).

**Status:** Phase 1 (foundation) is done:
- the metadata DB schema
- domain entities and repositories
- the connection API: CRUD, encrypted passwords, connection test, and listing collections/tables

The migration engine (Phase 2) and scheduling (Phase 3) are next.

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

## Tests
```powershell
.\mvnw.cmd test
```
Tests use an in-memory H2 metadata DB and need no Docker.
