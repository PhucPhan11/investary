# AGENTS.md — Investary

Operating guide for coding agents (Claude Code, Copilot, Codex, …). Read this first; details live in
`README.md` and `docs/architecture.md`.

## What this is

A personal, local, single-user investment research assistant. It has three parts:
- **Phase 1:** a PDF research library.
- **Phase 2:** brokerage email (`.eml`) ingestion, link classification, secure background download of
  linked reports, and deterministic research items.
- **Planned:** text extraction/search, then AI/RAG, then a portfolio module. Do not add AI or new
  infrastructure unless asked.

- Backend: Spring Boot 4.1, Java 21, Maven wrapper, PostgreSQL 17 + Flyway, in `backend/`.
- Frontend: Angular 22 (standalone, signals, zoneless, Vitest, SCSS, no UI library), in `frontend/`.
- Infra: `infrastructure/docker-compose.yml` (Postgres only), config from `infrastructure/.env`.

## Module boundaries (enforced by `ArchitectureTest`)

```
email ──▶ research ──▶ document ──▶ common
```

| Module | Owns |
|---|---|
| `com.investary.document` | PDF library |
| `com.investary.email` | EML import, parsing, sanitizing, original files |
| `com.investary.research` | Links, classification, downloads/jobs, research items, `message_documents` provenance |
| `com.investary.common` | Errors (`ApiException`), `LocalFileStore`, `AfterCommit`, `UrlCodec`, `PageResponse`, CORS |

- `common` imports no feature module. `document` imports neither `email` nor `research`. `research`
  never imports `email`.
- Cross-module calls go to `application` services, never to another module's `persistence`, `storage` or `api`.
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
`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"` (bash), or the
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
- **Config:** new settings go to `application.yml` with an env override, and are documented in the
  `README.md` configuration table.
- **Test data:**
  - Never commit real emails, PDFs or `infrastructure/.env`.
  - Fixtures are synthetic and live in `backend/src/test/resources/eml/`.
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
