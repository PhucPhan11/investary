# ADR 0010 — Research workflow orchestration in PostgreSQL

## Status

Accepted (Phase 5 / Milestone 2).

## Context

New research arrives from Gmail sync, EML uploads and library uploads. Downloading reports, indexing,
portfolio matching and impact assessment already exist, but each had to be triggered by hand or ran on
its own schedule. We need them chained automatically, durably (restarts, partial failures) and without
duplicate side effects, on a single local machine.

## Decision

- Orchestrate in the existing `automation` module, which may call the `application` services of `email`,
  `research`, `knowledge`, `intelligence` and `document`. Nothing imports `automation`, so no cycle arises.
- One workflow per source and a PostgreSQL job table (`automation_jobs`) using the same pattern as the
  download and indexing queues: `FOR UPDATE SKIP LOCKED`, claim tokens, leases, and a unique idempotency
  key per stage, portfolio and processing version.
- Owning modules publish plain Spring events only after their data is committed (`EmailImportedEvent`,
  `DocumentUploadedEvent`, `ReportDownloadedEvent`); a periodic reconcile covers lost events.
- Stage handlers run with no transaction open; results are applied in one short, token-guarded transaction.
- Deterministic matching precedes any model call; assessments are unique per portfolio, trigger source,
  holdings snapshot and content fingerprint.
- Process-wide semaphores limit concurrent chat and embedding calls.

## Alternatives considered

- **Kafka / RabbitMQ / Temporal:** new infrastructure for a single-user local app; ruled out.
- **`@TransactionalEventListener` doing the work inline:** loses work on crash and would run downloads or
  model calls on request threads.
- **A new `workflow` module:** equivalent dependencies; `automation` already owns the local-only
  `/api/v1/automation` surface and the Gmail source rules the download policy needs.

## Consequences

- Workflow state is queryable and recoverable after restarts; manual retry never repeats completed stages.
- `ArchitectureTest` allows `automation → email, research, knowledge, intelligence, document`.
- Caps are best-effort under concurrency (bounded by worker count).
