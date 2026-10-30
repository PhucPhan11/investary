# Investary

Personal, AI-ready investment research assistant. **Phase 1** provides the foundation: a modular-monolith
Spring Boot backend, an Angular frontend and PostgreSQL, with the first business feature —
**management of brokerage research PDFs** (upload, list, view metadata, download, delete).

> ⚠️ **Local, single-user only.** Phase 1 has **no authentication or authorization**. Never expose the
> backend (port 8080) or the database to a network you do not fully control.

---

## Architecture overview

```
investary/
├── backend/          Spring Boot 4.1 · Java 21 · Maven (wrapper included)
│   └── src/main/java/com/investary/
│       ├── document/     Feature module: research documents
│       │   ├── api/          REST controller + DTOs
│       │   ├── application/  Use cases (DocumentService), PDF validation, domain exceptions
│       │   ├── domain/       JPA entity + ReportType
│       │   ├── persistence/  Spring Data repository
│       │   └── storage/      DocumentStorage port + local filesystem adapter
│       └── common/       Cross-cutting: RFC 9457 error handling, CORS config
├── frontend/         Angular 22 · standalone components · signals · SCSS (no UI library)
├── infrastructure/   docker-compose.yml (PostgreSQL 17), .env.example
└── docs/             architecture.md, ADRs
```

- **Modular monolith**: one deployable backend, organised by business capability. Future modules
  (`portfolio`, `research`, `ai`) will sit next to `document`; see [docs/architecture.md](docs/architecture.md).
- **Storage**: PDF binaries live on the local filesystem outside the repo (default `~/.investary/documents`);
  metadata lives in PostgreSQL. Files are stored under server-generated names (`<uuid>.pdf`).
- **Schema**: owned by Flyway (`backend/src/main/resources/db/migration`); Hibernate only validates it.

## Prerequisites

| Tool | Version used | Notes |
|------|--------------|-------|
| JDK | 21 | `JAVA_HOME` **must** point at a JDK 21 — the build fails fast otherwise |
| Docker + Compose | Docker 29 / Compose v2+ | For PostgreSQL and the Testcontainers tests |
| Node.js / npm | Node 22.22+, 24.15+ or 26 · npm 11 | Required by Angular 22 |

Maven itself is **not** required: use the bundled wrapper (`./mvnw` / `mvnw.cmd`), which downloads
Maven 3.9.11 from Maven Central. This also avoids a global `settings.xml` that mirrors to an unreachable
corporate repository.

## Local development

### 1. Start PostgreSQL

```bash
cp infrastructure/.env.example infrastructure/.env   # then set a real POSTGRES_PASSWORD
docker compose -f infrastructure/docker-compose.yml up -d --wait
```

The database listens on `127.0.0.1:5432` only. Data persists in the `investary_postgres-data` volume.

### 2. Start the backend (http://localhost:8080)

```bash
cd backend
./mvnw spring-boot:run
```

PowerShell, if your default `JAVA_HOME` is not JDK 21:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'; cd backend; .\mvnw.cmd spring-boot:run
```

`spring-boot:run` activates the **`dev`** profile, which reads `POSTGRES_*` from `infrastructure/.env`
(so the password is defined once), allows CORS from `http://localhost:4200`, and logs in plain text.
Flyway migrates the schema on startup.

### 3. Start the frontend (http://localhost:4200)

```bash
cd frontend
npm install
npm start
```

The Angular dev server proxies `/api` to `http://localhost:8080` (`frontend/proxy.conf.json`).

### 4. Use it

Open http://localhost:4200, go to **Document library**, choose a PDF, optionally add brokerage, report
type, ticker and publication date, and upload. The document appears in the table and on the dashboard.

## Configuration

All backend settings are in `backend/src/main/resources/application.yml` (production-safe defaults)
and `application-dev.yml` (local overrides). Override with environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `INVESTARY_DB_URL` | `jdbc:postgresql://localhost:5432/investary` | JDBC URL |
| `INVESTARY_DB_USERNAME` | `investary` | DB user |
| `INVESTARY_DB_PASSWORD` | — (no default; dev profile falls back to `POSTGRES_PASSWORD` from `.env`) | DB password |
| `INVESTARY_STORAGE_ROOT` | `~/.investary/documents` | Where PDFs are stored (keep it outside the repo) |
| `INVESTARY_MAX_UPLOAD_SIZE` | `50MB` | Max PDF size (larger → HTTP 413) |
| `INVESTARY_MAX_REQUEST_SIZE` | `52MB` | Max multipart request size |
| `INVESTARY_CORS_ALLOWED_ORIGINS` | none (dev: `http://localhost:4200`) | Comma-separated browser origins |
| `INVESTARY_LOG_FORMAT` | `ecs` (dev: plain text) | Structured console logging format |

Without the `dev` profile (e.g. `java -jar target/investary-backend-*.jar`) logs are ECS JSON and CORS is off.

Secrets live only in `infrastructure/.env` / environment variables. `.env`, `storage/` and `*.pdf` are git-ignored.

## Database migrations

- Migrations live in `backend/src/main/resources/db/migration` as `V<n>__<description>.sql`.
- Flyway runs them on application startup; `spring.jpa.hibernate.ddl-auto=validate` makes the app fail
  fast if entities and schema diverge.
- Never edit a migration that has been applied; add a new version instead.

