# Agent harness — adoption report

- Date: 2026-10-29
- Branch: `phase-2-email-ingestion`

## Starting point

Before this change the repository had no `AGENTS.md`, `CLAUDE.md`, `.github/`, `scripts/` or CI.
Project knowledge lived in `README.md`, `docs/architecture.md` and four ADRs. The toolchain and
framework pitfalls found during Phase 1–2 existed only in session transcripts.

## Files added

| File | Purpose |
|---|---|
| `AGENTS.md` | Tool-neutral operating guide: purpose, module boundaries, commands, change rules, finish checklist, pitfalls |
| `CLAUDE.md` | Imports `AGENTS.md`; Claude Code specifics (language, browser pane, background servers, real sample data) |
| `backend/CLAUDE.md` | Spring Boot 4 / Jackson 3 / Testcontainers 2 / Hibernate 7 APIs, persistence and test conventions |
| `frontend/CLAUDE.md` | Angular 22 conventions, email-HTML rule, Vitest pitfalls, no `npx` |
| `docs/failures/0001`–`0006` | Failure memory for problems that actually happened |
| `backend/src/test/java/com/investary/ArchitectureTest.java` | Module dependency rules, run by `./mvnw verify` |
| `scripts/check-harness.mjs` | Sensitive-file, migration-immutability, docs-drift and failure-memory checks |
| `docs/harness/migration-checksums.json` | Recorded checksums of V1 and V2 |
| `.run/Harness check.run.xml` | IntelliJ run configuration for the script |

`README.md` gained a short "Working with AI coding agents" section and a run-config row.

## Rules and how they are enforced

| Rule | Enforcement |
|---|---|
| Dependency direction `email → research → document → common`; no access to another module's `api` / `persistence` / `storage` | `ArchitectureTest` (automatic) |
| Never edit an applied Flyway migration | `check-harness.mjs` checksum comparison (automatic) |
| No real emails, PDFs or `.env` in git | `.gitignore` + `check-harness.mjs` (automatic) |
| Agent docs only reference existing paths and npm scripts; run configs point at real paths | `check-harness.mjs` docs drift (automatic) |
| Failure notes say how recurrence is prevented and detected | `check-harness.mjs` failure memory (automatic) |
| Java 21 required | `maven-enforcer-plugin` (automatic, pre-existing) |
| Use `./mvnw`, not `mvn`; no `npx` | Instructions + failure note 0001 (manual; environment-specific) |
| URLs from emails only via `ReportDownloader` / `UrlPolicy`; email HTML only via `HtmlSanitizer` | Instructions + existing security tests (`UrlPolicyTest`, `ReportDownloaderTest`, `HtmlSanitizerTest`); a new fetch path is a manual review point |
| Workers enabled in a test need `@DirtiesContext` | Instructions + failure note 0004 (manual review point) |

## Commands run and results

| Command | Result |
|---|---|
| `node scripts/check-harness.mjs` (before recording) | Failed as expected: V1 and V2 not recorded |
| `node scripts/check-harness.mjs --update-migrations` | Recorded 2 checksums (migrations identical to `HEAD`) |
| `node scripts/check-harness.mjs` | Passed |
| Deliberate violations: appended to V1; failure note without Detection; force-added `tmp-real.eml` + `tmp.pdf`; stale path and `npm run lint` in `AGENTS.md` | Each one reported by the matching check, then reverted |
| Plain `git add` of the fake `.eml` / `.pdf` | Refused by `.gitignore` (first line of defence) |
| `./mvnw test -Dtest=ArchitectureTest` | 3/3 passed |
| Same, with a temporary `document` class importing `email.parsing` and `research.link` | Failed, naming the file and both imports; the file was removed afterwards |
| `./mvnw verify` | 189 tests, 0 failures, 0 skipped (186 existing + 3 `ArchitectureTest`) |

## Assumptions and follow-up

- There is no CI and no git remote yet. When one is added, run `./mvnw verify`, `npm test -- --watch=false`,
  `npm run build` and `node scripts/check-harness.mjs` in a workflow.
- Whether Claude Code loads `CLAUDE.md` (and `AGENTS.md` through `@AGENTS.md`) in a new session has
  to be confirmed by starting one; it could not be tested from this session.
- The docs-drift check only verifies backtick paths that contain `/`. Prose claims about behaviour are
  kept accurate by review.

## Measuring effectiveness

- New sessions should not repeat the problems in `docs/failures/` (plain `mvn`, `npx`, Boot 3 imports,
  module cycles).
- Count harness check failures caught before commit, and add a failure note whenever a new
  class of mistake shows up twice.
