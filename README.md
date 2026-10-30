# Investary

Personal, AI-ready investment research assistant.

- **Phase 1:** a modular-monolith Spring Boot backend, an Angular frontend and PostgreSQL, with
  **research PDF management** (upload, list, view metadata, download, delete).
- **Phase 2:** **email research ingestion**. Import brokerage emails (`.eml`), keep the originals,
  extract readable content and links, classify research links, download selected reports securely in
  the background into the document library, and extract a few research items deterministically.
- **Phase 3:** **research knowledge base and assistant**. PDFs and emails are indexed (page-aware text
  extraction, chunking, embeddings in PostgreSQL + pgvector), searched with hybrid retrieval, and
  questions are answered in Vietnamese by a configurable LLM (default: local Ollama) with
  backend-validated citations. See [docs/phase-3-implementation.md](docs/phase-3-implementation.md).

> ⚠️ **Local, single-user only.** There is **no authentication or authorization**. Never expose the
> backend (port 8080) or the database to a network you do not fully control.

---

## Architecture overview

```
investary/
├── backend/          Spring Boot 4.1 · Java 21 · Maven (wrapper included)
│   └── src/main/java/com/investary/
│       ├── document/     PDF library: api, application, domain, persistence, storage
│       ├── email/        EML import: api, application, domain, persistence, parsing, storage
│       ├── research/     Links, classification, secure downloads/jobs, research items, provenance
│       ├── knowledge/    Extraction, chunking, embeddings, index jobs, hybrid search, RAG assistant
│       └── common/       Errors (RFC 9457), file store, tx helpers, CORS, shared web types
├── frontend/         Angular 22 · standalone components · signals · SCSS (no UI library)
├── infrastructure/   docker-compose.yml (PostgreSQL 17 + pgvector), .env.example
└── docs/             architecture.md, phase-2/3-implementation.md, ADRs
```

- **Modular monolith.** One deployable backend, organised by business capability. Dependencies point one way:
  `knowledge → email → research → document → common`. See [docs/architecture.md](docs/architecture.md).
- **Sources of truth.** Original files are authoritative: PDFs in the document store, `.eml` files in
  the email store, both outside the repo. PostgreSQL holds metadata and *derived* data (parsed text,
  links, research items), which can be regenerated from the originals.
- **Schema.** Flyway owns it (`backend/src/main/resources/db/migration`); Hibernate only validates.

## Prerequisites

| Tool | Version used | Notes |
|------|--------------|-------|
| JDK | 21 | `JAVA_HOME` **must** point at a JDK 21. The build fails fast otherwise |
| Docker + Compose | Docker 29 / Compose v2+ | For PostgreSQL and the Testcontainers tests |
| Node.js / npm | Node 22.22+, 24.15+ or 26 · npm 11 | Required by Angular 22 |
| Ollama (optional) | any recent | Only for the research assistant: `ollama pull bge-m3` and `ollama pull qwen3:8b` |

Maven itself is **not** required: use the bundled wrapper (`./mvnw` / `mvnw.cmd`). It downloads
Maven 3.9.11 from Maven Central, which also avoids a global `settings.xml` that mirrors to an
unreachable corporate repository.

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

`spring-boot:run` activates the **`dev`** profile, which:
- reads `POSTGRES_*` from `infrastructure/.env`
- allows CORS from `http://localhost:4200`
- logs in plain text

Flyway migrates the schema on startup. An existing Phase 1 database is upgraded in place (V1 → V2).

### 3. Start the frontend (http://localhost:4200)

```bash
cd frontend
npm install
npm start
```

The Angular dev server proxies `/api` to `http://localhost:8080` (`frontend/proxy.conf.json`).

### Running from IntelliJ IDEA

Shared run configurations live in `.run/` and appear in the Run/Debug list when you open the repository
root. They work in IntelliJ IDEA Community, because they use only Maven, Shell Script, Remote JVM
Debug and Compound configurations.

