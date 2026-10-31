# AGENTS.md — Investary

Operating guide for coding agents (Claude Code, Copilot, Codex, …). Read this first; details live in
`README.md` and `docs/architecture.md`.

## What this is

A personal, local, single-user investment research assistant. It has three parts:
- **Phase 1:** a PDF research library.
- **Phase 2:** brokerage email (`.eml`) ingestion, link classification, secure background download of
  linked reports, and deterministic research items.
- **Phase 3:** a research knowledge base (PDF/email text extraction, chunks, pgvector embeddings,
  hybrid search) and a RAG assistant with backend-validated citations (`docs/phase-3-implementation.md`).
- **Phase 4:** investor profile and goals, transaction-based portfolios with manual prices, research
  matching, impact assessments and a portfolio-aware assistant (`docs/phase-4-implementation.md`).
- **Planned:** see "Next phases" in `README.md`. Do not add new infrastructure unless asked.

- Backend: Spring Boot 4.1, Java 21, Maven wrapper, PostgreSQL 17 + Flyway, in `backend/`.
- Frontend: Angular 22 (standalone, signals, zoneless, Vitest, SCSS, no UI library), in `frontend/`.
- Infra: `infrastructure/docker-compose.yml` (PostgreSQL 17 + pgvector), config from `infrastructure/.env`.
- AI: Spring AI 2.0 model interfaces only (`EmbeddingModel`, `ChatModel`); provider by config, default
  `none` (local default: Ollama). No vector-store starter; the schema is ours (`V3`).

## Module boundaries (enforced by `ArchitectureTest`)

```
knowledge ──▶ email ──▶ research ──▶ document ──▶ common
intelligence ──▶ knowledge, portfolio, investor, research (Keywords only)
portfolio ──▶ common          investor ──▶ common
```

| Module | Owns |
|---|---|
| `com.investary.document` | PDF library |
| `com.investary.email` | EML import, parsing, sanitizing, original files |
| `com.investary.research` | Links, classification, downloads/jobs, research items, `message_documents` provenance |
| `com.investary.knowledge` | Extraction, chunking, embeddings, `knowledge_sources` jobs, hybrid search, RAG assistant, citations |
| `com.investary.investor` | Investor profile and goals |
| `com.investary.portfolio` | Instruments, prices, portfolios, transactions; holdings/valuation/allocation computed on read |
| `com.investary.intelligence` | Research matching, analysis, impact assessments, portfolio-aware assistant |
| `com.investary.common` | Errors (`ApiException`), `LocalFileStore`, `AfterCommit`, `UrlCodec`, `PageResponse`, CORS |

- `common` imports no feature module. `document` imports neither `email` nor `research`. `research`
  never imports `email`.
- Cross-module calls go to `application` services, never to another module's `persistence`, `storage` or `api`.
- `intelligence` may only use `*QueryService` classes from `portfolio` and `investor`: AI output must never
  change portfolio, transaction or profile data.
- `document` reacts to nothing; it publishes `DocumentDeletedEvent`, which `research` listens to.
- Inside a module, use the layers `api / application / domain / persistence / storage` (`email` also has `parsing`).

## Sources of truth

- Original files are authoritative: PDFs in `investary.storage.root`, EMLs in
  `investary.email.storage.root` (`yyyy/MM/<uuid>.eml`). Database content columns, links and research
  items are **derived**.
- Never put storage keys or file paths in API responses or error messages.
- Research data copies values from the source and never invents them. A source rating such as
  "Chờ mua" is the brokerage's statement, not Investary advice.

## Commands

Windows note: the default `JAVA_HOME` on this machine is JDK 17. Set JDK 21 first:
`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"` (bash), or the
`$env:JAVA_HOME` equivalent in PowerShell.

| Purpose | Command (from repo root unless noted) |
|---|---|
| Start Postgres | `docker compose -f infrastructure/docker-compose.yml up -d --wait` |
| Backend run (dev profile) | `cd backend && ./mvnw spring-boot:run` |
| Backend tests | `cd backend && ./mvnw verify` |
| Frontend run | `cd frontend && npm start` |
| Frontend tests | `cd frontend && npm test -- --watch=false` |
| Frontend build | `cd frontend && npm run build` |
| Harness checks | `node scripts/check-harness.mjs` |

- Always use `./mvnw`, never `mvn`. The installed Maven mirrors to an unreachable corporate Nexus.
- Do not use `npx`; NVM blocks it here. Use `npm run …` or `node node_modules/.bin/…`.
- Testcontainers tests are **skipped, not failed**, without Docker. Read the surefire summary
  (`Skipped: 0`) before claiming the integration tests passed.
