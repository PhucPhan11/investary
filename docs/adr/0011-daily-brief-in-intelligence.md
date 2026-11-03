# ADR 0011 — Daily briefs in the intelligence module, on their own job queue

## Status

Accepted (Phase 5 / Milestone 3).

## Context

The daily brief summarises research that became available in a reporting period for a portfolio. It needs
the knowledge base (search, chunks, prompt and citation conventions, chat gateway), read-only portfolio and
investor data, and the impact assessments of the period. It must run on a schedule, survive restarts, never
generate the same scheduled brief twice, and keep every generation as an immutable revision.

## Decision

- **Module:** briefs live in `intelligence`. It already depends on exactly `knowledge`, `portfolio` and
  `investor` (`*QueryService` only) and `research` (`Keywords`), and owns the helpers a brief reuses
  (`PortfolioContext`, holdings snapshot and hash, impact assessments). No module edge is added;
  `ArchitectureTest` keeps its allowed map and gains a check that nothing imports `automation`.
- **Queue:** a sibling table `brief_jobs` with the M2 pattern (`FOR UPDATE SKIP LOCKED`, claim token, lease,
  attempts with backoff, unique idempotency key). `automation_jobs` is tied to research workflows (non-null
  workflow id, job-type check) and `intelligence` may not import `automation`.
- **Scheduling:** the worker computes the latest fire time of a Spring cron in a configured IANA zone and
  inserts one job per portfolio with the key `scheduled:{scope}:{periodEnd}:{configVersion}` (insert once).
  Only the latest run within a catch-up window is generated after downtime.
- **"New" research:** a source is new in the period in which it first became searchable
  (`knowledge_sources.first_indexed_at`, set once and kept across reindexing), answered by a read-only
  `ResearchAvailabilityService` in `knowledge`. Sources imported in the period but not searchable are listed
  as pending, using the knowledge index state.
- **Revisions:** brief rows are written once, when a job completes, in a token-guarded transaction; a new
  generation for the same scope and period appends a revision (`root_brief_id`, `revision`). Partial unique
  indexes prevent two chains per period and two scheduled briefs per scope, period and configuration.
- **Generation:** deterministic Java composes all facts, dates, quotes, relevance and conflicts; one optional
  model call adds interpretation, validated item by item with `CitationValidator` against only the labels
  that item may cite. Identical inputs (fingerprint) never call the model again.

## Alternatives considered

- **New `brief` module:** would need the same dependencies plus public copies of intelligence helpers; no
  gain in isolation.
- **In `automation`:** it may call intelligence services, but the brief is not orchestration of other
  modules' work, and it would put the read-only portfolio rule out of `ArchitectureTest`'s intelligence check.
- **Reuse `automation_jobs`:** would need a nullable workflow id, a new job type and an `intelligence →
  automation` dependency (cycle).
- **`@Scheduled(cron)` firing the generation directly:** a run missed while the app is down would be lost, and
  two instances would generate twice.
- **Import time as "new":** an index-version bump or a late index would resurface or hide research.

## Consequences

- Briefs are restart-safe and idempotent; regeneration history is kept.
- The "pending" view does not know about M2 workflow stages (for example a report still being downloaded).
- `FailureClassifier.safe`/`backoff` moved to `common.error.ErrorSummaries` so both queues share them.
