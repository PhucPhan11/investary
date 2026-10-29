# Investary architecture

## Style

Investary is a **modular monolith**: a single Spring Boot application whose code is organised by
business capability, plus an Angular single-page app. See [ADR 0001](adr/0001-modular-monolith.md).

```
Browser (Angular SPA) ──/api/v1──▶ Spring Boot backend ──JDBC──▶ PostgreSQL (metadata + derived data)
                                       │      │
                                       │      └─filesystem──▶ document store (original PDFs)
                                       │                      email store    (original EML files)
                                       └─HTTPS (allowlisted, background jobs)──▶ brokerage websites
```

## Backend modules

| Module | Since | Responsibility |
|--------|-------|----------------|
| `document` | Phase 1 | Store, list, download and delete research PDFs (the document library) |
| `email` | Phase 2 | Import EML files, parse MIME, normalize and sanitize content, keep originals |
| `research` | Phase 2 | Link classification, secure report downloads and jobs, research items, message↔document provenance |
| `common` | Phase 1 | Error handling (RFC 9457), file store, transaction helpers, CORS, shared web types |
| `ai`, `portfolio` | planned | LLM/RAG features; holdings and decision journal |

Planned modules intentionally have **no placeholder code** yet.

### Dependency direction

```
email ──▶ research ──▶ document ──▶ common
  └────────────────────────▲
```

- `email` hands parsed content to `research` (`ResearchIngestionService`, using research's own input
  types) and stores PDF attachments through `document`.
- `research` stores downloaded reports through `document` (`DocumentService.upload`), and reads document
  metadata for provenance views.
- `document` knows nothing about emails. When a document is deleted it publishes a
  `DocumentDeletedEvent`; `research` listens and detaches its links.
- Research tables reference emails by id (foreign keys in the database, no cross-module JPA relations).

### Layering inside a module

```
api/          HTTP only: controllers, request/response DTOs
application/  use cases and business rules (transactions live here)
domain/       entities and value types
persistence/  Spring Data repositories, specifications, JDBC queries
storage/      file storage adapters (local filesystem today, S3 later)
parsing/      (email) MIME and HTML processing; pure, no I/O
```

Rules:

- Controllers never expose JPA entities or storage keys.
- Only `application` coordinates persistence, storage and other modules.
- Other modules call a module's `application` services, not its `persistence` or `storage` packages.

## Data ownership

| Data | Authoritative | Derived (regenerable) |
|------|---------------|-----------------------|
| Research PDFs | file in the document store | `document` row (checksum, size, metadata) |
| Emails | original `.eml` in the email store | `source_messages` content columns, `source_links`, `research_items`, attachment metadata |
| Provenance | `message_documents` (which email produced which document, and how) | — |

See [ADR 0003](adr/0003-preserve-original-eml.md).

## Document upload flow (Phase 1)

1. The controller binds the multipart `file` and the optional metadata, and validates the metadata.
2. `DocumentService` checks the `%PDF-` signature and computes SHA-256 and size.
3. If the checksum already exists → `DuplicateDocumentException` (409), and nothing is written.
4. The file is written to `.tmp`, then atomically moved to `<root>/<uuid>.pdf`.
5. Metadata is inserted. If the insert fails, the file is deleted; a concurrent duplicate is reported as 409.

Delete: the row is removed in a transaction (after publishing `DocumentDeletedEvent`), and the file is
deleted after commit.

## Email import flow (Phase 2)

1. Check type and size, then reject a re-import of identical bytes (SHA-256 → 409).
2. `EmlParser` (Angus Mail, lenient) → headers, html/text bodies, attachments, and a list of
   non-fatal problems. Input without email headers → 400.
3. `ContentAnalyzer` → reading-order blocks (heading level, section path, bold lead, language),
   normalized text, and deduplicated links with tracker URLs unwrapped.
4. Store the original EML. Then, in one transaction, insert the message, attachment metadata, and the
   research data (links classified by `LinkClassifier`, items from `ResearchItemExtractor`). The file is
   removed if the transaction fails.
5. After commit, import PDF attachments through `DocumentService` and record `ATTACHMENT` provenance.

## Report download flow (Phase 2)

```
user ─POST download─▶ DownloadRequestService ──(UrlPolicy precheck, row lock)──▶ download_jobs: PENDING
                                                                                   │
DownloadJobWorker (poll, SKIP LOCKED claim, lease) ─▶ DownloadJobProcessor ─▶ ReportDownloader
        │                                                                   (policy per hop, DNS check,
        │                                                                    timeouts, size cap, %PDF-)
        └─▶ DocumentService.upload (duplicate → existing id) ─▶ link DOWNLOADED + message_documents
```

Failures are classified as `BLOCKED` / `PERMANENT` (fail now) or `TRANSIENT` (retry with backoff).
See [ADR 0004](adr/0004-report-download-policy.md).

## Database

Flyway owns the schema (`db/migration`); Hibernate runs with `ddl-auto=validate`. Tests use the same
migrations against real PostgreSQL (Testcontainers). There is no test-only schema. `FlywayUpgradeTest`
checks that a Phase 1 database upgrades without data loss.

| Table | Purpose |
|-------|---------|
| `document` | Library metadata (V1) |
| `source_messages` | Imported emails: immutable header fields, storage key, checksum, derived content, parsing status |
| `source_attachments` | MIME attachment metadata; `document_id` for imported PDFs |
| `source_links` | Original and unwrapped URL, display text, section, language, category, rule, ticker hint, status, error, document |
| `message_documents` | Many-to-many provenance between messages and documents (`ATTACHMENT`, `LINKED_REPORT`) |
| `research_items` | Deterministic research items: type, ticker, original text, `structured_data` jsonb, method, location |
| `download_jobs` | Queue: status, attempts, next attempt, claim token, lease, last error |

## Frontend

```
src/app/
  core/       API services (documents, emails), models, error mapping
  shared/     alert, empty state, pager, status badge, pipes
  layout/     application shell
  features/   dashboard · documents (library, detail) · emails (inbox, import panel, detail)
```

- Standalone components with signals; no state library and no UI component library.
- The Angular dev server proxies `/api` to the backend, so the browser sees a single origin.
- Email HTML is never bound into the Angular DOM. The safe preview loads from the backend's sanitized
  endpoint into an `<iframe sandbox>` without scripts or same-origin access.

## Configuration and profiles

- `application.yml`: production-safe defaults. No CORS, ECS JSON logs, DB password only from the
  environment, strict download policy.
- `application-dev.yml`: activated by `./mvnw spring-boot:run`. Imports `infrastructure/.env`, allows
  the Angular origin, enables plain-text debug logs.
- `src/test/resources/application.properties`: test storage under the temp dir; the download worker is
  off unless a test enables it.

## Security posture

- Single-user and local only. There is no authentication, so the backend must not be reachable from
  untrusted networks. Docker publishes PostgreSQL on `127.0.0.1` only.
- **Files:** uploaded files are stored under server-generated names outside the source tree. Storage
  keys are validated against strict patterns, must resolve inside the root, and are never exposed.
- **Email HTML** is untrusted: it is sanitized server-side, served with a strict CSP, rendered only in a
  sandboxed frame, and remote images are dropped.
- **External URLs** are untrusted: https only, allowlisted domains, per-hop redirect checks, DNS-level
  SSRF and rebinding protection, size and time limits, and content validation. Downloads only happen
  when the user asks for them.
