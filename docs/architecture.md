# Investary architecture

## Style

Investary is a **modular monolith**: a single Spring Boot application whose code is organised by
business capability, plus an Angular single-page app. See [ADR 0001](adr/0001-modular-monolith.md).

```
Browser (Angular SPA) ──/api/v1──▶ Spring Boot backend ──JDBC──▶ PostgreSQL + pgvector (metadata, derived data, chunks/vectors)
                                       │      │
                                       │      └─filesystem──▶ document store (original PDFs)
                                       │                      email store    (original EML files)
                                       ├─HTTPS (allowlisted, background jobs)──▶ brokerage websites
                                       ├─HTTPS (OAuth 2.0, read-only Gmail API, polling)──▶ Google
                                       └─HTTP (Spring AI, configurable)──▶ AI provider (default: local Ollama)
```

## Backend modules

| Module | Since | Responsibility |
|--------|-------|----------------|
| `document` | Phase 1 | Store, list, download and delete research PDFs (the document library) |
| `email` | Phase 2 | Import EML files, parse MIME, normalize and sanitize content, keep originals |
| `research` | Phase 2 | Link classification, secure report downloads and jobs, research items, message↔document provenance |
| `knowledge` | Phase 3 | Text extraction, chunking, embeddings, index jobs, hybrid search, RAG assistant with validated citations |
| `common` | Phase 1 | Error handling (RFC 9457), file store, transaction helpers, CORS, shared web types |
| `investor` | Phase 4 | Investor profile and goals (self-declared) |
| `portfolio` | Phase 4 | Instruments, manual prices, portfolios, transactions; holdings, valuation and allocation computed on read |
| `intelligence` | Phase 4 | Research matching, deterministic analysis, impact assessments, portfolio-aware assistant (read-only) |
| `automation` | Phase 5 / M1 | Gmail OAuth connection (encrypted refresh token), brokerage source rules, Gmail sync (runs, cursor, message mapping), local-only filter |

### Dependency direction

```
intelligence ──▶ knowledge ──▶ email ──▶ research ──▶ document ──▶ common
     │                          └────────────────────────▲              ▲
     ├──────▶ portfolio ─────────────────────────────────────────────────┤
     └──────▶ investor ──────────────────────────────────────────────────┘
```

- `automation` depends only on `email` (application: `EmailImportService.importRaw`, `EmailExceptions`) and
  `common`. Nothing imports `automation`. Synced mail goes through the Phase 2 import unchanged.
- `investor` and `portfolio` depend only on `common`. `intelligence` uses `knowledge` search/assistant
  building blocks, `research.link.Keywords` for text folding, and only the **read** services
  `PortfolioQueryService` / `InvestorQueryService` (enforced by `ArchitectureTest`), so AI output can
  never change portfolio data. Nothing imports `intelligence`.

- `knowledge` reads sources only through `DocumentService` and `EmailQueryService` (plus the Phase 2
  `ContentAnalyzer`). Nothing imports `knowledge`: new sources are discovered by the index worker
  (`trackMissing`), and deletions are handled by `ON DELETE CASCADE` from `document` / `source_messages`.
- Its SQL read models (`trackMissing`, status counts, the sender join in search) read the `document` and
  `source_messages` tables directly; it never writes them.

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
| Knowledge base | — | `knowledge_sources`, `knowledge_chunks` (text, vectors); rebuildable with `POST /knowledge/reindex {"scope":"ALL"}` |

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

## Knowledge base and assistant (Phase 3)

```
KnowledgeIndexWorker ─ trackMissing (auto-index/backfill) ─ claim (SKIP LOCKED + token)
  └─ KnowledgeIndexProcessor: SourceLoader → PdfTextExtractor | EmailTextExtractor → TextChunker
       → EmbeddingService (reuse by hash, batches, dims check) → KnowledgeChunkStore.replace (one tx)
POST /research/ask ─ HybridSearchService (pgvector + full text + tickers, RRF, per-brokerage)
  └─ PromptBuilder (S1..Sn, untrusted <source>) → ChatModel → CitationValidator → citations from chunks
```

Details, evaluation and limitations: [phase-3-implementation.md](phase-3-implementation.md),
[ADR 0005](adr/0005-spring-ai-models-own-vector-schema.md), [ADR 0006](adr/0006-citation-integrity.md).

## Portfolios and portfolio intelligence (Phase 4)