`V1__create_document_table.sql` creates `document` with a UUID primary key, unique `storage_key` and
`sha256_checksum`, check constraints (positive size, hex checksum, allowed report types) and indexes on
`uploaded_at`, `ticker` and `report_type`.

## Testing

```bash
cd backend && ./mvnw verify          # unit, web-layer and PostgreSQL integration tests
cd frontend && npm test -- --watch=false
cd frontend && npm run build
```

| Suite | Kind | Covers |
|-------|------|--------|
| `PdfSignatureValidatorTest` | unit | magic-byte detection |
| `LocalFileSystemDocumentStorageTest` | unit (temp dir) | store/load/delete, path traversal rejection, cleanup on failed writes |
| `DocumentServiceTest` | unit (Mockito) | upload, invalid type, duplicate, DB failure → file removed, concurrent duplicate, not found, delete |
| `DocumentControllerTest` | `@WebMvcTest` | status codes, validation errors, problem details, download headers |
| `DocumentIntegrationTest` | Testcontainers PostgreSQL | Flyway schema + full upload → list → download → duplicate → delete flow |
| Frontend specs | Vitest | API service, error mapping, file-size pipe, app shell |

`DocumentIntegrationTest` needs a running Docker daemon; without one it is **skipped** (not failed) —
check the surefire summary for `Skipped`.

## API

Base path: `/api/v1/documents`. Errors use RFC 9457 `application/problem+json` with a machine-readable
`code` (`VALIDATION_FAILED`, `INVALID_DOCUMENT`, `DOCUMENT_NOT_FOUND`, `DUPLICATE_DOCUMENT`, `STORAGE_ERROR`, `INTERNAL_ERROR`).

| Method | Path | Result |
|--------|------|--------|
| `POST` | `/api/v1/documents` | `201` + metadata; `400` non-PDF / invalid fields; `409` duplicate (`existingDocumentId`); `413` too large |
| `GET` | `/api/v1/documents?page=0&size=20&sort=uploadedAt,desc` | `200` page envelope (`size` capped at 100) |
| `GET` | `/api/v1/documents/{id}` | `200` metadata; `404` |
| `GET` | `/api/v1/documents/{id}/download` | `200` `application/pdf` attachment; `404` |
| `DELETE` | `/api/v1/documents/{id}` | `204`; `404` |

Upload form fields: `file` (required), `brokerageCompany` (≤200 chars), `reportType`
(`MARKET_OVERVIEW`, `MACROECONOMIC`, `INDUSTRY_ANALYSIS`, `STOCK_ANALYSIS`, `ETF_RESEARCH`, `OTHER`),
`ticker` (1–15 of `A-Z 0-9 . -`, stored upper-case), `publishedOn` (`yyyy-MM-dd`, not in the future).

### curl examples

```bash
# Upload
curl -F "file=@report.pdf;type=application/pdf" \
     -F brokerageCompany="SSI Research" -F reportType=STOCK_ANALYSIS \
     -F ticker=VNM -F publishedOn=2026-10-15 \
     http://localhost:8080/api/v1/documents

# List (newest first)
curl "http://localhost:8080/api/v1/documents?page=0&size=20"

# Metadata
curl http://localhost:8080/api/v1/documents/<id>

# Download
curl -OJ http://localhost:8080/api/v1/documents/<id>/download

# Delete
curl -X DELETE http://localhost:8080/api/v1/documents/<id>
```

## Behaviour and design decisions

- **PDF detection** uses the `%PDF-` signature within the first 1 KB; the file name and declared MIME type
  are ignored, and the stored MIME type is always `application/pdf`.
- **Duplicates** (same SHA-256 content) are rejected with `409 Conflict` and the existing document's id.
  See [ADR 0002](docs/adr/0002-duplicate-handling.md).
- **Consistency**: uploads write the file (temp file + atomic move), then insert metadata; if the insert
  fails the file is removed. Deletes remove metadata in a transaction and the file after commit — a
  leftover file is harmless, metadata pointing at nothing is not.
- **No internal paths** in API responses or error messages; storage keys are never exposed.
- Original file names are sanitised (path components and control characters stripped, max 255 chars).

## Known limitations

- No authentication/authorization, rate limiting or virus scanning — local single-user use only.
- PDF validation checks the signature only; malformed or encrypted PDFs are accepted.
- Orphaned files (from a failed post-commit delete or a crash between file write and DB insert) are not
  garbage-collected yet.
- Metadata cannot be edited after upload; no search or filtering beyond paging.
- Dashboard statistics (last 7 days, brokerage count) are computed from the 100 most recent documents.
- Downloads load the whole PDF through the backend; fine for research reports, not tuned for huge files.

## Next phases

- **Phase 2 — Text extraction & metadata**: background PDF text extraction (Apache PDFBox/Tika), a
  `document_content` table, processing status per document, metadata editing, search and filters.
- **Phase 3 — AI summaries**: Spring AI integration in an `ai` module, per-document summaries with
  source citations.
- **Phase 4 — RAG**: chunking + embeddings in PostgreSQL `pgvector`, cross-document Q&A and report comparison.
- **Phase 5 — Portfolio & decisions**: `portfolio` module (holdings, watchlist), decision journal linked to research.
- Later: authentication, S3-compatible storage behind `DocumentStorage`, Gmail/Drive ingestion.