| Configuration | What it does |
|---------------|--------------|
| **Investary (all)** | Starts *Backend (dev)* and *Frontend (npm start)* together |
| Postgres (docker compose) | `infrastructure/start-postgres.ps1` → `docker compose -f infrastructure/docker-compose.yml up -d --wait` (runs automatically before the backend) |
| Backend (dev) | `spring-boot:run` with the `dev` profile, through the Maven wrapper |
| Backend (debug) | Same as above, with the debug agent listening on `localhost:5005` |
| Attach backend debugger (5005) | Remote JVM Debug; start it after *Backend (debug)* is up |
| Frontend (npm start) | Angular dev server on http://localhost:4200 |
| Backend tests (verify) | `mvnw verify` (needs Docker for the Testcontainers tests) |
| Frontend tests | `npm test -- --watch=false` |
| Harness check | `node scripts/check-harness.mjs` (see "Working with AI coding agents") |

One-time setup:
1. *File → Project Structure → Project → SDK*: select a JDK 21 (any vendor/name). The Maven
   configurations use the project SDK, so they do not depend on what the JDK is called.
2. Create `infrastructure/.env` as described in step 1 below.
3. Run `npm install` in `frontend/` once.

If IntelliJ cannot resolve something, open the configuration and re-select it:
- the JDK (*Runner → JRE*)
- the Maven home (*Use Maven wrapper*)
- for Shell Script configurations, the shell interpreter (PowerShell on Windows)

*Postgres (docker compose)* must not run "in terminal": as a before-launch task IntelliJ has to wait for
its process to exit (`docs/failures/0008-intellij-run-config-terminal-before-launch.md`).

### 4. Use it

- **Document library:** upload PDFs with optional brokerage, report type, ticker and publication date.
- **Email research:**
  1. Export a brokerage email from your mail client as `.eml` (Gmail: ⋮ → *Download message*;
     Outlook: *Save as*) and import it.
  2. The detail page shows the email text, a safe HTML preview, research links with categories, and
     extracted research items.
  3. Click **Download** on a research link. The PDF appears under *Linked reports* and in the document
     library, and the document page shows which emails it came from.

## Configuration

