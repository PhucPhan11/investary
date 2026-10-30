# Investary architecture

## Style

Investary is a **modular monolith**: a single Spring Boot application whose code is organised by
business capability, plus an Angular single-page app. See [ADR 0001](adr/0001-modular-monolith.md).

```
Browser (Angular SPA) ──/api/v1──▶ Spring Boot backend ──JDBC──▶ PostgreSQL (metadata)
                                          │
                                          └──filesystem──▶ storage root (original PDFs)
```

## Backend modules

| Module | Status | Responsibility |
|--------|--------|----------------|
| `document` | Phase 1 | Upload, store, list, download and delete research PDFs |
| `common` | Phase 1 | Error handling (RFC 9457 problem details), web/CORS config |
| `research` | planned | Text extraction, report comparison, research notes |
| `ai` | planned | LLM/embedding clients, summarisation, RAG retrieval |
| `portfolio` | planned | Holdings, watchlist, decision journal |

Planned modules intentionally have **no placeholder code** yet.

### Layering inside a module

```
api/          HTTP only: controllers, request/response DTOs, mapping
application/  use cases and business rules (transactions live here)
domain/       entities and value types
persistence/  Spring Data repositories
storage/      ports/adapters for external resources (filesystem today, S3 later)
```

Rules:

- Controllers never expose JPA entities; they map to DTOs.
- Only `application` coordinates persistence and storage.
- Other modules may call a module's `application` services but not its `persistence` or `storage` packages.
- Cross-module data access goes through IDs, not shared entities.

## Document upload flow

1. Controller binds multipart `file` + optional metadata and validates the metadata (Bean Validation).
2. `DocumentService` checks the `%PDF-` signature and computes SHA-256 and size by streaming the upload.
3. Existing checksum → `DuplicateDocumentException` (409), nothing written.
4. `DocumentStorage.store` writes to `<root>/.tmp`, then atomically moves to `<root>/<uuid>.pdf`.
5. Metadata is inserted (`saveAndFlush`). On failure the stored file is deleted; a unique-constraint
   violation from a concurrent identical upload is reported as 409.

Delete: the row is removed in a transaction; the file is deleted in an `afterCommit` callback. Failure to
delete the file is logged, never surfaced as an error, because metadata is already consistent.

## Database

Flyway owns the schema (`db/migration`). Hibernate runs with `ddl-auto=validate`. Tests use the same
migrations against a real PostgreSQL container (Testcontainers), so there is no test-only schema.

## Frontend

```
src/app/
  core/       API services, models, error mapping (singletons)
  shared/     reusable presentational components and pipes
  layout/     application shell (top bar, navigation)
  features/   lazy-loaded routes: dashboard, documents
```

Standalone components with signals; no state library and no UI component library. In development the
Angular dev server proxies `/api` to the backend, so the browser sees a single origin.

## Configuration and profiles

- `application.yml`: production-safe defaults — no CORS, ECS JSON logs, DB password only from the environment.
- `application-dev.yml`: activated by `./mvnw spring-boot:run`; imports `infrastructure/.env`, allows the
  Angular origin and enables plain-text debug logs.

## Security posture (Phase 1)

Single-user and local only. No authentication, so the backend must not be reachable from untrusted
networks; Docker publishes PostgreSQL on `127.0.0.1` only. Uploaded files are stored under
server-generated names outside the source tree, storage keys are validated against a strict pattern
and never exposed, and error responses omit internal details.