```
transactions ─ HoldingCalculator (weighted average, chronological replay, DECIMAL128)
             └ PortfolioValuator + latest manual prices → holdings, summary, allocation (cost vs market value)
POST /intelligence/portfolios/{id}/impact-assessments
  └ PortfolioQueryService.snapshot → ResearchMatcher (HybridSearchService per holding: ticker, name, market)
       → deterministic categories → [ChatGateway + PromptBuilder.sources, if configured]
       → category allowed? CitationValidator against that holding's sources → save snapshot + evidence hashes
GET  …/impact-assessments/{id} → stale if holdings hash or cited chunk hashes changed
POST /intelligence/ask (portfolioId required) → profile, goals, facts + Phase 3 retrieval → validated answer
```

Details: [phase-4-implementation.md](phase-4-implementation.md),
[ADR 0007](adr/0007-portfolio-accounting.md), [ADR 0008](adr/0008-read-only-portfolio-intelligence.md).

## Gmail integration and sync (Phase 5 / Milestone 1)

```
POST /integrations/gmail/connect ─ OAuthStateStore (state, PKCE, cookie binding) ─▶ Google consent
GET  /integrations/gmail/callback ─ state + cookie ─ token exchange ─ scope/refresh-token checks
     ─ users.getProfile ─ TokenCipher (AES-GCM) ─ gmail_accounts CONNECTED
POST /integrations/gmail/sync | GmailSyncScheduler ─ lease on gmail_accounts (token) ─ GmailSyncRunner
     INITIAL/RECOVERY: messages.list per enabled rule (from:… after:…)   INCREMENTAL: history.list(cursor)
     ─ metadata ─ SenderVerifier (From addr-spec, Gmail dmarc) ─ RuleMatcher ─ raw ─ EmailImportService.importRaw
     ─ gmail_messages (dedup key) ─ token-guarded cursor checkpoint ─ gmail_sync_runs
```

Details: [phase-5-milestone-1.md](phase-5-milestone-1.md), [ADR 0009](adr/0009-gmail-oauth-and-sync.md).

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
| `knowledge_sources` | One per document/email: index status and reason, index version, content hash, job lease fields (V3) |
| `knowledge_chunks` | Chunk text, page range or section, metadata snapshot, tickers, `vector` embedding, model, `tsvector` (V3) |
| `investor_profile`, `investor_goals` | Self-declared profile (single row) and goals (V4) |
| `instruments`, `instrument_prices` | User-entered instruments with aliases; manual price observations with source and date (V4) |
| `portfolios`, `portfolio_transactions` | Portfolios and their BUY/SELL/OPENING_POSITION transactions; the source of truth for holdings (V4) |
| `impact_assessments`, `impact_assessment_items` | Saved assessments: portfolio snapshot and hash, categories, validated summaries, evidence copies with chunk hashes (V5) |
| `gmail_accounts` | Gmail connection: status, encrypted refresh token + key id, scopes, history cursor, last sync result, sync lease (V6) |
| `email_source_rules` | Approved brokerage senders (addresses/domains), subject keywords, Gmail query, enabled / auto-import / auto-PDF flags (V6) |
| `gmail_messages` | Handled Gmail messages per account (unique Gmail id), outcome, rule, imported `source_messages` row (V6) |
| `gmail_sync_runs` | Sync history: trigger, mode, status, counters, history ids, error (V6) |

## Frontend

```
src/app/
  core/       API services (documents, emails), models, error mapping
  shared/     alert, empty state, pager, status badge, pipes
  layout/     application shell
  features/   dashboard · documents (library, detail) · emails (inbox, import panel, detail) · assistant
              · portfolio (list, dashboard, transaction form, research insights) · investor (profile, goals)
              · automation (Gmail connection, sync history, brokerage sources)
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
- `src/test/resources/application.properties`: test storage under the temp dir; download and knowledge
  workers are off unless a test enables them; AI providers are `none` (tests use fake models via
  `fake-ai.properties` + `FakeAiConfiguration`).
- AI: `spring.ai.model.embedding/chat` = `INVESTARY_AI_*_PROVIDER` (default `none`: the app starts, AI
  endpoints answer `503 AI_NOT_CONFIGURED`); settings under `investary.knowledge.*`.

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
- **Gmail credentials** never reach the browser or the logs: OAuth with PKCE and a browser-bound one-time
  state, `gmail.readonly` only, refresh token AES-GCM encrypted with a key from the environment, access tokens
  in memory. The integration endpoints accept only loopback clients with a loopback `Host` (DNS rebinding) and,
  for writes, no foreign `Origin`. Synced mail is untrusted like any imported EML.
- **Source text sent to an LLM** is untrusted: instructions live only in the system message, sources are
  delimited and neutralised, and citations/figures are validated against stored chunks. Logs contain ids
  and counts, never document text, prompts or API keys. With a remote provider, retrieved excerpts leave
  the machine; the default (Ollama) keeps them local.