Backend settings live in `backend/src/main/resources/application.yml` (production-safe defaults) and
`application-dev.yml` (local overrides). Override them with environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `INVESTARY_DB_URL` | `jdbc:postgresql://localhost:5432/investary` | JDBC URL |
| `INVESTARY_DB_USERNAME` | `investary` | DB user |
| `INVESTARY_DB_PASSWORD` | — (dev profile falls back to `POSTGRES_PASSWORD` from `.env`) | DB password |
| `INVESTARY_STORAGE_ROOT` | `~/.investary/documents` | Original PDFs |
| `INVESTARY_EMAIL_STORAGE_ROOT` | `~/.investary/emails` | Original EML files |
| `INVESTARY_MAX_UPLOAD_SIZE` / `INVESTARY_MAX_REQUEST_SIZE` | `50MB` / `52MB` | Multipart limits (larger → 413) |
| `INVESTARY_EMAIL_MAX_SIZE` | `25MB` | Largest accepted `.eml` |
| `INVESTARY_DOWNLOAD_EXTRA_DOMAINS` | — | Extra domains allowed for report downloads (comma-separated) |
| `INVESTARY_DOWNLOAD_MAX_SIZE` | `50MB` | Largest downloaded report |
| `INVESTARY_DOWNLOAD_TEMP_DIR` | `~/.investary/tmp` | Partial downloads before validation |
| `INVESTARY_DOWNLOAD_WORKER_ENABLED` | `true` | Run the background download worker |
| `INVESTARY_CORS_ALLOWED_ORIGINS` | none (dev: `http://localhost:4200`) | Comma-separated browser origins |
| `INVESTARY_LOG_FORMAT` | `ecs` (dev: plain text) | Structured console logging format |
| `INVESTARY_AI_EMBEDDING_PROVIDER` / `INVESTARY_AI_CHAT_PROVIDER` | `none` | `ollama` to enable the knowledge base / assistant |
| `INVESTARY_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `INVESTARY_EMBEDDING_MODEL` / `INVESTARY_EMBEDDING_DIMENSIONS` | `bge-m3` / `1024` | Embedding model and its vector size (part of the index version) |
| `INVESTARY_CHAT_MODEL` | `qwen3:8b` | Chat model for answers |
| `INVESTARY_AI_TIMEOUT` | `120s` | Provider request / answer timeout |
| `INVESTARY_KNOWLEDGE_AUTO_INDEX` | `true` | Track new and existing documents/emails for indexing |
| `INVESTARY_KNOWLEDGE_WORKER_ENABLED` | `true` | Run the background indexing worker |

**Brokerages** are configured under `investary.research.brokerages` (defaults: TCBS `tcbs.com.vn`,
SSI `ssi.com.vn`, VNDIRECT `vndirect.com.vn`, VCBS `vcbs.com.vn`). Their domains are used to:
- identify the brokerage from the sender's address
- build the **download allowlist** (sub-domains included)

Download timeouts, redirects, retries and worker settings are under `investary.download.*`.

Secrets live only in `infrastructure/.env` or environment variables. `.env`, `storage/`, `*.pdf` and
`*.eml` are git-ignored; only the synthetic test fixtures under `backend/src/test/resources/eml/` are
tracked.

## Email ingestion (Phase 2)

### Import workflow

1. **Validate:** `.eml` extension or `message/rfc822`, size ≤ `INVESTARY_EMAIL_MAX_SIZE`.
2. **Duplicates:** the SHA-256 of the raw file identifies a duplicate. A re-import returns `409` with
   `existingEmailId`. `Message-ID` is stored and indexed but is **not** treated as unique. A different
   file with the same Message-ID is imported, and the detail view lists it under `sameMessageIdAs`.
3. **Parse** with Angus Mail (Jakarta Mail), leniently:
   - RFC 2047 headers, any charset, multipart trees, attachments.
   - Input without email headers is rejected (`400 INVALID_EMAIL`).
   - Problems in single parts mark the email `PARTIAL` but keep it.
4. **Store** the original as `<email-root>/yyyy/MM/<uuid>.eml`. The original filename is never used in a path.
5. **Persist**, in one transaction: metadata, attachment metadata, classified links and research items.
   If that fails, the stored file is removed.
6. **After commit:** PDF attachments go into the document library (`ATTACHMENT` relationship).

### Parsing and content

- HTML is the primary body; plain text is used when there is no HTML.
- The body is walked in reading order as *blocks*:
  - Headings are `h1–h6` or short, fully bold paragraphs; `1. …` marks a top-level heading.
  - Each block records its section path, its leading bold label (`VN-Index:`), and its language
    (Vietnamese vs English, by diacritics).
- **Normalized text** keeps every figure as written and drops invisible preheader filler.
- **HTML is untrusted.** The preview endpoint serves a jsoup-sanitized copy:
  - no scripts, event handlers, styles, forms or embedded content
  - images replaced by their alt text, so no tracking pixels load
  - links unwrapped
  - served with a strict `Content-Security-Policy`
  The UI shows it in a sandboxed iframe without scripts; readable text is the default view.

### Links and classification

- Every `http(s)` link is stored **exactly as found** (`originalUrl`). Known click trackers are unwrapped
  into `targetUrl`; today that is Amazon SES `…awstrack.me/L0/<encoded target>/…`, as used by TCBS.
  Downloads use the target, so the tracker is never contacted. `#anchors`, `mailto:`, `javascript:`
  and similar links are ignored.
- Links are deduplicated per email by target URL.
- **Rule-based classifier.** Rules run in order, first match wins, and the rule name is stored with the link:

  | Category | Typical evidence | Initial status |
  |----------|------------------|----------------|
  | `NAVIGATION` | unsubscribe/subscribe, social sites, login/app links, "view all reports" listings | `SKIPPED` |
  | `PROMOTION` | promotion sections (`ưu đãi`, `khuyến mãi`), `/uu-dai/` paths, promo wording | `SKIPPED` |
  | `STOCK_RESEARCH` | PDF + ticker in link text or file name (`IMP - …`, `/oneclick/VIC.pdf`) | `READY` |
  | `MARKET_REPORT` | PDF + market/bond/macro wording | `READY` |
  | `RESEARCH_REPORT` | any other PDF, or report wording on a non-PDF link | `READY` / `DISCOVERED` |
  | `OTHER` | nothing confident | `DISCOVERED` |

  Statuses: `DISCOVERED`, `READY`, `DOWNLOADING`, `DOWNLOADED`, `FAILED`, `SKIPPED`. A URL ending in
  `.pdf` is only a hint; the downloaded bytes must be a PDF.

### Secure report downloads

Nothing is downloaded automatically. A user picks a link, and
`POST /api/v1/emails/{id}/links/{linkId}/download` queues a job (`202 Accepted`, `Location` = link status).