- IntelliJ run configurations are in `.run/`.

## Rules for changes

- **Database:**
  - Never edit an existing Flyway migration. Add `backend/src/main/resources/db/migration/V<n>__name.sql`,
    then run `node scripts/check-harness.mjs --update-migrations` to record its checksum.
  - Keep `spring.jpa.hibernate.ddl-auto=validate`; schema changes go only through Flyway.
- **Errors:** expected failures extend `ApiException` (status + `ErrorCode`), so `GlobalExceptionHandler`
  maps them without importing feature code.
- **Untrusted input:**
  - Every URL from an email is untrusted. Fetch only through `ReportDownloader`, which applies
    `UrlPolicy` and `RestrictedAddressDnsResolver`.
  - Never add a generic "fetch this URL" endpoint, and never relax the download defaults outside tests.
  - Email HTML is untrusted. Serve it only through `HtmlSanitizer`. The UI shows it in a sandboxed
    iframe, never via `innerHTML` or `bypassSecurityTrustHtml`.
- **Deletes:** deleting an email must never delete library PDFs (retention policy in `README.md`).
- **AI / knowledge base:**
  - Text from documents and emails is untrusted. Instructions go only in the system message
    (`PromptBuilder`); source text stays inside `<source>` elements.
  - Citations are built from stored chunks; never trust ids, titles, pages or figures from model output.
    Keep `CitationValidator` able only to lower the evidence status.
  - Never log document text, prompts, answers or API keys; log ids and counts.
  - A change to chunking or extraction output must bump `IndexVersion.PIPELINE`; never mix vectors of
    different models or dimensions.
  - Tests must not call a real AI provider: use `FakeAiConfiguration` + `classpath:fake-ai.properties`.
  - Never present a brokerage rating as Investary's recommendation.
- **Portfolios:**
  - Holdings are computed from transactions by `HoldingCalculator` (weighted-average cost, BigDecimal
    `DECIMAL128`, chronological replay). Never store derived holdings; round only for display (`Money`).
  - Every transaction change must replay the full history under the portfolio lock and reject oversells.
  - Never show or compute market value or unrealized P&L without a real price observation; label
    cost-basis and market-value allocation separately.
  - Never present a portfolio-intelligence category as a trading signal or recommendation.
- **Config:** new settings go to `application.yml` with an env override, and are documented in the
  `README.md` configuration table.
- **Test data:**
  - Never commit real emails, PDFs or `infrastructure/.env`.
  - Fixtures are synthetic and live in `backend/src/test/resources/eml/`; research PDFs are generated in
    tests (`TestResearchPdfs`), never committed. The evaluation set is `backend/src/test/resources/knowledge/eval/questions.json`.
  - Tests must not contact real websites; use WireMock.
- **Git:** do not commit, push or create branches unless the user asks. Work happens on per-phase branches.

## Before you finish

1. Run the relevant tests: `./mvnw verify` and/or `npm test -- --watch=false` plus `npm run build`.
2. Run `node scripts/check-harness.mjs`.
3. If you changed behaviour, commands or config, update `README.md` and `docs/architecture.md`.
   Record architecture decisions in `docs/adr/`.
4. If something went wrong that could happen again, add a note in `docs/failures/`. It must include
   `## Prevention` and `## Detection` sections; the check script enforces this.
5. Report exactly which commands ran and their results; never claim tests passed if they were skipped.

## Known pitfalls (details in `docs/failures/`)

- Maven mirror, JDK 17 and `npx` blocked → `docs/failures/0001-toolchain-maven-jdk-npx.md`
- Spring Boot 4 / Jackson 3 / Testcontainers 2 package moves → `docs/failures/0002-spring-boot-4-package-moves.md`
- Module dependency cycles → `docs/failures/0003-module-dependency-cycle.md`
- Background worker in integration tests → `docs/failures/0004-integration-test-background-worker.md`
- Real personal emails → `docs/failures/0005-real-email-data.md`
- Claude browser pane blocks iframes → `docs/failures/0006-embedded-browser-iframe.md`
- Java text-block SQL losing trailing spaces → `docs/failures/0007-text-block-sql-trailing-space.md`
- IntelliJ run configs: terminal before-launch task, lost Maven wrapper → `docs/failures/0008-intellij-run-config-terminal-before-launch.md`
- Business dates vs JVM time zone → `docs/failures/0009-market-date-vs-jvm-zone.md`
- The `.gitignore` storage rule hid the Java `storage` packages → `docs/failures/0010-gitignore-storage-hides-java-packages.md`