- **URL policy, applied to the request and to every redirect hop (max 5, followed manually):**
  - **https only** (`allow-http=false`)
  - host on the **allowlist** (brokerage domains + `INVESTARY_DOWNLOAD_EXTRA_DOMAINS`)
  - no credentials in the URL
  - standard ports only
- **Address check (SSRF / DNS rebinding):**
  - The HTTP client's DNS resolver rejects any host that resolves to loopback, private (RFC 1918),
    carrier-grade NAT, link-local (incl. `169.254.169.254`), multicast, reserved, IPv6 unique-local,
    or IPv4-mapped/NAT64 forms of those.
  - The client connects to exactly the addresses it checked, on every connection.
- **Limits:** no proxy, cookies or automatic retries in the client; connect/read timeouts; an overall
  deadline; a size cap by `Content-Length` and while streaming.
- **Validation:** the body is streamed to a temp file and accepted only if it carries a `%PDF-`
  signature. HTML login pages and expired links are rejected. Temp files are always deleted.
- **Storage:** the PDF goes through the Phase 1 `DocumentService` (same checks and storage). Identical
  content links to the **existing** document instead of storing a copy.

### Background jobs, errors and retry

- `download_jobs` is a database-backed queue: one job per link.
- **Worker:** a poller (every 2 s) claims jobs with `FOR UPDATE SKIP LOCKED` and a fresh claim token.
  It runs them on a bounded pool (`concurrency=2`) and only claims as many jobs as it has free threads.
- **Leases:** a job still `RUNNING` after its lease (5 min), for example after a crash or restart, is
  re-queued, or failed if it used all its attempts. A worker whose claim expired cannot overwrite the result.
- **Failures:**
  - Transient (timeouts, 5xx, 429) retry automatically with exponential backoff (`max-attempts=3`).
  - Permanent ones fail at once: expired/404/410, not a PDF, too large, blocked by policy.
  - The error is stored on the link and shown in the UI.
- **Retry and idempotency:** retrying is the same `POST …/download` on a `FAILED` link. Requesting a
  link that is already downloading returns `202`; one already downloaded returns `200`.

### Research items (deterministic)

- Sections are recognised by bilingual heading keywords:
  - *Thị trường chung / General market* → `MARKET_UPDATE`
  - *Vĩ mô / Macro* → `MACRO_UPDATE`
  - *Ngành / Sectors* → `INDUSTRY_UPDATE`
  - *Tin doanh nghiệp / Corporate news* → `COMPANY_NEWS` (ticker from the `MWG:` label)
  - *Khuyến nghị / Recommendation* → `STOCK_RECOMMENDATION`
- A recommendation is only recorded when the source states one explicitly as `TICKER - <rating> - …`
  with a known rating phrase. The rating is kept **verbatim** (e.g. "Chờ mua"); this is the
  brokerage's statement, **not** an Investary recommendation.
- Vietnamese and English copies of one recommendation are merged into one item.
- Every item keeps the original paragraph, its language, `source_location` (block + section path) and
  `extraction_method = RULES_V1`. No figures are parsed, computed or inferred. Promotions and footers
  are ignored.

### Retention policy for deletes

- **Deleting an email** removes the email, its links, research items, attachment metadata, download
  jobs and document associations, and deletes its original `.eml`. **PDFs in the document library are
  never deleted with an email**, because other emails may reference them.
- **Deleting a document** removes its associations; links that pointed at it become `READY` again.

## Research assistant (Phase 3)

1. Install [Ollama](https://ollama.com) and pull the models: `ollama pull bge-m3` and `ollama pull qwen3:8b`.
2. In `infrastructure/.env` set `INVESTARY_AI_EMBEDDING_PROVIDER=ollama` and `INVESTARY_AI_CHAT_PROVIDER=ollama`
   (see `.env.example`), then restart the backend.
3. Existing and new PDFs and emails are indexed in the background. Each document and email page shows
   its index status with **Index now / Retry / Reindex**. Scanned PDFs show "OCR required".
4. Open **Research assistant**, ask in Vietnamese, optionally filter by brokerage, ticker, source type or
   dates. Click `S1`, `S2`… to see the cited passage and open the source.

- Without a provider the app works as before; the assistant page explains what is missing and the
  AI endpoints answer `503 AI_NOT_CONFIGURED`.
- **Changing the embedding model** (or chunk settings) makes existing chunks *stale*: they are no longer
  searched until you reindex them (see below). Unchanged text with the same model is never re-embedded.
- Answers are grounded in your stored documents only; brokerage ratings are third-party opinions, the
  data may be outdated, and nothing here is investment advice.

```bash
curl -X POST -H "Content-Type: application/json" -d '{"scope":"STALE"}' http://localhost:8080/api/v1/knowledge/reindex
```

### Upgrading an existing database to Phase 3

The Compose file now uses `pgvector/pgvector:0.8.7-pg17` (Debian) instead of `postgres:17.6-alpine`. The
data volume is reused. Because text collation can differ between Alpine and Debian, back up first and
rebuild indexes once after switching:

```bash
docker exec investary-postgres pg_dump -U investary -d investary -Fc > investary-backup.dump
```

```bash
docker compose -f infrastructure/docker-compose.yml up -d --wait
```

```bash
docker exec investary-postgres psql -U investary -d investary -c "REINDEX DATABASE investary;"
```

Flyway applies `V3` on the next backend start.

## Database migrations

Migrations are in `backend/src/main/resources/db/migration` as `V<n>__<description>.sql`. Flyway runs
them on startup. Never edit an applied migration; add a new one.

| Version | Tables |
|---------|--------|
| `V1` | `document` (unique storage key and SHA-256, checks, indexes) |
| `V2` | `source_messages`, `source_attachments`, `source_links`, `message_documents`, `research_items`, `download_jobs` |
| `V3` | `vector` extension, `knowledge_sources`, `knowledge_chunks` |

V2 is additive: it leaves `document` untouched and only adds foreign keys to it
(`ON DELETE SET NULL` / `CASCADE` on association rows).

## Testing

```bash
cd backend && ./mvnw verify          # unit, web-layer, WireMock and PostgreSQL (Testcontainers) tests
cd frontend && npm test -- --watch=false
cd frontend && npm run build
```

| Area | Suites |
|------|--------|
| Phase 1 documents | `PdfSignatureValidatorTest`, `LocalFileSystemDocumentStorageTest`, `DocumentServiceTest`, `DocumentControllerTest`, `DocumentIntegrationTest` |
| EML parsing | `EmlParserTest` (HTML-only, plain, multipart, RFC 2047 B/Q headers, attachments, bad charset, broken MIME, non-emails), `ContentAnalyzerTest`, `HtmlSanitizerTest`, `TrackingLinkUnwrapperTest` |
| Links & research | `LinkClassifierTest`, `ResearchItemExtractorTest` |
| Download security | `UrlPolicyTest`, `RestrictedAddressDnsResolverTest` (private ranges, mapped IPv6, DNS rebinding), `ReportDownloaderTest` (WireMock: redirects, blocked hops, loops, 404/410, 503, HTML instead of PDF, size limits, timeouts, localhost blocked) |
| Persistence | `DownloadJobQueueTest` (concurrent claims, lease recovery, stale tokens, backoff), `FlywayUpgradeTest` (Phase 1 data survives V2), `LocalFileStoreTest` |
| End-to-end | `EmailIngestionIntegrationTest` (import → links → background download from WireMock → library → provenance across emails → delete rules → retries → PDF attachments → search/filter) |
| Knowledge base & RAG | `PdfTextExtractorTest`, `EmailTextExtractorTest`, `TextChunkerTest`, `TickerDetectorTest`, `EmbeddingServiceTest`, `QueryAnalysisTest`, `CitationValidatorTest`, `ResearchAssistantServiceTest`, `KnowledgeControllerTest`, `ResearchControllerTest`, `KnowledgeBaseIntegrationTest`, `KnowledgeEvaluationTest` (writes `backend/target/knowledge-eval-report.md`) |
| Frontend | API services, error mapping, file-size pipe, app shell, email inbox (search debounce, sender filter), email detail (retry action, download polling), index status, research assistant |

- Tests that need Docker are **skipped** (not failed) without a Docker daemon; check the surefire summary.
- No test contacts a real website or a real AI provider (fake embedding and scripted chat models).
- Fixtures are synthetic: `backend/src/test/resources/eml/tcbs-style-newsletter.eml` copies the
  structure of a real TCBS newsletter with invented content.

### Trying it with your own emails

Import a real `.eml` through the UI. It stays in your local email store and is never committed.
Downloads only reach allowlisted brokerage domains over HTTPS. To allow another provider, add it to
`investary.research.brokerages` or set `INVESTARY_DOWNLOAD_EXTRA_DOMAINS`.

## Working with AI coding agents

The repository carries a small agent harness, so a new AI session starts with the project's rules
instead of rediscovering them:

- **`AGENTS.md`:** tool-neutral operating guide covering module boundaries, exact commands, rules for
  changes and the "before you finish" checklist. `CLAUDE.md` imports it, and `backend/CLAUDE.md` /
  `frontend/CLAUDE.md` add stack-specific notes.
- **`docs/failures/`:** failure memory. Real problems from earlier phases, each with Prevention and Detection.
- **Checks:**
  - `ArchitectureTest` (part of `./mvnw verify`) enforces the module dependency direction.
  - `node scripts/check-harness.mjs` blocks committed real emails/PDFs/`.env`, edits to applied Flyway
    migrations, stale paths or npm scripts in the agent docs, and incomplete failure notes.
  - After adding a migration, record it with `node scripts/check-harness.mjs --update-migrations`.

Details: `docs/harness/adoption-report.md`.

## API

Errors use RFC 9457 `application/problem+json` with a machine-readable `code`:
`VALIDATION_FAILED`, `INVALID_DOCUMENT`, `DOCUMENT_NOT_FOUND`, `DUPLICATE_DOCUMENT`, `INVALID_EMAIL`,
`DUPLICATE_EMAIL`, `EMAIL_NOT_FOUND`, `LINK_NOT_FOUND`, `DOWNLOAD_NOT_ALLOWED`, `PAYLOAD_TOO_LARGE`,
`STORAGE_ERROR`, `INTERNAL_ERROR`, `KNOWLEDGE_JOB_NOT_FOUND`, `AI_NOT_CONFIGURED` (503), `AI_PROVIDER_ERROR` (502),
`AI_TIMEOUT` (504).

### Documents

| Method | Path | Result |
|--------|------|--------|
| `POST` | `/api/v1/documents` | `201` metadata · `400` non-PDF / invalid fields · `409` duplicate (`existingDocumentId`) · `413` |
| `GET` | `/api/v1/documents?page=0&size=20&sort=uploadedAt,desc` | `200` page envelope (`size` ≤ 100) |
| `GET` | `/api/v1/documents/{id}` | `200` · `404` |
| `GET` | `/api/v1/documents/{id}/download` | `200` `application/pdf` attachment · `404` |
| `DELETE` | `/api/v1/documents/{id}` | `204` · `404` |

Upload fields: `file` (required), `brokerageCompany` (≤200), `reportType` (`MARKET_OVERVIEW`,
`MACROECONOMIC`, `INDUSTRY_ANALYSIS`, `STOCK_ANALYSIS`, `ETF_RESEARCH`, `OTHER`), `ticker`
(1–15 of `A-Z 0-9 . -`), `publishedOn` (`yyyy-MM-dd`, not in the future).

### Emails

| Method | Path | Result |
|--------|------|--------|
| `POST` | `/api/v1/emails/import` (multipart `file`) | `201` detail · `400` · `409` (`existingEmailId`) · `413` |
| `GET` | `/api/v1/emails?q=&sender=&documentId=&page=&size=` | `200` page of summaries with link/report counts |
| `GET` | `/api/v1/emails/senders` | distinct senders |
| `GET` | `/api/v1/emails/{id}` | metadata, recipients, normalized text, languages, attachments, counts |
| `GET` | `/api/v1/emails/{id}/html` | sanitized HTML preview (strict CSP) |
| `GET` | `/api/v1/emails/{id}/original` | original `.eml` (`message/rfc822`) |
| `GET` | `/api/v1/emails/{id}/links` · `/{id}/links/{linkId}` | links with category, status, rule, ticker and download job state |
| `POST` | `/api/v1/emails/{id}/links/{linkId}/download` | `202` queued/running (+`Location`) · `200` already stored · `422 DOWNLOAD_NOT_ALLOWED` |
| `GET` | `/api/v1/emails/{id}/documents` | linked library documents (`ATTACHMENT` / `LINKED_REPORT`) |
| `GET` | `/api/v1/emails/{id}/research-items` | extracted items with provenance |
| `DELETE` | `/api/v1/emails/{id}` | `204` (see retention policy) |

### Knowledge base and research assistant

| Method | Path | Result |
|--------|------|--------|
| `GET` | `/api/v1/knowledge/status` | models, index version, counts, stale/untracked sources |
| `POST` | `/api/v1/knowledge/index/documents/{id}` · `/index/emails/{id}` | `202` job (index, retry or reindex) · `404` |
| `GET` | `/api/v1/knowledge/documents/{id}` · `/emails/{id}` · `/jobs/{id}` | index state (`NOT_INDEXED` if untracked) |
| `POST` | `/api/v1/knowledge/reindex` with `scope` = `ALL`, `FAILED`, `STALE` or `MISSING` | `202 {queued}` |
| `POST` | `/api/v1/research/search` | hybrid search: `{query, ticker, brokerage, sourceType, from, to, maxResults}` |
| `POST` | `/api/v1/research/ask` | `{question, …same filters}` → answer, `evidenceStatus`, citations, gaps, warnings |

### curl examples

```bash
# Documents
curl -F "file=@report.pdf;type=application/pdf" -F brokerageCompany="SSI Research" \
     -F reportType=STOCK_ANALYSIS -F ticker=VNM http://localhost:8080/api/v1/documents
curl "http://localhost:8080/api/v1/documents?page=0&size=20"
curl -OJ http://localhost:8080/api/v1/documents/<id>/download

# Emails
curl -F "file=@newsletter.eml;type=message/rfc822" http://localhost:8080/api/v1/emails/import
curl "http://localhost:8080/api/v1/emails?q=c%C6%A1%20h%E1%BB%99i"
curl http://localhost:8080/api/v1/emails/<id>/links
curl -X POST http://localhost:8080/api/v1/emails/<id>/links/<linkId>/download
curl http://localhost:8080/api/v1/emails/<id>/links/<linkId>        # poll status
curl http://localhost:8080/api/v1/emails/<id>/documents
curl -X DELETE http://localhost:8080/api/v1/emails/<id>

# Research assistant
curl http://localhost:8080/api/v1/knowledge/status
curl -X POST -H "Content-Type: application/json" \
     -d '{"question":"TCBS đang khuyến nghị gì về cổ phiếu SCS?","brokerage":"TCBS"}' \
     http://localhost:8080/api/v1/research/ask
```

## Known limitations

- No authentication, authorization or rate limiting; local single-user use only. No virus scanning.
- PDF validation checks the signature only; malformed or encrypted PDFs are accepted.
- **Opaque click trackers** (other than Amazon SES) cannot be unwrapped. Their links are followed with
  per-hop checks, so the tracker's domain must be added to the allowlist.
- Reports behind a login, or served as an HTML viewer page, fail with a clear "not a PDF" error.
- Classification and extraction rules were tuned on TCBS's newsletter layout and generic patterns.
  Other brokers' templates may need extra keywords. Unrecognised sections stay unstructured, and
  non-recommendation items appear once per language.
- Forwarded emails attached as `message/rfc822` are stored as attachments; they are not parsed as
  separate emails.
- If the process stops between storing an email and importing its PDF attachments, those attachments
  are not imported. Reprocessing from the stored original is a planned feature.
- Orphaned files (failed post-commit deletes, crashes) are not garbage-collected yet.
- The safe HTML preview drops styling, so complex newsletters look plain; the text view is the
  primary reading view.
- **Research assistant:** no OCR (scanned PDFs are skipped); tickers are 3-character codes found by a
  heuristic; the similarity threshold is untuned; answers were only tested with fake models, not with
  a real LLM; no streaming or conversation memory. Details in
  [docs/phase-3-implementation.md](docs/phase-3-implementation.md#known-limitations).

## Next phases

- **Phase 3 — done:** knowledge base, hybrid search and RAG assistant with validated citations.
- **Phase 4 — recommended:** live-model evaluation and tuning, OCR, cited summaries and brokerage
  comparison views, a ticker master list, editable metadata with reindex, HNSW index (see
  [docs/phase-3-implementation.md](docs/phase-3-implementation.md#recommended-phase-4-scope)).
- **Phase 5 — Portfolio & decisions:** holdings, watchlist and a decision journal linked to research.
- Later: authentication, S3-compatible storage behind the storage ports, Gmail/Drive ingestion.
